# Расширение совместимости Memcached: результат 25.10.2026

## Финальное дополнение: локальные транспорты

**Последний повтор после добавления трёх независимых manifest-тестов:** attempt `101838Z-0f16c519` выполнил3622проверки:0 assertion failures,1 error,0 skips. Ошибка — обязательный `NioTransportTenThousandIT`, Windows `No buffer space available` при connect. Поэтому этот прогон **FAIL**, coverage-проверка в нём **NOT_REACHED**, а не PASS. Три новых manifest-теста прошли. Production-код/POM не менялись и JAR побайтово совпадает с предыдущим3619PASS; эти два факта не превращают последний полный прогон в успех. Не менялись системные лимиты, тест не пропускался, бесконечных retries нет. Полная повторная квалификация текущего набора тестов остаётся заблокирована доступностью стенда на10 000соединений; точная причина OS-исчерпания не установлена. Подробности — `local-transports/manifest-followup-results.md`.

После TCP-этапа ниже добавлены optional Unix-domain listener и loopback UDP с classic/meta/binary. Текущая сборка **FB5615475D0301EF1FF3A9A68036238D0E70ECDCA995946631195062A09C3F47** прошла независимый полный `clean verify -Pcoverage`: **3591 unit +28 IT =3619**, ноль failures/errors/skips; все прежние gates сохранены. Branch coverage3951/4078=96,89%, UDP120/123=97,56%. На том же JAR прошли Linux/WSL Unix3 и UDP7 сценариев, classic224+expanded110 наблюдений и шесть library-пилотов, включая TLS/SASL. Доказательства: `tests/reports/protocol-expansion-20261025/local-transports/final-evidence.json`, full attempt `101018Z-242e9741`. Финальная независимая проверка local-source `ctx_0bd93ee09df8` завершена без блокирующих замечаний; актуальный манифест проверяется отдельно, с оговоркой последнего полного FAIL выше.

У UDP: totalrequest1, responsecap1MiB, fragmentation1392, только loopback без auth/TLS. У Unix: сервер никогда не удаляет filesystem entry, operator-managed private directory/cleanup; existing path отказ. Проверены startup/close/fault cleanup, incomplete-body no-admission, typed binary cap error и exactcap±1. Это не надёжная доставка UDP и не разрешение external UDP. Сбой одного промежуточного10k-теста из-за OS port exhaustion сохранён; после подтверждённого восстановления TIME_WAIT новый полный прогон прошёл без изменения host settings или критерия.

Координатор отдельно повторил финальный JARfb561 без новой сборки: classic224PASS (`compatibility-recheck-20261025/differential-20261025T102527Z-1784cc31`), expanded110PASS (`expanded-differential-20261025T102547Z`),6реальных клиентов PASS (`client-pilot-20261025T102606Z`), LinuxUnix3/UDP7PASS (`local-transports/unix-20261025T102730Z` и `udp-20261025T102730Z`). Все принадлежащие прогонам процессы завершены; эти ограниченные повторы не заменяют заблокированный полный3622.

Ниже сохранён завершённый TCP-stage997A как отдельное историческое доказательство; его прежнее «UDP/Unix отсутствуют» больше не описывает текущую реализацию. Ограничения stats-parity, nonPLAIN/mTLS, admin/Couchbase, performance/soak/trustedOS/реестра сохраняются.

## Реализовано

Octomem больше не ограничен classic text. Один TCP listener обслуживает binary или ASCII по первому байту; classic/meta работают на одном ASCII-соединении. Все семейства используют один индекс, значения и CAS. Добавлены бинарные ключи, атомарные conditional mutations/counter creation, meta vivify/recache/stale/tombstone, quiet-ответы и streaming без полной копии value.

Добавлены SASL PLAIN, текстовая аутентификация и TLS1.2/1.3. Security-материалы читаются до bind; неверная настройка не допускает plaintext fallback. Внешний bind с PLAIN/text без TLS запрещён. Делегированные TLS-задачи выполняются в ограниченном пуле; connection admission удерживается до их завершения. Закрытие и readiness проверены как реальными TLS-клиентами, так и отдельными детерминированными fault-тестами. Эти виды доказательств не смешиваются.

Подробный профиль, настройки и примеры: [memcached-protocols-and-security.md](memcached-protocols-and-security.md). Исходный код Memcached использовался для изучения поведения, не включался в реализацию.

## Финальный проверенный кандидат

- JAR: `target/octomem-1.0.0.jar`.
- SHA-256: `997A7763D96C0ECC798D6430BBF8DC04CC5F2EBBE64112B371A1D3543FD9EDA5`.
- Исходный production-набор: `d322c210dec56ad7ff668f1ae9aec00057def279b78dda8c39984200fed63a1d` — хеш канонического списка путей/хешей в отчёте независимого автора, не Git commit.
- Эталон: Memcached1.6.45, хешированные исходный архив и Linux-бинарник.
- Сборка/тесты: Temurin OpenJDK25.0.4.1+1; Maven Wrapper, offline, прежние POM и thresholds сохранены.

