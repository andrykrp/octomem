# 03. Руководство администратора

Статус документа: черновик 0.1. Все поля и семантика сверены с исходным кодом.

## 1. Наблюдение через `stats`

Основной источник метрик — команда `stats` `[ИСТОЧНИК docs/07-security-operations-release.md:64]`.
Ответ — строки `STAT <имя> <значение>\r\n`, завершение `END\r\n`
`[ИСТОЧНИК docs/04-protocol-compatibility-specification.md:182]`.

### 1.1. Общий `stats`

Формируется из пяти констант, 26 счётчиков и 6 датчиков — итого 37 строк `STAT`
`[ИСТОЧНИК src/main/java/ru/octomem/protocol/CacheCommandDispatcher.java:219-237]`.

Константы процесса `[ИСТОЧНИК src/main/java/ru/octomem/stats/AtomicStatsRegistry.java:27-29, CacheServer.java:75-79]`:
`pid`, `version` (значение `1.0.0 (octomem)`), `pointer_size`, `threads`, `limit_maxbytes`.

Счётчики в порядке перечисления `[ИСТОЧНИК src/main/java/ru/octomem/stats/Counter.java:8-60]`:
`total_connections`, `conn_yields`, `cmd_get`, `cmd_set`, `cmd_flush`, `cmd_touch`,
`get_hits`, `get_misses`, `delete_hits`, `delete_misses`, `incr_hits`, `incr_misses`,
`decr_hits`, `decr_misses`, `cas_hits`, `cas_misses`, `cas_badval`, `touch_hits`,
`touch_misses`, `total_items`, `reclaimed`, `expired_unfetched`, `evicted_unfetched`,
`evictions`, `bytes_read`, `bytes_written`.

Датчики в порядке перечисления `[ИСТОЧНИК src/main/java/ru/octomem/stats/Gauge.java:9-21]`:
`curr_connections`, `connection_structures`, `curr_items`, `bytes`, `uptime`, `time`.

Пояснения к отдельным полям:

- `curr_connections` и `connection_structures` публикуются транспортом
  `[ИСТОЧНИК src/main/java/ru/octomem/transport/NioTransport.java:269-273]`;
  `connection_structures` — выделенные структуры соединений, не «соединения за всё время»
  `[ДОКАЗАТЕЛЬСТВО tests/reports/T013/ds/acceptance-ledger.md (A03)]`.
- `curr_items` и `bytes` — текущее число связанных записей и сумма их запрошенных байтов;
  это не физические байты страниц `[ИСТОЧНИК DefaultCacheEngine.java:212-218]`
  `[ДОКАЗАТЕЛЬСТВО tests/reports/T013/raw/a11GeometryUsesPhysicalPagesAndLinkedRequestedBytes-*.wire.txt]`.
- `uptime` и `time` выставляются диспетчером непосредственно перед снимком: `uptime` —
  секунды с момента создания диспетчера, `time` — текущее время часов
  `[ИСТОЧНИК CacheCommandDispatcher.java:160-161]`.
- `limit_maxbytes` — значение `memory.limit` `[ИСТОЧНИК CacheServer.java:79]`.
- Счётчики монотонны и насыщаются на `Long.MAX_VALUE`
  `[ИСТОЧНИК src/main/java/ru/octomem/stats/Counter.java:4-6, AtomicStatsRegistry.java:120-127]`.
- Снимок слабо согласован между полями (не линеаризуемый «снимок всего реестра»)
  `[ИСТОЧНИК src/main/java/ru/octomem/stats/Snapshot.java:5-13]`.

`[НЕ ПРОВЕРЕНО]` Поле `eviction_policy` в общем `stats` **не выводится**: его нет среди
констант, счётчиков и датчиков `[ИСТОЧНИК CacheCommandDispatcher.java:46]`. Требование
`docs/04` условно («если эталон его выводит») `[ИСТОЧНИК docs/04:196]`, но фактический
вывод зафиксирован именно так.

### 1.2. `stats items`

Выводятся по одному классу размера (идентификатор в имени — `id+1`, то есть с 1),
только для классов, у которых число записей не ноль
`[ИСТОЧНИК CacheCommandDispatcher.java:239-269,315]`.

