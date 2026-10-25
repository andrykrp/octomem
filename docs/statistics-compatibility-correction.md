# Коррекция счётчиков classic/binary/meta

## Проверенный результат

На новом кандидате `D18CAC559E072D663B3BF7B39A2A99D7ADD54C213D49A294E33D264B78CD5BE7` независимый полный `clean verify -Pcoverage` выполнил **3763теста:0failures/0errors/0skips**, все прежние gates PASS (3990/4118ветвей,96,89%). Добавлены141проверка:137TCP counter/phase/class/reset/retry и4reservation/exception-lifecycle. Production-набор `add30ac8d156bf87b933a694a8d157d0215a6b8ffee86534f672344de748fb3b`; POM и существующие тесты не ослаблялись.

Свежий отдельный Memcached1.6.45 probe:118сценариев на каждом сервере, все17выбранных полей присутствуют, расхождений в counter vectors нет. Это counter-only доказательство, не подмена wire-проверки: отдельно повторены classic224 и expanded110наблюдений, Java32проверки и6Python-client режимов, включая TLS/SASL, на **том же новом JAR**. Все запущенные процессы завершены. Локальная evidence-матрица: `tests/reports/qualification-followup-20261026/stats/final-evidence.json`, полный запуск `targeted-20261025T204158Z`.

Исторические31ошибка первой матрицы и14ошибок дополнительных CAS/exception-контролей сохранены; после исправлений прошли. Ошибки тестового fixture с недостаточным budget для stats-snapshot сохранены отдельно; увеличение fixture до1MiB сохранило требование настоящего вытеснения и точные counter assertions. Реализация не объявляется полностью статистически эквивалентной по всем admin/allocator метрикам, режимам отказа и сочетаниям команд. Финальное независимое ревью `ctx_2d7d7ea14f17` принято без блокирующих замечаний в проверенном объёме. Координатор отдельно повторил полный3763/allcoveragePASS на стабильных исходниках (`targeted-20261025T204957Z`); механическое обновление этого статуса не меняет код.

## Основание и уточнённые требования

26.10.2026. Требование уточняется до правки реализации; эталон Memcached1.6.45, не произвольное представление о статистике. Независимый frozen oracle106сценариев выявил31расхождение; ответы/данные и выбранные17счётчиков сравниваются отдельно. Дополнительные11phase-controls зафиксировали момент инкремента.

- `cmd_set` учитывает попытку после успешного выделения входной записи, включая add/replace/CAS conditional refusal. Classic/meta — при header admission; binary — после полного тела. Счётчик класса относится к исходной выделенной записи, не к результирующему append. Oversize/allocation refusal до admission не увеличивает cmd_set; native retry не должен увеличивать его повторно.
- CAS-метрики относятся к storage-CAS, а не к любому сравнению токена. Binary DELETE CAS mismatch не увеличивает cas_badval/delete_misses; meta md mismatch увеличивает delete_misses, не cas_badval. Отсутствующий ключ delete — delete_misses, arithmetic — incr/decr_misses, не cas_misses.
- Создание отсутствующего счётчика через binary initial или meta N/J не считается arithmetic hit. Успешное изменение существующего счётчика считается hit.
- Meta md I (с x или без него) не считается delete hit; md x без I сохраняет delete hit.
- Промах meta mg с T считается cmd_get/get_misses; binary TOUCH/GAT промах — прежние touch-метрики. Остальные успешные и отрицательные контроли сохраняются.
- Дополнительный pinned oracle12сценариев: APPEND/PREPEND с compare-CAS не увеличивает cas_hits/cas_badval/cas_misses даже при равном/неверном токене или отсутствующем ключе; cmd_set учитывается по фазе. CAS_HITS обычного conditional store относится к прежнему классу записи, а CMD_SET — к новому входному классу. Исключение статистического приёмника при header admission должно отменить native/service reservation до выхода с исключением, сохраняя исходную ошибку; повторного присвоения занятого reservation быть не должно.

Оракул: локальные `tests/reports/qualification-followup-20261026/stats/probe-20261025T202005Z/` и `phase-oracle-20261025T202409Z/`. Это bounded профиль, не обещание полного allocator/admin статистического равенства. Публикуется независимый TCP regression `StatisticsParityTest`, phase-controls, затем изменённый кандидат проходит полный verify/coverage, fresh review и повтор затронутых протокольных/клиентских проверок. Старый JARfb561 не считается доказательством новых счётчиков.
