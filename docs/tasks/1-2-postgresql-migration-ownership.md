# Story 1.2 — Generator

- Generator не запускает миграции и проверяет в readiness точное значение `equipment.schema_release` вместе с доступностью PostgreSQL.
- Compose ждёт healthy PostgreSQL и Analytics перед запуском Generator.
- Readiness-тесты используют PostgreSQL Testcontainers и покрывают совпадающий, отсутствующий и несовпадающий release, а также недоступность БД при остающемся UP liveness.
- Проверка: Java 21 `./mvnw -B verify` прошёл (15 тестов). Вывод сборки перенаправлялся во временный `/tmp`, так как существующая папка `target/` принадлежит root.
