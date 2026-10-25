# ADR-021. Защищённый режим Memcached

25.10.2026, PROPOSED. Дополнительное поручение максимальной совместимости требует не отключать защиту клиентов при миграции. Реализация только на стандартном JDK25, без внешних runtime-библиотек.

## Контракт

- По умолчанию остаётся loopback без authentication. Новые security-настройки явные, прежние defaults не изменяются.
- `auth.mode=none|sasl|text`; `auth.file` — локальный UTF-8 файл username:password, ограниченный по размеру, прочитанный до открытия listener. Credentials не принимаются в argv, не включаются в errors/stats/logs. Неверные/неполные параметры прерывают startup; пустой файл не означает отключение защиты.
- SASL: binary opcodes LIST_MECHS/AUTH/STEP, механизм PLAIN; до успеха cache операции не выполняются. Text auth: совместимый fake SET username/password до любых cache операций. Никаких общих сессий между соединениями. PLAIN/text допускаются на loopback либо поверх TLS; без TLS на внешнем bind startup fail-closed.
- `tls.keyStore`, `tls.keyStorePasswordFile`: PKCS12 и файл пароля загружаются перед bind. TLS1.2/1.3 через стандартный SSLContext/SSLEngine на существующем NIO owner loop; это не отдельный blocking proxy/порт и не thread-per-client. Wrong TLS/cert/password не приводит к plaintext fallback.
- Per-connection TLS packet/application буферы имеют фиксированный проверенный верхний предел и допускаются до allocation отдельной control charge. Handshake/progress ограничены за проход; канал закрывается при переполнении, renegotiation не даёт неограниченных allocations. Cleanup освобождает handler/native leases независимо от handshake состояния.
- Тесты: синтетические credentials и self-signed сертификат только в закрытом test-temp, never production secrets. Unauthenticated denied/isolated, invalid credentials cannot mutate, reconnect resets auth, enabledTLS rejects plaintext, real TLS client succeeds, handshake failure cleanup, independent review.

Unix-domain и UDP оцениваются отдельными транспортами. Их отсутствие не скрывается под словами «полная совместимость»; UDP не включается автоматически и не используется для внешних тестов.
