# 07. SBOM, контрольные суммы и инвентаризация сторонних компонентов

Статус документа: черновик 0.1 от 16.09.2026. Автор — независимый
агент-документалист, не реализовывавший сервер.

Этот документ — сводка релизного комплекта по упаковке. Он **не** является
заявлением о выпуске, приёмке или готовности к реестру. Открытые пункты
(72-часовая проверка, расширенная матрица платформ, реестровые и юридические
определения) не заявляются пройденными.

## 0. Легенда статусов

- `[ИСТОЧНИК path]` — проверено по файлу репозитория.
- `[ДОКАЗАТЕЛЬСТВО path]` — проверено по сохранённому артефакту доказательств.
- `[РЕКОМЕНДАЦИЯ]` — процессная рекомендация, не гарантия кода.
- `[НЕ ПРОВЕРЕНО]` — утверждение, подтвердить которое в этом комплекте не удалось.

## 1. Состав поставки этого пункта

| Файл | Содержание |
|---|---|
| `tests/reports/release/packaging/sbom.cdx.json` | Машиночитаемая SBOM в формате **CycloneDX 1.5 JSON** |
| `tests/reports/release/packaging/checksums.sha256` | SHA-256 релизного JAR, `pom.xml` и пяти замороженных манифестов кандидата |
| `tests/reports/release/packaging/checksums-commands.txt` | Точные команды вычисления и повторной проверки |
| `tests/reports/release/packaging/NOTICE-third-party.md` | Инвентаризация сторонних компонентов и сборочного/тестового инструментария |
| `tests/reports/release/packaging/build-inventory.md` | Закреплённые инструментарии, команда сборки, воспроизводимость, находка P-1 |
| `tests/reports/release/packaging/raw/**` | Сырые выводы команд, на которые ссылаются документы |

Требования-адресаты: `docs/02` §9, `docs/07` §6.1/§6.5, `docs/09` §6
`[ИСТОЧНИК docs/02-general-technical-specification.md:189-200, docs/07-security-operations-release.md:85-96, docs/09-acceptance-and-registry-readiness.md:97-108]`.

`[ИСТОЧНИК docs/release/00-index.md]` Документ добавлен в оглавление комплекта
(строка `07-sbom-and-checksums.md`).

## 2. Артефакт и контрольные суммы

- Артефакт: `target/octomem-1.0.0.jar`, 202 263 байта, 151 запись,
  `Main-Class: ru.octomem.cli.Main`, байткод release 25
  `[ДОКАЗАТЕЛЬСТВО tests/reports/release/packaging/raw/manifest.txt, raw/pom-properties.txt, raw/jar-contents.txt]`.
- SHA-256:
  `D409079AEB7D1F9476B6D316D58D469F45C835EE9B23105AF70CAF13B0A9086A`
  `[ДОКАЗАТЕЛЬСТВО tests/reports/release/packaging/checksums.sha256]`.
- Совпадает с замороженным `tests/reports/release/candidate/windows-jar.sha256`
  `[ДОКАЗАТЕЛЬСТВО tests/reports/release/packaging/raw/candidate-manifest-verification.txt]`.
- Хеш проверен до и после подготовки комплекта и не изменился
  `[ДОКАЗАТЕЛЬСТВО tests/reports/release/packaging/raw/jar-sha256-before.txt, raw/jar-sha256-after.txt]`.

`checksums.sha256` содержит SHA-256:
JAR, `pom.xml`, и пяти манифестов `tests/reports/release/candidate/*.sha256`
(`pom.sha256`, `src-main.sha256`, `src-test.sha256`, `src-test-resources.sha256`,
`windows-jar.sha256`) `[ИСТОЧНИК tests/reports/release/packaging/checksums.sha256]`.
Все пять манифестов повторно сверены с рабочим деревом: `pom` 1/1,
`src-main` 76/76, `src-test` 61/61, `src-test-resources` 4/4, `windows-jar` 1/1
`[ДОКАЗАТЕЛЬСТВО tests/reports/release/packaging/raw/candidate-manifest-verification.txt]`.

