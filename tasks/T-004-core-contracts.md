# T-004. Контракты ядра, протокола и модели записи

**Цель.** Зафиксировать интерфейсы `CacheEngine`, `Item`, `ItemLease`, `GetResult`, `RetrievalSnapshot`, `MemoryAllocator`, а также контракты протокола `ProtocolDecoder`, `ProtocolEncoder`, `ResponseWriter`, `ChunkSink`, `WriteStatus`, `Command`, `CommandType`, `Response`, `ResponseKind`. Multi-get выражается через `Command.keys()` и `Response.snapshot()` по [ADR-016](../docs/adr/ADR-016-bounded-command-snapshots.md), а не цикл одиночных `get`.

**Статус контрактов.** ADR-013/015/016 ACCEPTED координатором 15.09.2026 после ревью без замечаний `97c8c23b86be`. R-04/R-05/R-08/R-09 закрыты как DESIGN findings, R-07 уже закрыт. Реализация интерфейсов T-004 разрешена; выполнение/заморозка задачи требуют её собственной приёмки, runtime/test/performance/release PASS не заявлен. Владелец разрешил заменить только однозаписное и независимое от размера ответа удержание, не FR-40 или дедупликацию/defaults.

**Зависит от.** T-001.

**Входы.** docs/03 §4.1–4.3 и §4.6; [ADR-013](../docs/adr/ADR-013-immutable-published-item-version.md), [ADR-014](../docs/adr/ADR-014-package-dependency-edges.md), [ADR-015](../docs/adr/ADR-015-chunk-sink-crlf-capacity.md).

**Результаты.**
- Интерфейсы ядра и модели записи без реализации.
- Контракты протокола без реализации.
- Типы результатов: `StoreResult`, `GetResult`, `CounterResult`.
- Контракт удержания записи (`ItemLease`).
- Размещение типов по ADR-014: `Item`/`ItemLease` в `ru.octomem.storage`, `GetResult` в `ru.octomem.engine`, согласованные сигнатуры.
- Контракт неизменяемой опубликованной версии (allocate → publish → retire, отдельные LRU/refcount, OOM сохраняет старое) по ADR-013.
- Проверяемое `CacheCapacityException` пакета `engine` с ограниченными метаданными без внутренних сообщений; сигнатуры `touch` и `getAndTouch` объявляют `throws`.
- Контракт ёмкости приёмника и неразрывного `\r\n` по ADR-015.
- Документированные инварианты и владение ресурсами.

**Критерии приёмки.**
1. Сигнатуры совпадают с docs/03 §4.1–4.3 и §4.6.
2. Компиляция проходит; реализации отсутствуют или являются заглушками.
3. Контракты удержания и освобождения описаны в Javadoc.
4. Изменение интерфейса после заморозки невозможно без ADR.
5. Владение типами и рёбра пакетов соответствуют ADR-014; неизменяемость версии — ADR-013; ёмкость `ChunkSink` ≥ 2 и `IN_PROGRESS` при 0/1 — ADR-015.
6. `touch` и `getAndTouch` объявляют `throws CacheCapacityException`; тип принадлежит `engine` и не заменяет отказ значением `false`/промахом; multi-`gat` не применяет обновление частично (R-04 закрыт на уровне DESIGN, проверка реализации обязательна).
7. `snapshot`/`snapshotAndTouch` объявляют `throws CacheCapacityException`; `RetrievalSnapshot` в `engine` владеет refs и метаданными, один курсор выдаёт заимствованные Item. Все locks по индексу, один capturedClock, pin до unlock, фиксированные промахи, вывод без дублей в порядке первого появления.
8. Javadoc фиксирует передачу владения снимком Response/писателю и идемпотентное закрытие при COMPLETE, отказе передачи, cancel/disconnect/shutdown, включая непосещённые записи; native-байты очереди не остаются без удержания.
9. Зафиксировать checked long атомарные reserve/release для heap-пулов ADR-016 F1/F2/F3 отдельно от native: body `max(item.maxSize+32,min(memory.limit,64 MiB))`, control `544*2*connections.max`, необязательный burst `min(memory.limit,64 MiB)`, snapshot cap `min(floor(memory.limit/16),16 MiB)` с платой `512+512*N`. Целый body payload+32 до allocation/read, без роста/old+new; отказ немедленно выбирает OOM и framed drain через собственную receive512, затем error/pipeline с правильным noreply. Transmit512 независим от burst для всех ответов и CRLF2, fair round-robin без spin/unlimited wait queues; lane overhead32 каждая, резерв до регистрации соединения, lazy allocation.
10. Зафиксировать шесть массивов F3, максимум ключа250, 271N payload/384 headers/128 wrapper-scratch, HotSpot layout assumptions и их проверку, iterative in-place heapsort/compaction без perkey объектов/рекурсии/массивов по общему числу сегментов; internal key view без defensive copy. Sorted segments → allocator → LRU, eviction preflight отдельно после unlock с защищёнными целевыми ключами и повторным Clock; writer/cursor/close confined. Допуск снимка до первого байта и полный rollback multi-gat обязательны; четырнадцать ключей/defaults не меняются, произвольный JVM OOME не считается восстановимым admission failure.

**Проверка.** Ревью сигнатур контролёром, сборка.

M1/M2: пятый line pool charge `2L*L+256`, perconn `satMul(maxInflight,charge)`, global `satMul(connections.max,perconn)`. Только LIMIT saturate Long.MAX_VALUE без config rejection/урезания public limits; CAS actual reserve `charge<=limit-used`, без насыщения counters/charges, shortage ресурсный не startup. Engine.KeySequence и KeyVisitor обязательны для Command.keys()/snapshot/snapshotAndTouch, protocol реализует разрешённое ребро к engine. Size одним scan, forEachKey(visitor) последовательный borrowed backing/offset/length до command release; нет perkey массивов/List/views/offset tables/getKey copies/рекурсии/O(N²). Raw backing геометрически до L, old+new≤2L, максимум headers2*64+wrappers/cursor128 внутри overhead256, без shrink-copy. Count N → snapshot reserve → шесть массивов → sequential fill, оба резерва до handoff; pipeline/control правила сохранены. Тестовый List adapter вне production-бюджета не обязательный API, RSS измеряется отдельно.

**Роли.** Исполнитель — сильный (контракты); контролёр — сильный.

**Вне области.** Реализация хранилища и протокола.

**Ограничения.** После закрытия задачи интерфейсы считаются замороженными.
