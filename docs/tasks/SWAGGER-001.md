# SWAGGER-001 — публичный Swagger Generator

## Объём

Нормативный `docs/contracts/generator-api-draft.yaml` включён в jar Generator и используется Swagger UI как статический контракт. Business endpoints защищены общим Bearer JWT.

## Публичные URL

- Service domain UI: `https://generator.bi.kitexp.ru/swagger-ui.html`
- Service domain contract: `https://generator.bi.kitexp.ru/openapi/generator-api-draft.yaml`
- Path-based UI: `https://bi.kitexp.ru/api/generator/swagger-ui.html`
- Path-based contract: `https://bi.kitexp.ru/api/generator/openapi/generator-api-draft.yaml`
- Сгенерированный OpenAPI JSON `/v3/api-docs` также доступен публично; используется тот же service domain или префикс `/api/generator/`.

OpenAPI server задан относительно URL контракта, поэтому `Try it out` остаётся на service domain или под path-prefix UI. Создание и проверка run содержат примеры realtime, historical и scheduled.

## Проверка

- Полный `./mvnw verify`: прошёл (43 теста).
- Текущие `GeneratorApplicationTest` и `GeneratorOpenApiContractTest`: 21/21 — прошли проверки анонимных UI/config/raw YAML/JSON, forwarded prefix, Bearer-запроса и 401 для анонимного business API.
- `GeneratorOpenApiContractTest` проверяет операции, описания, примеры JSON body и byte-for-byte упаковку YAML.
- `docker compose config -q` с временными фиктивными значениями обязательных переменных: прошёл.
- `git diff --check`: прошёл.
