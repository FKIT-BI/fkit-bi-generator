# FKIT BI Generator

Java 21/Spring Boot service for generation scenario/run control. It defines `TelemetrySink` and provides a JDBC adapter boundary; analytics owns migrations.

The draft public contract is `docs/contracts/generator-api-draft.yaml`. It controls Scenario and GenerationRun only; equipment CRUD belongs to Analytics API and raw measurements use the internal JDBC sink.

Run `./mvnw test` and `docker build -t fkit-bi-generator .`.