| Проверка | Результат и границы |
|---|---|
| Независимый полный `clean verify -Pcoverage` | PASS: 3535 unit +28 IT =3563, ноль failures/errors/skips |
| Coverage | 3774/3896 ветвей =96,87% в целом; transport96,13%, protocol97,47%, engine96,62%; все per-class/package gates пройдены |
| Пinned classic differential | PASS:112 случаев на сервер,224 наблюдения |
| Пinned expanded differential | PASS:55 случаев на сервер,110 наблюдений |
| Настоящие клиентские библиотеки | 6 PASS: pymemcache4.0.0 classic/TLS; python-binary-memcached0.31.4 binary/SASL/TLS/SASL+TLS |
| Защита TLS в library-пилотах | Проверены цепочка и hostname; test-only CA, без глобального отключения проверки |
| Координаторский повтор | Полный3563 PASS, expanded110 PASS, те же6 library-пилотов PASS; побайтово тот же JAR |
| Независимое ревью | Финальное `ctx_efc3eae0dbc4`: нет блокирующих замечаний к проверенным пяти meta-коррекциям/текущему ограниченному профилю; прежние TLS lifetime findings исправлены и перепроверены |

Авторские доказательства — `tests/reports/protocol-expansion-20261025/replace-review-results.md` и `replace-final-evidence.json`. Повтор координатора:

- `targeted-20261025T091848Z-155bd190`: полный verify после механической коррекции LF-классификации в манифесте;
- `expanded-differential-20261025T092203Z`:110 наблюдений, нет failures, JAR stable;
- `client-pilot-20261025T092230Z`:6 PASS, все дочерние процессы завершены/reaped.

Остановка тестовых Windows-процессов через terminate иногда даёт exit1; это доказательство завершения принадлежащего тесту процесса, **не** доказательство graceful application shutdown. Lifecycle и close_notify проверяются другими тестами. Ревьюеры сами не запускали тесты; они проверяли исходники, oracle, hashes и raw evidence. Исполнение автора и координатора учитывается отдельно.

## Исправления, найденные проверками

Помимо основного codec добавлены/исправлены: header-time native admission и откат, инвалидирование отклонённого SET до drain, защита новой записи другого клиента от позднего удаления, CAS результата именно выполненной мутации, mode-specific meta CAS/create/TTL, отдельные semantics md x/I, no-op debug и mg-only recache win. В TLS исправлены owner-loop delegated-task blocking, потеря progress при coalesced input, readiness после post-handshake task, удержание ресурсов до завершения задачи, гонка completion/stop, финальный ciphertext и close_notify. Тесты сначала фиксировали провал, затем проходили после исправления; неудачные прогоны сохранены.

До расширения создан исходный архив196файлов, затем snapshots перед волнами. Один промежуточный прогон с параллельной правкой координатора помечен FAIL/sourceStable=false, несмотря на зелёные assertions; он не входит в итоговую квалификацию. Thresholds, skips и исключения не менялись ради PASS.

## Не закрыто и не заявляется

- UDP и Unix-domain listener отсутствуют. Binary/meta/auth/TLS реализованы, но «любой проект без проверки» обещать нельзя.
- SASL поддерживает PLAIN, не весь набор Cyrus; mTLS и внешние identity stores отсутствуют.
- Admin watch/lru_crawler/cache_memlimit/shutdown и Couchbase/TAP не реализованы.
- Сохраняются отдельные classic-отличия: дедупликация повторных ключей, лимит command line, геометрия itemMax. Все возможные сочетания meta flags не перебраны исчерпывающе.
- Категорийно точное совпадение всех statistics-счётчиков, особенно md/CAS, не доказано. Пользовательские данные и ответы выбранного wire-профиля проверены отдельно от полного stats parity.
- Фиксированные control/TLS charges — логические пределы, не RSS-гарантия. Полный физический граф JDK TLS provider/session cache не измерен как универсальная гарантия для всех провайдеров. Невозвращающаяся delegated task может задержать окончательную остановку; память не объявляется освобождённой преждевременно.
- Java-клиент заказчика, его конкретное приложение, полный performance/72h/trusted-OS/реестровый комплект на новом кандидате не квалифицированы. Старые результаты кандидатов950A/2DB не переносятся автоматически.

Текущие реализации и локальная инженерная проверка существенно расширяют миграционный профиль, но общий проектный objective остаётся шире этого доказанного объёма. T-019 не закрывается как безусловная универсальная замена. Деплоя, коммитов, push, платных ресурсов и подачи заявки не было.
