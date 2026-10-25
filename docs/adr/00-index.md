# ADR octomem: реестр и трассировка

**Текущая версия 0.6, 15.09.2026.** Координатор принял ADR-013/015/016 после независимого ревью без замечаний `97c8c23b86be` (task_ce9fef41dc47, ctx_01a12ec0a55e). R-04/R-05/R-08/R-09 **закрыты как DESIGN findings**, R-07 уже закрыт. Разрешена реализация интерфейсов T-004; завершение/заморозка задачи требуют её приёмки, runtime/test/performance/release PASS не заявлен. Прежние вступления 0.4/0.5, описания корректировок и условия приёмки ниже сохраняются как история; их PROPOSED/открытые статусы не являются текущими блокерами. Текущие статусы реестра и новый хеш-baseline имеют приоритет; архитектура, FR-40, wire-дедупликация, узкое разрешение владельца и четырнадцать defaults не меняются.

Версия 0.4 от 15.09.2026. Индекс архитектурных решений, введённых для закрытия замечаний [ревью от 14.09.2026](../review-2026-09-14.md). Это изменения контрактной документации, а не доказательство работы реализации. Правка 0.3 исправила confirmed findings; правка 0.4 фиксирует **принятие ADR-014 (R-07)** координатором на точном существующем графе `docs/03` без изменения рёбер. ADR-013 и ADR-015 остаются **предложениями (PROPOSED)**. R-05/R-08 **не закрыты**, R-07 **закрыт**, T-004 **не заморожен**. Диспозиция T-001 — в [карточке](../../tasks/T-001-build-skeleton.md).

## 1. Реестр

[ADR-020](ADR-020-memcached-protocol-families.md), 25.10.2026: общий атомарный движок и ограниченные codecs classic/binary/meta, PROPOSED, работа по T-019. Не считать реализацию принятой по одному ADR.

25.10.2026 добавлен [ADR-019](ADR-019-wire-cas-and-set-failure.md): различение wire-CAS и внутренней версии, узкое исключение SET из общего rollback при allocation-отказе. **ACCEPTED как дизайн** после независимого ревью `ctx_d5ab24e0309e`; полная приёмка реализации ещё проверяется. Старые хеши и статусы ниже не являются доказательством этой коррекции.

Уточнение от 18.09.2026: [ADR-018 — владение и учёт геометрии движка](ADR-018-engine-geometry-ownership.md)
принят для исправлений R1–R3 повторного ревью OPT-01. Локальные независимые тесты,
повторное ревью и проверка координатора — [post-review/STATUS.md](../../tests/reports/optimization/post-review/STATUS.md).
Это не приёмка выпуска и не перенос исторических платформенных результатов.

Уточнение 0.5 от 15.09.2026: ADR-016 **PROPOSED** после узкого разрешения владельца на bounded snapshots. R-04/R-09 открыты до независимого ревью, но механизм и производные бюджеты теперь предложены; прежнее «не проектируется/отложен» ниже относится к состоянию 0.4. ADR-013/015 остаются PROPOSED, ADR-014 принят, T-004 не заморожен. FR-40, дедупликация и четырнадцать ключей/defaults сохранены.

| ID | Решение | Замечание | Документ | Статус |
|---|---|---|---|---|
| ADR-013 | Неизменяемая опубликованная версия записи; проверяемый `CacheCapacityException` для `touch`/`getAndTouch` | R-05 | [ADR-013](ADR-013-immutable-published-item-version.md) | ACCEPTED 15.09.2026; R-05 закрыт как DESIGN finding |
| ADR-014 | Рёбра зависимостей пакетов и владение типами; `stats` только на JDK | R-07 | [ADR-014](ADR-014-package-dependency-edges.md) | ПРИНЯТО 15.09.2026; точный граф `docs/03` без изменения рёбер, R-07 закрыт |
| ADR-015 | `ChunkSink` ≥ 2, `protocol.maxChunkBytes` ≥ 2 и неразрывный `CRLF` | R-08 | [ADR-015](ADR-015-chunk-sink-crlf-capacity.md) | ACCEPTED 15.09.2026; R-08 закрыт как DESIGN finding |
| ADR-016 | Ограниченный снимок целой команды, prepare-all multi-gat, раздельные native/heap-бюджеты и lifecycle | R-04/R-09 | [ADR-016](ADR-016-bounded-command-snapshots.md) | ACCEPTED 15.09.2026; R-04/R-09 закрыты как DESIGN findings |

## 2. Трассировка