Поля на класс (11) `[ИСТОЧНИК CacheCommandDispatcher.java:240-241]`:
`number`, `age`, `evicted`, `evicted_nonzero`, `evicted_time`, `outofmemory`, `tailrepairs`,
`reclaimed`, `expired_unfetched`, `evicted_unfetched`, `crawler_reclaimed`.

Особенности семантики (проверены по коду и отчётам):

- `age` — `now − oldestActivityTime`, если есть записи и время в прошлом, иначе `0`
  `[ИСТОЧНИК CacheCommandDispatcher.java:287-288]`.
- `evicted_time` — возраст **последнего** вытеснения (выборочное значение), а не
  накопленное время `[ИСТОЧНИК AtomicStatsRegistry.java:212]`
  `[ДОКАЗАТЕЛЬСТВО tests/reports/T013/raw/a12EvictedAgeIsLastEventSampleNotElapsedTime-*.wire.txt]`.
- `tailrepairs` всегда `0`: разрушительного «ремонта» застрявших ссылок в реализации нет
  `[ИСТОЧНИК CacheCommandDispatcher.java:293]`.

### 1.3. `stats slabs`

Выводятся только для классов, у которых есть хотя бы одна физическая страница
(`total_pages != 0`) `[ИСТОЧНИК CacheCommandDispatcher.java:261]`.

Поля на класс (15) `[ИСТОЧНИК CacheCommandDispatcher.java:242-244]`:
`chunk_size`, `chunks_per_page`, `total_pages`, `total_chunks`, `used_chunks`,
`free_chunks`, `free_chunks_end`, `get_hits`, `cmd_set`, `delete_hits`, `incr_hits`,
`decr_hits`, `cas_hits`, `cas_badval`, `mem_requested`.

Особенности:

- `chunks_per_page` = `total_pages == 0 ? 0 : total_chunks / total_pages`
  `[ИСТОЧНИК CacheCommandDispatcher.java:300]`.
- `free_chunks_end` всегда `0` — совместимостное поле
  `[ИСТОЧНИК CacheCommandDispatcher.java:305]`.
- `mem_requested` — запрошенные байты класса `[ИСТОЧНИК CacheCommandDispatcher.java:313]`.
- `get_hits`, `cmd_set`, `delete_hits`, `incr_hits`, `decr_hits`, `cas_hits`, `cas_badval` —
  счётчики класса `[ИСТОЧНИК CacheCommandDispatcher.java:306-312]`.
- Геометрия классов (страницы/чанки) — физические, а `bytes` в общем `stats` — логические
  `[ДОКАЗАТЕЛЬСТВО tests/reports/T013/raw/a11GeometryUsesPhysicalPagesAndLinkedRequestedBytes-*.wire.txt]`.

Если бюджет снимка классов исчерпан, ответ — `SERVER_ERROR statistics unavailable`
`[ИСТОЧНИК CacheCommandDispatcher.java:169-176]`.

## 2. `stats reset` — семантика сброса

- Команда отвечает строкой `RESET\r\n` `[ИСТОЧНИК CacheCommandDispatcher.java:156, docs/04:182]`.
- Сброс **атомарно заменяет эпоху счётчиков** на новую с нулями; завершённые до начала
  сброса приращения удаляются, начатые после — попадают в новую эпоху. Приращения,
  пересёкшиеся со сбросом, могут быть отнесены к любой из эпох, но **не к обеим**
  `[ИСТОЧНИК src/main/java/ru/octomem/stats/StatsRegistry.java:44-51, AtomicStatsRegistry.java:159-162]`.
- **Датчики, константы и уже выданные снимки не меняются** `[ИСТОЧНИК StatsRegistry.java:46-48]`.
- Сброс **не** удаляет записи, не меняет токены `cas`, возраст и геометрию распределителя;
  сбрасываются только счётчики (общие и по классам)
  `[ДОКАЗАТЕЛЬСТВО tests/reports/T013/raw/a15ResetPreservesRecordsCasAgeGeometryAndClearsCounters-*.wire.txt]`.
