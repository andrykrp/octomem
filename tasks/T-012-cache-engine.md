# T-012. Реализация CacheEngine

**Цель.** Собрать единую реализацию `CacheEngine` поверх хранилища, срока жизни, вытеснения и атомарности.

**Зависит от.** T-009, T-010, T-011.

**Входы.** docs/03 §4.1, ЧТЗ-04, ЧТЗ-05.

**Результаты.**
- Реализация всех методов `get`, `set`, `add`, `replace`, `append`, `prepend`, `cas`, `delete`, `increment`, `decrement`, `touch`, `flushAll`.
- Управление жизненным циклом `start`/`stop`.
- Удержание записей при выдаче (`ItemLease`).
- Согласованность счётчиков с операциями.

**Критерии приёмки.**
1. Все методы работают по семантике docs и docs/04.
2. По [ADR-016](../docs/adr/ADR-016-bounded-command-snapshots.md) целая команда захватывает ограниченный снимок: все уникальные segment locks по индексу, один capturedClock, неизменяемые версии pin до unlock, фиксированные промахи. Один курсор сериализации не означает один pin; каждый уникальный ключ выводится один раз в порядке первого появления.
3. Удаление и вытеснение во время выдачи не приводят к чтению переиспользованного блока.
4. `flushAll` с задержкой и без работает по правилам.
5. Unit-тесты покрывают обычные и граничные случаи.
6. `snapshot`/`snapshotAndTouch` резервируют полные метаданные до захвата/первого байта: cap `min(floor(memory.limit / 16), 16 MiB)`, checked плата `512 + 512 * N`. Ровно шесть плоских массивов по ADR-016 F3: reference[N], три int[N], keyLengths[N], keys[250*N]; layout 271N payload, 384 headers и 128 wrapper/scratch при ссылке≤8/array overhead≤64 проверяется на JDK25. Без perkey объектов/роста/рекурсии, in-place heapsort; internal borrowed key view, не defensive copies. Отказ даёт CacheCapacityException и SERVER_ERROR через независимую control lane без частичного VALUE; поздний отказ допуска запрещён.
7. Multi-gat готовит все новые версии и резервы до публикации под всеми locks; failure освобождает неопубликованное без изменения любого исходного значения/TTL/CAS. Дубли обновляются один раз; flush/expiry/eviction не нарушают общую точку видимости.
8. Snapshot close идемпотентно освобождает все refs и метаданные на COMPLETE/cancel/disconnect/shutdown, включая невыданные записи. Retired-версии учтены в native memory.limit до refs0; замены не ждут сокета и либо получают резерв, либо OOM без частичного состояния.
9. Порядок locks: sorted segments → allocator mutex → LRU; никакого нового segment lock/eviction под набором. Eviction preflight после unlock, целевые ключи исключены; повторить весь захват/Clock/проверку. Writer/cursor/close confined циклу событий, shutdown передаёт отмену. Body byte[] приходит с целым payload+32 резервом ingress по ADR-016 F1, без роста/копирования old+new; отказ allocation/cancel освобождает полученное, не обещает восстановление после произвольного JVM OOME.

**Статус.** ADR-013/015/016 ACCEPTED координатором после ревью без замечаний `97c8c23b86be`; R-04/R-05/R-08/R-09 закрыты как DESIGN findings, R-07 уже закрыт. Реализация интерфейсов T-004 разрешена, зависимые работы сохраняют условия своих карточек; завершение/заморозка T-004 и runtime/test/performance/release PASS не заявлены. Владелец разрешил только замену однозаписного/независимого от ответа удержания; FR-40, wire-дедупликация и четырнадцать defaults сохранены.

**Проверка задачи.** Unit-тесты движка, включая удержание записи при выдаче. **Трассировка к релизу.** [TEST-04](../tests/TEST-04-functional.md), [TEST-06](../tests/TEST-06-concurrency.md).

M1/M2: line charge `2L*L+256`, perconn `satMul(maxInflight,charge)`, global `satMul(connections.max,perconn)`, LIMIT-only saturation Long.MAX_VALUE без config rejection/урезания public limits; actual reserve `charge<=limit-used`, counters не насыщаются. Snapshot API принимает engine.KeySequence: size одним scan, forEachKey(KeyVisitor) передаёт borrowed immutable backing/offset/length до command release. Count N до reserve/allocation, sequential fill flat keys, без per-token arrays/views/List/O(N²); оба резерва до handoff/release line. Raw line old+new≤2L плюс overhead256 по ADR-016, нет shrink-copy. Slot до чтения, body reserve не удерживается ради line capacity; пятый heap-пул отдельно от native, theoretical ceiling не allocation/RSS-гарантия.

**Роли.** Исполнитель — сильный; контролёр — сильный.

**Вне области.** Сетевой транспорт и разбор протокола.

**Ограничения.** Не менять интерфейс `CacheEngine` без ADR.