Коррекция F1/F2/F3 (15.09.2026, ожидает ревью): полный body reserve payload+32 до чтения, body cap `max(item.maxSize+32,min(memory.limit,64 MiB))`; отдельный control pool `544*2*connections.max`, собственные receive512/transmit512 с overhead32; burst `min(memory.limit,64 MiB)` только ускоряет. Snapshot charge `512+512*N` при прежнем cap обоснован шестью плоскими массивами, iterative heapsort и проверяемым HotSpot layout. Отказ body admission даёт framed drain/OOM, не цикл ожидания; нормальные/ошибочные ответы и CRLF2 идут независимо от чужого burst. Locks sorted segments → allocator → LRU, preflight eviction отдельно. FR-40/дедупликация/14defaults сохранены, R-04/R-09 не закрыты, T-004 не заморожен.

| Замечание | Решение | Синхронизированные документы | Карточки | Испытания |
|---|---|---|---|---|
| R-05 | ADR-013 | docs/03 §4.1/§4.2/§7/§11/§13; docs/04 §3.3/§5.3/§7/§8; docs/05 ЧТЗ-02-R19, ЧТЗ-03-R14, ЧТЗ-05-R11…R13; docs/06 Т-01/Т-02/Т-04 | T-004, T-009, T-011 | TEST-01, TEST-06 |
| R-07 | ADR-014 | docs/03 §3/§4.1/§4.2/§4.3/§11/§13; docs/05 ЧТЗ-03-R14 | T-004, T-007, T-009 | TEST-01 |
| R-08 | ADR-015 | docs/03 §4.6/§8/§11/§13; docs/05 ЧТЗ-02-R18, ЧТЗ-07-R11; docs/06 Т-02; design/D-02 | T-002, T-004, T-007 | TEST-01 |
| R-04/R-09 | ADR-016 | docs/02 FR-40; docs/03 §4–7; docs/04 §3/§8; docs/05 ЧТЗ-01/02/03/04/05; docs/06 Т-01/02/04/05/06; ref-sources-and-open-questions | T-004, T-009, T-012 | TEST-01, TEST-06 |

Ключевые уточнения правки 0.3:

- **`gat`/`gats` не поддерживают `noreply`:** это команды чтения, они всегда отвечают, а лексема `noreply` в позиции ключа — обычный ключ. Текстовый сценарий добавлен в docs/06 Т-01 и TEST-01.
- **Подавление отказа ёмкости только у `touch`** и только при явной лексеме `noreply`; у `gat`/`gats` отказ `CacheCapacityException` всегда даёт `SERVER_ERROR out of memory storing object`. Синхронизированы docs/03 §4.1/§7, docs/04, docs/05 ЧТЗ-02-R19, ADR-013, docs/06 Т-02.
- Прочие решения правки 0.2 сохранены: `protocol.maxChunkBytes` ≥ 2 (значение по умолчанию `64k` не меняется), ёмкость приёмника ≥ 2 и временная ёмкость 1, `CacheCapacityException` с ограниченными метаданными, `stats` только на JDK, атомарность multi-gat как требование при открытом R-04.

Никакие исходники, `pom.xml`, сборки, конвейеры, коммиты и рабочие деревья не создавались и не менялись. Правка выполнена как малые текстовые изменения.

## 3. История границ до принятия

Полный roster: native cache плюс snapshot/body/control/optional burst/line-descriptor. M1 line charge `2L*L+256`, perconn `satMul(maxInflight,charge)`, global `satMul(connections.max,perconn)`; только LIMIT насыщаются до Long.MAX_VALUE без config rejection/урезания public limits, actual CAS reserve `charge<=limit-used` без насыщения счётчиков. M2 engine.KeySequence/KeyVisitor заимствуют raw line до command release без per-token allocations/List/offset arrays; один scan count и sequential fill после snapshot reserve, old+new line≤2L плюс overhead256, пик обоих резервов до handoff. Нет eager theoretical ceiling/RSS-обещания, FR-40/14defaults сохранены, ADR-016 PROPOSED.

- **R-04 остаётся ОТКРЫТЫМ до ревью ADR-016.** Предложены общий снимок и prepare-all-before-publish multi-gat с полным откатом; FR-40 не сужен.
- **R-09 остаётся ОТКРЫТЫМ до ревью ADR-016.** Предложены отдельные глобальные heap-бюджеты снимков/ввода/вывода и native accounting; новые публичные ключи/defaults не вводятся, RSS не ограничен этими логическими платами.
- **ADR-014 принят; ADR-013/015/016 остаются предложениями.** ADR-014 принят координатором на точном графе `docs/03` без изменения рёбер, R-07 закрыт. R-04/R-05/R-08/R-09 не закрыты, T-004 не заморожен. FR-40 и значения по умолчанию сохранены; производные глобальные бюджеты предложены в ADR-016, но не означают приёмку реализации.

## 4. Исторические доказательства правки 0.4

### Текущий baseline принятия 0.6

