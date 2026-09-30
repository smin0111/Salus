# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project structure

Polyrepo-style monorepo, three independently built subprojects — no root package.json/Makefile ties them together:
- `backend/` — Spring Boot 3.2.1 API (Maven, Java 17)
- `frontend/` — Expo / React Native user app
- `admin/` — Vite / React admin web

## Backend build/test/run

- No `mvnw` wrapper exists — use plain `mvn`.
- Build + test: `cd backend && mvn -q clean test`
- Run locally: `set -a; source .env; set +a; mvn spring-boot:run` (env vars, incl. `JWT_SECRET`, are required — see `.env.example` / `application-secret.example.properties`)
- **Java version is hard-enforced**: Maven Enforcer fails the build outside JDK `[17,18)`. An off-range JDK breaks Lombok/Mockito annotation processing with confusing "cannot find symbol" errors — if you see those, check `java -version` first.
- Persistence is **JPA/Hibernate only** — MyBatis is explicitly not used despite some naming that might suggest otherwise. `ddl-auto=validate`, `spring.sql.init.mode=never`: schema is never auto-generated.
- Schema source of truth is Flyway migrations under `backend/src/main/resources/db/migration`. Root-level `schema.sql`, `init_db.sql`, `test_data.sql` are **not used/tracked** — ignore them.
- Redis is used directly via `StringRedisTemplate` (`spring.data.redis.repositories.enabled=false`) for short-lived AI work sessions, not Spring Data Redis repositories.

## Frontend / admin

- Frontend: `npm ci && cp src/secrets.example.js src/secrets.js && npx expo export --platform web --output-dir dist`
- Admin: `npm ci && npm run build`
- Neither has a lint or format script configured — **no linter/formatter exists project-wide** (no ESLint/Prettier/Checkstyle/Spotless/.editorconfig). Style enforcement is informal/reviewer-driven; don't assume a formatter will catch style issues.

## Backend package layout

Under `backend/src/main/java/com/salus/healthytable/`: `config`, `controller`, `domain`, `dto`, `exception`, `repository`, `security`, `service`, `util`. The `service` package is intentionally flat (not per-feature) except for `service/allergen/` and `service/recipeagent/` subpackages.

## LLM / Ollama integration gotchas

- Ollama and Gemini are called manually over `WebClient` — there is no LLM SDK dependency.
- **qwen3 thinking-mode trap**: qwen3 models emit a hidden reasoning block that consumes `num_predict` *before* the real answer, and can exhaust the whole budget, returning an empty reply. The fix lives in one place — `OllamaLlmService.thinkingSettingFor(model)` — which returns `false` for any model name starting with `"qwen3"` (case-insensitive). Both the chat path and `OllamaRecipeGenerationClient` must delegate to this single method; don't reintroduce a second decision point, that's exactly how this broke in prod before (see commit `be415ea`).
- `TavilySearchEngine` prefers Tavily's extracted "content" field over raw page content (avoids menu/ad/comment noise in the LLM prompt); only falls back to raw content when Tavily's content is empty.

## Allergen matching

`service/allergen/AllergenMatcher` + `AllergenDictionary` are the single entry point for allergen conflict judgment — chat and Recipe Agent previously had separate matching logic that could disagree on the same user/dish; don't reintroduce a parallel path.
- Single-character allergen terms (e.g. "밀", "게") must match on exact full-token equality, never substring — substring matching produces false positives (e.g. "밀" inside "밀크"). Actual detection of derived terms (밀가루, 게살, etc.) goes through the dictionary's term list instead.
- `matchesLiterally()` distinguishes "the allergen name itself appears" vs. "only matched via a derived ingredient" — needed because stripping the literal allergen word from output doesn't remove allergen-derived ingredients like 버터/치즈 for a 우유 allergy.

## Approved recipe catalog

Recipe validation (`validator_version=v2.0`) enforces business rules worth knowing before touching recipe generation/validation code: no ingredient reduced to 0g may still be referenced in steps; no meat-style safety phrasing on tofu/vegetable dishes; sequential step-time sums can't exceed the displayed total time by more than 10 minutes; cooking temperatures are only allowed on oven/air-fryer steps. Menu names not present in the approved recipe DB are never guessed by the LLM without reliable search evidence backing them.

## Git / PR conventions

- Commit messages: Korean text with a Conventional-Commits-style English prefix — `fix:`, `feat:`, `chore:`, `refactor:`, `test:`, `build:`.
- Feature branches merge into `main` via regular GitHub merge commits (not squashed) — history is preserved per-commit.
- Before making changes, check the current git status and preserve unrelated uncommitted work.

## CI (`.github/workflows/ci.yml`)

Jobs: `backend-test` (`mvn test`), `admin-build` (npm audit + build), `frontend-web-export` (custom audit-policy script + expo export), `frontend-android-gradle-check`, `docker-build` (asserts the backend container runs as uid `10001` and the admin container runs as a non-root user).
