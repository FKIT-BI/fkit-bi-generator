# Epic 6 Scenario API implementation note

Implemented the six Scenario operations from `docs/contracts/generator-api-draft.yaml` under `/api/v1/scenarios`: paged list, create as `DISABLED`, side-effect-free validation, detail, complete definition replacement, and status replacement.

Scenario definitions are stored as JSONB in the Analytics-owned `generator.generation_scenario` table; selected machine ownership links are replaced transactionally in `generator.generation_scenario_machine`. Generator performs no schema migration. Validation reads Enterprise, Machine, ProductionLine, and TelemetrySignal from the shared Analytics schema. Drafts can persist with readiness/model issues and remain disabled; activation rejects errors. Updating an active Scenario rejects a definition that no longer validates.

Behavior validation supports the contract model catalog (`CONSTANT`, `UNIFORM`, `NORMAL`, `RAMP`, `SINE`, `COUNTER_RATE`, `PULSE`, `BERNOULLI`, `SEQUENCE`), checks common numeric/interval bounds, signal value type/kind compatibility, phase ordering metadata, duplicate targets/behaviors/overrides, ownership and readiness, and ensures every phase resolves at least one behavior. `expectedState` remains diagnostic input only.

Verification was limited to source inspection. `./mvnw -q -DskipTests compile` could not run because Java is unavailable in the environment (`java: command not found`; `JAVA_HOME` is invalid). Epic-wide tests were not run as directed.