- Снимок, наложившийся на сброс, может представлять старую эпоху; смешения эпох не
  происходит `[ИСТОЧНИК StatsRegistry.java:35-41]`.

`[РЕКОМЕНДАЦИЯ]` Не используйте `stats reset` для инвалидации кеша — для этого есть
`flush_all` `[ИСТОЧНИК docs/04:169]`.

## 3. Ёмкость и вытеснение

### 3.1. Распределитель памяти

- Классы размеров начинаются с 64 байт; следующий размер = `ceil(предыдущий × 1.25)`
  с округлением вверх до 8, с потолком на округлённом максимуме
  `[ИСТОЧНИК src/main/java/ru/octomem/storage/FfmSlabAllocator.java:42-48,97-100]`.
- Страница занимает ровно `pageSize`, либо ровно округлённый размер блока, если он больше;
  в составе сервера `pageSize = min(1 МиБ, memory.limit)`
  `[ИСТОЧНИК FfmSlabAllocator.java:47-48, CacheServer.java:80-82]`.
- Максимальный размер блока выводится из `item.maxSize`
  `[ИСТОЧНИК CacheServer.java:82]`.
- `memory.limit` ограничивает физические байты страниц; пустые страницы остаются в пуле и
  продолжают учитываться до `trim()`/`close()`
  `[ИСТОЧНИК FfmSlabAllocator.java:18-25,232-236]`.
- Если места нет, `allocate` возвращает `null`; это запускает предварительное вытеснение
  `[ИСТОЧНИК FfmSlabAllocator.java:109-165]`.

### 3.2. Приём записи

- Полный размер записи вычисляется как `(32 + длины ключа + значения + 7) с округлением до 8`
  и сравнивается с `item.maxSize`; превышение → `SERVER_ERROR object too large for cache`
  `[ИСТОЧНИК src/main/java/ru/octomem/engine/DefaultCacheEngine.java:300-301, CacheCommandDispatcher.java:194]`.
- При нехватке памяти выполняется предварительное вытеснение, затем повтор допуска; не
  более 1024 попыток на команду `[ИСТОЧНИК DefaultCacheEngine.java:303-318]`.
- Если память всё равно не выделена, ответ — `SERVER_ERROR out of memory storing object`
  `[ИСТОЧНИК CacheCommandDispatcher.java:195]`.

### 3.3. Предварительное вытеснение (preflight)

`[ИСТОЧНИК DefaultCacheEngine.java:716-747]`:

- до четырёх фаз; сначала освобождаются просроченные записи (`Removal.TAIL`), затем при
  политике `lru` — «живые» давно неиспользуемые записи (`Removal.EVICTION`);
- просмотр — не более 1024 записей на сегмент/класс в точном порядке LRU;
- пропускаются закреплённые (pinned) записи и целевые ключи текущей команды;
- освобождение пустых страниц вызывается через `allocator.trim()`.

Удаление по маршрутам вытеснения и очистки отражается в счётчиках `evictions`,
`reclaimed`, `expired_unfetched`, `evicted_unfetched` и в событиях классов
`[ИСТОЧНИК src/main/java/ru/octomem/stats/AtomicStatsRegistry.java:204-225]`.

### 3.4. Политика вытеснения

Конфигурация допускает **только** значение `lru`
`[ИСТОЧНИК src/main/java/ru/octomem/config/ConfigLoader.java:190-195]`.
Внутренние настройки движка дополнительно распознают `noeviction`, но выбрать его через
файл или CLI нельзя `[ИСТОЧНИК DefaultCacheEngine.java:66]`.

### 3.5. Срок жизни

- Просроченная запись удаляется лениво при обращении
  `[ИСТОЧНИК DefaultCacheEngine.java:190-198]`.
- Фоновый поток очищает просроченные записи с периодом `expiry.reclaimIntervalSeconds`
  (по умолчанию 1 с), не более 256 записей за проход, малыми сегментными партиями
  `[ИСТОЧНИК DefaultCacheEngine.java:143-148,695-714]`.