**Валютная поправка 23.09.2026 (согласованный проход `task_3c5fc136cb79`).** Приведённые выше
значения `202 263`/`D409079A…` (§2) и счёт `src-test 61/61` — это снимок проверки от 16.09.2026.
Текущий кандидат на диске: `target/octomem-1.0.0.jar`, 203 423 байта, 151 запись, SHA-256
`950A8EDB702E51C9979320C117257426f1109076845e80f30774ef0855ff682b`; после согласованной перезаморозки
пять манифестов `tests/reports/release/candidate/*.sha256` сверяются с деревом как `pom` 1/1,
`src-main` 76/76, `src-test` 71/71, `src-test-resources` 4/4, `windows-jar` 1/1 (0 несовпадений), а
`tests/reports/release/packaging/checksums.sha256` пересоздан (`F52AA6AA…`). Описательный пакетный
комплект (`sbom.cdx.json`, `NOTICE-third-party.md`, `build-inventory.md`, `raw/jar-sha256-*.txt`)
по-прежнему привязан к снимку `D409079A…`; его пересборка под `950A8EDB…` — **NOT_RUN**
(`tests/reports/release/platform/PACKAGING-AND-PLATFORM-RECONCILIATION.md` §7 P-1/P-2, O-5). Ни один
статус, порог, критерий или вердикт этой поправкой не меняется.

## 3. SBOM

- Формат: **CycloneDX 1.5 JSON**. Выбор обоснован в
  `tests/reports/release/packaging/README.md`: CycloneDX напрямую моделирует
  компонент-приложение с хешами и purl, позволяет описать обязательную среду
  исполнения как компонент типа `platform` (не подразумевая её включение в JAR)
  и выражает «ноль зависимостей» пустым `dependsOn`; SPDX 2.3 был бы более
  многословным для одного неизменённого JAR.
- Содержимое: единственный собственный компонент
  `pkg:maven/ru.octomem/octomem@1.0.0` с SHA-256, обязательная среда исполнения
  OpenJDK 25 (Eclipse Temurin 25.0.4.1+1) как `platform` с признаком
  `octomem:bundled=false`, и свойство
  `octomem:third-party-runtime-dependencies = 0`
  `[ДОКАЗАТЕЛЬСТВО tests/reports/release/packaging/sbom.cdx.json]`.
- Достоверность схемы: документ проверен по официальной схеме CycloneDX 1.5
  (`bom-1.5.schema.json` плюс `spdx.schema.json`, `jsf-0.82.schema.json`) через
  `ajv-cli` (draft-07) — результат `valid`, код возврата 0
  `[ДОКАЗАТЕЛЬСТВО tests/reports/release/packaging/raw/sbom-schema-validation.txt]`.
- Лицензия собственного кода в SBOM помечена как **предварительная**
  (Apache-2.0, не подтверждённая): свойство
  `octomem:license-status = preliminary-not-confirmed`
  `[ИСТОЧНИК docs/07-security-operations-release.md:111-115, docs/09-acceptance-and-registry-readiness.md:66]`.
  Идентификатор SPDX намеренно не заявляется.

## 4. Ноль сторонних runtime-зависимостей (как проверено)

| Проверка | Команда/источник | Результат |
|---|---|---|
| Содержимое JAR | `jar tf target/octomem-1.0.0.jar` | 151 запись; только `ru/octomem/**` и `META-INF/**`; нет вложенных библиотек, классов сторонних пакетов, `.so/.dll/.dylib` |
| Дерево runtime | `mvnw.cmd -o -B -ntp dependency:tree -Dscope=runtime` | только `ru.octomem:octomem:jar:1.0.0`, без дочерних узлов |
| Правило областей POM | `pom.xml`, `maven-enforcer-plugin` `bannedDependencies` | запрещены области `compile/runtime/provided/system`; объявлены только две зависимости области `test` |
| Манифест | `META-INF/MANIFEST.MF` | нет `Class-Path` на внешние архивы |