SHA-256 вычислены после механического принятия через Get-FileHash 15.09.2026; охват: 15 изменённых документов ниже, без самоссылочного хеша индекса. Хеш окончательного индекса передаётся координатору отдельно. Это baseline документации после ревью `97c8c23b86be`, не хеши кода/сборки и не runtime PASS. ADR-014 не менялся и не входит в эту таблицу; прежняя таблица 0.4 ниже остаётся исторической.

| Файл | SHA-256 baseline 0.6 |
|---|---|
| docs/adr/ADR-013-immutable-published-item-version.md | EFA833A2247F381BE6A8733411697FB868B5D5D30A153BD2606F317CB71BE98F |
| docs/adr/ADR-015-chunk-sink-crlf-capacity.md | 5514D57FDA05CDE0225ED4EAEDED9D75F009DF7E74B1426E5166882EC76C6408 |
| docs/adr/ADR-016-bounded-command-snapshots.md | CC093A97FB879B4ADD291B7C3F16540B60276AAA100E87F4C651B1EDF0D6F2DD |
| docs/02-general-technical-specification.md | 5154B60F625B752B114571E9E9AF7676D819308CC46C66F292445D3B264CFD6C |
| docs/03-architecture-specification.md | D74712541AD73B4A08530ECDDA49B13A9181C342A896F5DA441C5CEEB12F7143 |
| docs/04-protocol-compatibility-specification.md | A7819AE6464CF125994E2418381B2BD1A099F5592D10D9CB8419E68EA36312F3 |
| docs/05-detailed-technical-specifications.md | 07699A4DCBF4567B0F44D2950A0F399178B99E4AA4A5FFAE743163EB38847BFA |
| docs/06-test-specifications.md | 56991937E8C248850A146840B6C54A706C4D193BC1707126C5659BB7B69EA19F |
| docs/review-2026-09-14.md | 044F3E3873ED372A534BFC71EA4EFF2C15026E7AB389342D24EDD3EDECA60A73 |
| docs/ref-sources-and-open-questions.md | 7C7A4030EBF06DCCCAF0B880CB798235BC9C073A596E0D56DC453159CDCF8C9C |
| tasks/T-004-core-contracts.md | A77324347464BEBDEEC9127824C97FB5015D380826048C24394AD7963E7570EE |
| tasks/T-009-storage.md | DFB9E123767BF280B262AD6F40416C837AAABAF02FFAC0B6D1D0F0C10891F368 |
| tasks/T-012-cache-engine.md | 557B1361B2BF8256953EE75CE20027833FE630F23A3D62B2255DC8832D7F168E |
| tests/TEST-01-unit.md | 138E1A72F7AE6CD30E38ED6883A49CE0374AAF35FC7AF1D5F75BC7222ACD5758 |
| tests/TEST-06-concurrency.md | 1A61AA230A734C5DEDF0F64D0CF6D87B2BEB4907BE266BD4D3C900BC162FAADE |

### Исторический baseline 0.4

Таблица ниже сохранена как историческое свидетельство 0.4, **не текущие хеши** после ADR-016. Правка 0.5 меняет также docs/02, ADR-016, T-012 и реестр источников; не использовать прежние хеши для проверки текущего комплекта.

SHA-256, `Get-FileHash -Algorithm SHA256` (без сборки и компиляции):

| Файл | SHA-256 |
|---|---|
| docs/adr/ADR-013-immutable-published-item-version.md | b2c0648fd5fba4b734e2493b0f3ae63735893c90318e03a53775ae0a8807b613 |
| docs/adr/ADR-014-package-dependency-edges.md | 8590b8d1c62000da78db6d3ab0bd6e00d8cb9cbb00ae4dc366ced561499a2fc5 |
| docs/adr/ADR-015-chunk-sink-crlf-capacity.md | e452184081ac45ea5aa8c2a336074d0fba505fb3d25ad875232928e9b6ca3437 |
| docs/03-architecture-specification.md | 70c1ba2d751b3e2964604283923654ca87cccf59064828dcd22ee717d2f57c67 |
| docs/04-protocol-compatibility-specification.md | 4c7b28583c077ea452b99080027802b74db89e6212a338bbafa38a5aca581150 |
| docs/05-detailed-technical-specifications.md | f9559de6f17fb4c7fe5a33be10ef8e56178aa04e4cd50b3633d7b43a7d3920c4 |
| docs/06-test-specifications.md | f8c80fa1233b48324b89e32f794e512c0e413ce236c8b77e8205f870283330b0 |
| design/D-02-config-schema.md | 80bb4fcdefa4907c03a5240fabb75a72cd125272bca321bf7e4c9c6d23d04c72 |
| tasks/T-002-config-and-cli.md | 0a929045a7b2117c849fe882db79349371626d6b31b68194f1170c729bbbb8a6 |
| tasks/T-004-core-contracts.md | 1a3d068fc50ba75ad46e00101090c7b239eed292be7ea285983ab5d173953820 |
| tasks/T-007-protocol-codec.md | 20822871b2470d2c23d11e8221b26047b376c6c6b8b64d1608daff71f32f9a61 |
| tasks/T-009-storage.md | ad897117417e9d31aa6936c2b8f062eb35ab006903108658abe44cab60e3ed9c |
| tasks/T-011-atomics-cas.md | 09727f9dec9471cfe0cf9774104f0f8a9109866f2be39c3e5da1470d7eecc5fe |
| tests/TEST-01-unit.md | b6cc00d52cda8caf5b767ac34f3d4d0c1e8916973e8b2f4a1217576f8fe584f2 |
| tests/TEST-06-concurrency.md | 22053f01d497d6dc5d3420b2d52832f115c3195bc8e60fd8fd7c69d9275f331a |
| docs/review-2026-09-14.md | 88800af8d95e61ae6d3f463b9fa45ecb13da0932e44ffb165e9b67b533a2459f |
| tasks/T-001-build-skeleton.md | f438f7ee6d541f19f94f88658a4ed81c0b53ea9fd5265bfaf329b1338619dfaa |

