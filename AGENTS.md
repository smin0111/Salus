# Salus — agent instructions

Salus is a personal diet/health service: fridge ingredients, meal logs, health profile and allergies, and evidence-checked recipe generation with a local LLM. It is a portfolio/validation-stage project; never present output as medical advice.

Code is the source of truth. This file and `docs/ai/*` are maps verified against commit `7aaeefc` plus the 2026-09-17 working tree; items marked (WIP) were uncommitted at that time. If a doc and the code disagree, trust the code and fix the doc in the same change.

## Repository map
- `backend/` Spring Boot API (Maven). Java root `backend/src/main/java/com/salus/healthytable/`: `config controller domain dto exception repository security service util`. `service/` is flat except `service/allergen/` and `service/recipeagent/`.
- `backend/src/main/resources/`: `application.properties`, `db/migration/` (Flyway), `allergens/ko-allergens.yaml`.
- `backend/src/test/java/...` mirrors main packages. Tests are unit tests (JUnit 5 + Mockito); no MySQL/Redis needed.
- `frontend/` Expo / React Native app. `admin/` Vite / React admin web.
- `.github/workflows/ci.yml` CI; `docker-compose.yml` local Redis; `docker-compose.prod.yml` MySQL, Redis, backend, admin.
- Detailed map, flows, commands, test map: `docs/ai/PROJECT_CONTEXT.md`.

## Stack facts that change how you work
- Java 17 only: Maven Enforcer requires JDK `[17,18)` and Maven >= 3.9. No `mvnw`; use `mvn`. Lombok "cannot find symbol" errors usually mean the wrong JDK: check `java -version` first.
- Persistence is Spring Data JPA/Hibernate only (no MyBatis). Schema = Flyway migrations in `backend/src/main/resources/db/migration`; `ddl-auto=validate`, `spring.sql.init.mode=never`. Root `schema.sql`, `init_db.sql`, `test_data.sql` are not used.
- MySQL 8 is the database. Redis is used only via `StringRedisTemplate` for short-lived work sessions (`spring.data.redis.repositories.enabled=false`).
- LLM calls go over `WebClient` to Ollama (default `qwen3:8b`); there is no LLM SDK. Gemini, Tavily, DuckDuckGo, MFDS and Recipe Agent sources are optional and flag-controlled.
- No linter or formatter exists in any subproject; match the surrounding style.

## Non-negotiable rules
1. Protect uncommitted work. Run `git status --short` before editing; never overwrite or revert changes you did not make. Salus usually has WIP.
2. Git: commit, push, branch, or create/merge/close PRs only when the user explicitly asks for that action. Never run `git reset`, `git clean`, `git restore`, `git checkout -- <path>`, `git stash drop|clear`, or force push without an explicit request.
3. Scope: change only what the task needs. No incidental changes to production code, tests, API contracts, dependencies or migrations. Never edit code or tests just to make a test pass.
4. Schema: add a new Flyway migration only when the task requires it; never modify an existing `V*__*.sql`.
5. Never print or copy secrets (`.env`, `application-secret.properties`, `frontend/src/secrets.js`).
6. Safety (details and code anchors: `docs/ai/SAFETY_RULES.md`):
   - Unknown is not safe. Missing, unreadable, unparsed or failed-to-load data must never become "no allergen" or "safe".
   - Do not weaken, bypass or skip allergen checks or recipe validators to make something pass.
   - Treat LLM output as untrusted until parsed and validated; never fill gaps with confident guesses.
   - Registered allergies override in-message phrases such as "빼고" / "제외".
   - Any behavior change in allergen, health-context or validator code needs a targeted test and an explicit note in your report.

## Context efficiency (full rules: `docs/ai/DEVELOPMENT_RULES.md`)
- Explore in this order and stop once you have evidence: `git status`/`git diff` -> known symbol -> `rg`/`git grep` -> file outline -> targeted line range -> whole file -> directory -> repository.
- Do not scan the whole repo. Skip `node_modules/`, `backend/target/`, `frontend/ios/Pods/`, build outputs, `backend/tools/**/results/` and `poc/` unless the task is about them.
- Read the method or section you need; several `service/*` classes exceed 600 lines.
- Do not re-read unchanged files or repeat expensive commands; reuse what is already in context.
- Logs: search first (`rg -n "ERROR|Exception|FAILED|Caused by"`), then read surrounding lines.
- Return conclusions with `path:line` evidence, not raw tool output.
- Saving tokens never justifies guessing: if evidence is insufficient, widen the search.

## Tests and verification
- Narrowest first: one method -> test class -> related classes -> full suite. Backend: `cd backend && mvn -Dtest='ClassName#method' test`; full: `mvn -q clean test`.
- `frontend/` and `admin/` have no test or lint scripts; verify with their build/export commands (see PROJECT_CONTEXT).
- Report exactly what you ran and what you did not run.

## Task routing (load only what the task needs)
| Task | Skill (`.claude/skills` = `.agents/skills`) | Read on demand |
|---|---|---|
| Chat -> recipe generation, prompts, Ollama client, parsers, validators, repair, audit, eval harness | `salus-llm-recipe-pipeline` | PROJECT_CONTEXT "LLM recipe flow"; SAFETY_RULES "LLM output" |
| Allergens, health context, declaration parser, any safe/unsafe judgement | `salus-allergen-safety` | SAFETY_RULES |
| Entity, repository, Flyway, Redis, API contract, backend test strategy | `salus-backend-change` | PROJECT_CONTEXT "Data layer" |
| Frontend or admin only | none | PROJECT_CONTEXT "Clients"; do not explore backend |
| AI harness or agent benchmark | none | `docs/ai/README.md`, `docs/ai/AGENT_EVALUATION.md` |

## Conventions
- Commit messages (only when asked): Korean text with an English Conventional Commits prefix (`fix:`, `feat:`, `chore:`, `refactor:`, `test:`, `build:`). Branches merge into `main` with merge commits.
- Reply in the user's language (Korean by default for this repo). Keep final reports short: result, evidence, what was verified, open risks.