- Ошибка фонового прохода не завершает процесс: регистрируется событие `EXPIRY_FAILURE`
  уровня `error` `[ИСТОЧНИК DefaultCacheEngine.java:145-146, SafeLogger.java:23]`
  `[ДОКАЗАТЕЛЬСТВО tests/reports/T013/ds/README.md (F1)]`.

## 4. Отчётность о ресурсах

- В коде существует внутрипроцессный метод `CacheServer.resourceUsage()`, возвращающий
  запись с полями `controlBytes`, `lineBytes`, `bodyBytes`, `nativeTotalBytes`,
  `nativeUsedBytes`, `snapshotBytes`, `serviceBytes`, `currentConnections`,
  `rejectedConnections` `[ИСТОЧНИК src/main/java/ru/octomem/cli/CacheServer.java:139-163]`.
- **В 1.0 этот метод не доступен ни через CLI, ни через протокол `stats`.** Число
  отклонённых соединений явно помечено как «пока не выведено в stats»
  `[ИСТОЧНИК src/main/java/ru/octomem/transport/NioTransport.java:189-193]`.
  `[НЕ ПРОВЕРЕНО]` Публичного административного способа прочитать `resourceUsage` нет.
- Значения полей — логические резервирования и бухгалтерия, **не** измерение heap или RSS
  `[ИСТОЧНИК NioTransport.java:195-212]`.
- `[РЕКОМЕНДАЦИЯ]` Наблюдение за реальным потреблением памяти ведите средствами ОС
  (RSS, heap, direct memory) и JFR, а `stats` используйте для счётчиков и логического
  учёта `[ИСТОЧНИК docs/java-platform.md:25-27]`.

### 4.1. Что рекомендуется отслеживать

`[ИСТОЧНИК docs/07-security-operations-release.md:64-71]` (рекомендации по мониторингу):

- `curr_items`, `bytes`, `limit_maxbytes`, `evictions`;
- `get_hits`, `get_misses`;
- `curr_connections`, `conn_yields`.

Сигналы тревоги `[РЕКОМЕНДАЦИЯ]`:

- рост `evictions` относительно обычного фона;
- `bytes`, близкое к `limit_maxbytes`;
- рост `curr_connections` до `connections.max`;
- падение отношения `get_hits` к сумме `get_hits + get_misses`.

## 5. Безопасная остановка

- `Ctrl+C` (SIGINT) или SIGTERM запускают зарегистрированный shutdown-hook
  `octomem-shutdown`, который вызывает `CacheServer.close()`
  `[ИСТОЧНИК src/main/java/ru/octomem/cli/Main.java:153-154]`.
- `close()` идемпотентен: останавливает транспорт, дожидается снятия удержаний/курсоров,
  останавливает движок и закрывает страницы; прерывания не пропускают очистку
  `[ИСТОЧНИК CacheServer.java:179-200]`.
- При нормальной остановке пишется событие `STOPPED` уровня `info`, а `Main` возвращает
  код 0 `[ИСТОЧНИК CacheServer.java:197, Main.java:165]`.
- Клиентская команда `quit` закрывает только своё соединение и **не** останавливает сервер
  `[ИСТОЧНИК CacheServer.java:165-171]`.

## 6. Ненулевой код возврата при внутреннем отказе

- **1** — ошибка запуска или работы. Возникает при исключении `IOException`/`RuntimeException`
  во время старта/ожидания, включая автономный отказ транспорта: `awaitTermination`
  выбрасывает `IllegalStateException`, `Main` регистрирует `START_FAILURE` и возвращает 1
  `[ИСТОЧНИК CacheServer.java:173-177, Main.java:166-172]`.
- **2** — ошибка аргументов или конфигурации; проверяется до старта сервера
  `[ИСТОЧНИК Main.java:136-139,181-184]`.
- **0** — успех; см. §4 документа 02.
- `[НЕ ПРОВЕРЕНО]` Специальные коды 130/143 по сигналам не реализованы (см. документ 02 §4).

`[РЕКОМЕНДАЦИЯ]` Ненулевой код 1 означает, что сервер не запустился или транспорт
отказал автономно, и не является сам по себе вердиктом о качестве продукта.