`[ДОКАЗАТЕЛЬСТВО tests/reports/release/packaging/raw/jar-contents.txt, raw/runtime-dependency-tree.txt, raw/runtime-dependency-tree.json; ИСТОЧНИК pom.xml:47-107]`.

## 5. Сторонние компоненты и инструментарий

- Поставляемые сторонние runtime-компоненты: **отсутствуют**
  `[ДОКАЗАТЕЛЬСТВО tests/reports/release/packaging/NOTICE-third-party.md]`.
  Поэтому отдельный `NOTICE` сторонних компонентов не требуется; ранее
  зафиксированное отсутствие `NOTICE` объясняется именно этим.
- Обязательная среда исполнения (не входит в JAR): Eclipse Adoptium
  **Temurin 25.0.4.1+1** (OpenJDK 25, Hotspot), лицензия GPLv2 with Classpath
  Exception `[ДОКАЗАТЕЛЬСТВО compatibility-manifest.json; .tools/jdk/jdk-25.0.4.1+1/release]`.
- Только сборочный и тестовый инструментарий (в артефакт не входит):
  Maven Wrapper 3.9.16, JaCoCo 0.8.15, JUnit Jupiter 5.14.4 (с транзитивными
  junit-platform 1.14.4, opentest4j 1.3.0, apiguardian-api 1.1.2), Gson 2.13.2
  (с error_prone_annotations 2.41.0), плагины Maven с закреплёнными версиями
  `[ДОКАЗАТЕЛЬСТВО tests/reports/release/packaging/NOTICE-third-party.md, raw/test-dependency-tree.txt; ИСТОЧНИК pom.xml]`.

## 6. Сборочный инвентарь и воспроизводимость

- Windows: Eclipse Temurin 25.0.4.1+1, архив
  `OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip` SHA-256
  `00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283`
  `[ДОКАЗАТЕЛЬСТВО compatibility-manifest.json]`.
- Linux: Eclipse Temurin 25.0.4.1+1, архив
  `OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz` SHA-256
  `dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e`
  `[ДОКАЗАТЕЛЬСТВО tests/reports/release/linux/toolchain-parity.md]`.
  `[НЕ ПРОВЕРЕНО]` Linux-архив отсутствует в `.tools/archives/`, его хеш здесь
  не пересчитывался.
- Команда сборки: `.\mvnw.cmd -B -ntp clean verify -Pcoverage` (POSIX:
  `./mvnw -B -ntp clean verify -Pcoverage`); артефакт-только:
  `clean package -DskipTests` `[ИСТОЧНИК tests/reports/release/packaging/build-inventory.md]`.
- Воспроизводимость: Linux-сборка на Temurin 25.0.4.1+1 воспроизводит хеш
  Windows-артефакта **побайтово после нормализации прав файлов**
  `[ДОКАЗАТЕЛЬСТВО tests/reports/release/linux/toolchain-parity.md]`.

**Находка P-1 (открытая, процессная).** Побитовая воспроизводимость между
платформами условна: режим прав файлов сборочного дерева влияет на один байт в
центральной директории архива (запись 149, `META-INF/maven/ru.octomem/octomem/pom.xml`;
0755 вместо 0644). Это не дефект продукта: ни один байт классов, манифеста или
ресурсов не отличается `[ДОКАЗАТЕЛЬСТВО tests/reports/release/linux/toolchain-parity.md]`.
`[РЕКОМЕНДАЦИЯ]` Собирать из дерева с нормализованными правами (как делает
`tests/platform/linux/build.sh`) либо фиксировать набор прав рядом с публикуемым
хешем.

## 7. Что не покрыто

- 72-часовая проверка (Т-05/M6) — **не выполнялась** здесь.
- Расширенная матрица платформ (TEST-08) — **не выполнялась** здесь.
- Калибровка производительности/RSS относительно эталона — **не установлена**
  здесь; числа производительности не приводятся.
- Реестровые и правовые определения (Р-01…Р-17) — **не подтверждены**.
- Лицензия собственного кода — **предварительная**, не подтверждена.
- Заявление о выпуске, приёмке или готовности к реестру **не делается**.