Хеш самого этого индекса вычисляется после его записи и передаётся координатору отдельно. Git-метаданных в рабочем каталоге нет; это хеши контрактной документации, а не снимок исходников или артефакт кандидата. Правка 0.4 изменила `ADR-014`, `docs/03`, `docs/review-2026-09-14` и `tasks/T-001`, поэтому их хеши пересчитаны и две новые строки добавлены. Остальные записи (`ADR-013`, `ADR-015`, `docs/04`…`docs/06`, `design/D-02`, `tasks/T-002/T-004/T-007/T-009/T-011`, `tests/TEST-01/TEST-06`) правкой 0.4 не менялись и сохраняют прежние хеши.

## 5. Исторические условия приёмки

1. **Требуется независимое ревью и решение координатора.** ADR-013 и ADR-015 и их синхронизированные документы не считаются принятыми; R-05/R-08 не закрыты; T-004 не заморожен. ADR-014 принят координатором, R-07 закрыт.
2. Заявления владельца о РФ-размещении, правах и передаче исходников допускают работу с документами и моделями. Они не являются юридическим PASS и не подтверждают REG-*.
3. Независимое ревью ADR-016 проверяет открытые R-04/R-09, целую атомарную команду, полный rollback, допуск до первого байта, раздельные бюджеты, lifecycle, сохранённые defaults/дедупликацию и грамматику `noreply`. Разрешение владельца касается только удержания; не выдавать его за приёмку реализации или ослабление других критериев.
4. Точное совпадение строк ошибок `touch`/`gat`/`gats` с зафиксированным эталоном остаётся обязательным дифференциальным требованием.

## 6. Изменения

| Версия | Дата | Что изменилось |
|---|---|---|
| 0.1 | 14.09.2026 | Индекс ADR-013…ADR-015, трассировка R-05/R-07/R-08 и хеш-доказательства |
| 0.2 | 14.09.2026 | Findings: `CacheCapacityException` для `touch`/`getAndTouch` (R-05), `stats` только на JDK (R-07), `maxChunkBytes` ≥ 2 и временная ёмкость 1 (R-08); обновлены трассировка и хеши; R-04 открыт |
| 0.3 | 14.09.2026 | Findings: `gat`/`gats` не поддерживают `noreply` (лексема — обычный ключ), подавление отказа ёмкости только у `touch`; ADR-013/014/015 переведены в статус PROPOSED (не приняты), R-05/R-07/R-08 не закрыты, T-004 не заморожен; обновлены трассировка и хеши |
| 0.4 | 15.09.2026 | Принятие ADR-014 (R-07) координатором на точном графе `docs/03` без изменения рёбер; ADR-013/015 остаются PROPOSED, R-04/R-09 открыты, T-004 не заморожен; запись scoped-приёмки T-001 и обновлённый хеш-индекс |
| 0.5 | 15.09.2026 | ADR-016 PROPOSED и синхронизация R-04/R-09 после узкого разрешения владельца; снимки, откат, lifecycle и производные бюджеты, дедупликация/defaults сохранены; хеши 0.4 обозначены историческими |
| 0.6 | 15.09.2026 | Принятие ADR-013/015/016 после независимого ревью 97c8c23b86be; DESIGN R-04/R-05/R-08/R-09 закрыты, T-004 interface implementation разрешена, без runtime/test/performance/release PASS; текущий baseline отделён от исторического |
