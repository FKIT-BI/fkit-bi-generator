# Epic 6 — contract consistency and acceptance

Acceptance slice Generator работает на PostgreSQL 16/Testcontainers. Fixture применяет актуальный Analytics Liquibase master к изолированной тестовой базе, затем поднимает реальный Generator MVC/JDBC context.

Покрыто настоящими API/DB сценариями:

- неполное поведение сохраняется как `DISABLED`, activation возвращает структурированный `422`, исходное состояние в БД не меняется;
- повторный `Idempotency-Key` с тем же canonical request возвращает прежний Run, изменённое тело получает `409 IDEMPOTENCY_KEY_REUSED` без дополнительной записи;
- конкурентное резервирование двух пересекающихся многомашинных Runs даёт ровно один успех и не оставляет частичных reservation rows;
- scheduled запуск с задержкой больше 30 секунд становится `FAILED/SCHEDULE_MISSED` без ingest batch или raw rows;
- lease после остановки worker можно забрать повторно: новый worker становится владельцем, а durable attempt не сбрасывается;
- crash после commit historical batch, но до checkpoint ACK: replacement worker повторяет тот же stable batch ID, raw остаётся уникальным, шестиминутный диапазон фиксирует оба chunk coverage;
- pending realtime batch публикуется до завершения Run, если bounded end истёк во время остановки;
- фазовый override остаётся только у Machine, которой принадлежит Signal;
- невалидные counter/pulse параметры и duration за пределом DB timestamp возвращают structured validation;
- protected API проверяется на отсутствие/невалидность JWT, trace ID и общий error envelope.

Добавлен OpenAPI guard для неизменного набора 12 operation IDs, JWT bearer/security errors на каждой операции, общих ошибок и согласованного лимита Scenario description 2000 символов. Лимит приведён к одному значению в OpenAPI, Generator validator и свежем Analytics release 013 DDL.

Graceful stop во время активной batch-транзакции и actual Analytics HTTP read endpoint не входят в эту acceptance fixture. Realtime no-catch-up проверен через delayed attempt anchor и реальный `TelemetrySink`; raw→Analytics путь подтверждён вызовом current projection на записанных Generator измерениях и чтением `signal_current_value`.

Проверки 2026-10-11: `mvn -B clean test` в Generator — 41/41; `mvn -B clean test` в Analytics — 76/76; дополнительный realtime bounded-end/pending-batch acceptance прошёл отдельно перед полным Generator прогоном.

## Изменённые/добавленные файлы

- `src/test/java/ru/fkit/bi/generator/epic6/GeneratorEpic6ApiAcceptanceTest.java`
- `src/test/java/ru/fkit/bi/generator/epic6/GeneratorOpenApiContractTest.java`
- `pom.xml` — test-scope Liquibase для применения canonical Analytics changelog к PostgreSQL fixture
- `docs/contracts/generator-api-draft.yaml` — 401/403 response mapping для всех 12 операций
- `src/main/java/ru/fkit/bi/generator/scenario/ScenarioValidator.java` — предел description 2000
- `../fkit-bi-analytics/src/main/resources/db/changelog/releases/013-generator-run-control/001-generator-run-control.sql` — соответствующий лимит колонки

Заметки ранних реализационных срезов ниже не отражают итоговый прогон; актуальные результаты приведены выше.
