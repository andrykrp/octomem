# T-019. Binary, meta и безопасная миграция Memcached

Статус: IN_PROGRESS, 25.10.2026. Поручение владельца расширяет прежний classic-only профиль; цель не ограничивается T-018.

Дополнение final local: Unix-domain и ограниченный loopback UDP реализованы и независимо проверены; scoped source reviews без блокирующих findings, JARfb561 прошёл3619/allcoverage +LinuxUnixUDP +334diff +6clientmodes. Последний полный повтор3622послеmanifest-тестов FAIL на Windows10kconnect;3manifestITPASS, Java/JAR unchanged. Поэтому карточка остаётся IN_PROGRESS до повторной квалификации стенда и остальных явно указанных broad release/application/statistics доказательств; отсутствие binary/meta/SASL/TLS/Unix/UDP больше не является причиной незавершённости реализации.

Реализованы binary/meta, SASL PLAIN/text auth/TLS; независимые3563теста/coverage,334 differential observations и6 реальных библиотечных режимов прошли, финальное scoped review без блокирующих findings. [Итог и ограничения](../docs/protocol-expansion-results-2026-10-25.md). Карточка остаётся IN_PROGRESS: UDP/Unix, полная statistics parity, Java/application pilot и расширенная реестровая квалификация не закрыты; критерии не отменены.

## Обязательный результат

- Binary core: GET/GETK и quiet, SET/ADD/REPLACE и quiet, DELETE/CAS, APPEND/PREPEND/CAS и quiet, INCR/DECR с initial/exptime/CAS и quiet, FLUSH, NOOP, VERSION, STAT, TOUCH, GAT/GATK и quiet, QUIT/QUITQ. Header/extras/key/body/status/opaque/CAS — по 1.6.45. Предложенный в старой wiki opcode VERBOSITY 0x1b не реализован core эталона: возвращать unknown-command, не выдумывать поддержку. Чужие расширения Couchbase/TAP не изображать работающими.
- Meta: mg/ms/md/ma/me/mn и применимые b/c/C/E/F/I/k/O/q/s/t/u/v/h/l/N/R/T/M/J/D/P/L; atomic vivify, CAS override/compare, stale/tombstone и recache win, quiet/errors, flags order и отражение opaque. Classic/meta pipeline на одном соединении.
- Разделяемые данные и бинарные ключи, корректные конкурентные операции, ограниченные ресурсы и lifetime по ADR-020.
- Явная матрица authentication/TLS/Unix/UDP. Реализовать безопасные необходимые режимы либо зафиксировать конкретный технический/авторизационный блокер, но не ссылаться только на прежнее исключение из1.0. Никакой отключённой защиты в засчитанном пилоте.
- Проверки реальными совместимыми клиентами без изменения прикладной логики; synthetic wire и реальные библиотеки учитывать отдельно.

## Приёмка

Независимое авторство обязательных тестов, pinned reference transcripts, before-source archive, полный verify/coverage без ослабления, независимое ревью и повтор substantive fixes. Raw ошибки сохраняются; один кандидат на финальную проверку. Новые features не отменяют classic regressions и safety. Реестр/доставка/production/коммиты не входят в разрешение.

Координатор владеет архитектурой и production; независимый тестировщик — новыми Expanded* тестами и `tests/reports/protocol-expansion-20261025/`; reviewers read-only. Детальные scope выдаются Orca, не новым локальным планировщиком.
