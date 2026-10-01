---
name: salus-backend-change
description: Use for Salus backend data or API work - JPA entities, Spring Data repositories, Flyway migrations, Redis work sessions, controllers and DTO contracts, transactions, choosing and running backend tests (엔티티, 리포지토리, 마이그레이션, API, 트랜잭션, 테스트 실행).
---

# Salus backend change (data, API, tests)

## Read first
- `docs/ai/PROJECT_CONTEXT.md` sections "Data layer", "Commands", "Test map".
- `docs/ai/DEVELOPMENT_RULES.md` section 4 when deciding what to run.

## Rules
- JPA/Hibernate only; no MyBatis. `ddl-auto=validate`: entity mappings and migrations must match exactly.
- Schema change = a new `V<next>__<description>.sql` in `backend/src/main/resources/db/migration`, only when the task needs it. Check the highest version with `ls` (uncommitted migrations count). Never edit an existing migration or legacy `backend/migrations/*.sql`.
- Redis only for short-lived work sessions via `StringRedisTemplate` (`RecipeWorkSessionService`); no Redis repositories, no durable data in Redis.
- `spring.jpa.open-in-view=false`: load what the response needs inside the `@Transactional` service method.
- API paths, DTO fields and status codes are used by `frontend/` and `admin/`. Change them only on explicit request and list affected clients (`rg -n "<path or field>" frontend/src admin/src`).
- Resolve the current user through `AuthenticatedUserProvider`; never trust a user id from a request body.
- Java 17 only (e.g. Homebrew: `JAVA_HOME=/opt/homebrew/opt/openjdk@17`).

## Verify
- Trace: `domain/X` -> `repository/XRepository` -> `service/XService` -> `controller/XController`; tests `service/XServiceTest`, `controller/XControllerTest`, `controller/*SecurityTest`.
- `cd backend && mvn -Dtest='XServiceTest,XControllerTest' test`; run `mvn -q clean test` if a migration, entity mapping, security config or `pom.xml` changed.
- Unit tests start no MySQL/Redis and do not execute migrations: say so when a migration is unverified against a real database.
