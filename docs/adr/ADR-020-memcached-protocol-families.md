# ADR-020. Общий движок для classic, binary и meta

Дата: 25.10.2026. Статус: PROPOSED, реализация и независимая проверка в работе.

## Основание

Владелец прямо поручил максимальную совместимость с Memcached и продолжение реализации отсутствующих протоколов. Старое ограничение выпуска classic text больше не служит основанием откладывать binary/meta. Исходники Memcached разрешены только для сверки, не для включения в продукт. Эталон — 1.6.45, OpenJDK 25, ноль сторонних runtime-библиотек.

## Решение

1. Один TCP listener выбирает binary по первому байту 0x80, иначе ASCII; выбор действует до закрытия соединения. Classic и meta разделяют ASCII-соединение. Неполный заголовок не потребляется как следующая команда, unknown/malformed не превращается в успешный no-op.
2. Существующий NIO owner loop, admission до allocation, FIFO, fairness и backpressure сохраняются. Новые codecs не имеют собственных socket threads и не обходят глобальные бюджеты. Фиксированный binary заголовок 24 байта, extras до 20 и ключ до 250 входят в connection control charge; только value хранится в одном отдельно допущенном byte[] без второй копии. Большое отклонённое тело отбрасывается ограниченными порциями.
3. Новый протокольно-нейтральный atomic request/result API движка обслуживает binary/meta. Операции проверки CAS, создания счётчика, выдачи recache-W, изменения TTL/данных выполняются в одной segment transaction. Запрещены протокольные композиции get→set или get→delete, подверженные гонке. Успешный ответ несёт CAS именно выполненной мутации, не последующего get.
4. Старый classic API сохраняется. Общий индекс хранит произвольные байтовые ключи 1–250; проверка whitespace/control остаётся на classic boundary, binary и meta-b ключи не кодируются в поддельные ключи и не создают отдельное пространство имён.
5. Результат удерживает ItemLease и небольшие скалярные метаданные, никогда полную копию value. Encoder выдаёт заголовок/flags/key/value частями, закрывает результат ровно один раз на успехе, ошибке, отмене и disconnect. Ни network I/O, ни ожидание читателей не происходят под cache locks.
6. Meta fetched/stale/winner/binary-key metadata принадлежит записи и изменяется только под segment lock; data/flags/expiry/CAS опубликованной версии остаются неизменяемыми. TTL-only replacement сохраняет CAS; E явно выбирает CAS; recache ownership W/Z атомарно относительно всех клиентов. Дополнительные поля учитываются в существующей консервативной service charge и проверяются, а не объявляются бесплатными.
7. Binary quiet подавляет miss для getq/gatkq и успех мутаций, но не ошибки; meta q имеет собственные правила и не отождествляется с classic noreply. Opaque отражается побайтово. Quit имеет явное close-after-response, а не перехватывается как безответный classic quit.
8. Режимы authentication/TLS вводятся отдельно с fail-closed startup и синтетическими credentials в тестах. Нельзя объявлять SASL/TLS по наличию opcode или отключать защиту ради миграции. UDP не включается по умолчанию, сетевые политики production не меняются.

## Проверки

Уточнение correction wave1: `ConnectionFactory.additionalControlBytes()` для расширенного семейства резервирует ещё 32768 байт до создания handler; старые фабрики сохраняют 10816. Binary fixed header24/prefix270, result+scratch256, bounded token array32/String/byte[] и ответные prefixes помещаются в эту консервативную добавку; отдельная ASCII line растёт лениво до maxLine и обнуляется при release, учтена transport line charge `2L*maxLine+256`, не удерживается idle. Полное instrumented-layout доказательство остаётся обязательным. Входная storage native reservation получается после heap admission до чтения body; успех передаёт её в atomic engine operation, cancel/drain возвращает её ровно раз. SET окончательно инвалидируется только после bounded eviction failure на header admission, не после upload. GET/NOOP без body не занимают heap body pool. Метаданные newCAS/TTL/create/flags трактуются по операции, а не общей догадкой.

Независимый автор фиксирует oracle до production inspection: binary opcode/status/extras/body/CAS/opaque/quiet и все meta команды/flags выбранного эталона. Нужны split/coalesced frames, tiny output chunks, binary keys, same-key cross-protocol, concurrent CAS/counter-create/recache, expiry, oversized/OOM/drain, leases/cleanup, действующий classic suite, full verify/coverage и свежий review. Отдельно реальные клиенты; самописный wire test не называется библиотечным пилотом.

Никакой runtime/release PASS до доказательств. Предварительная декомпозиция не разрешает закончить objective после одного binary happy path.
