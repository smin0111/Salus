# Salus — agent instructions

Salus is a personal diet/health service: meal logs, health profile and allergies, and evidence-checked recipe generation with a local LLM. It is a portfolio/validation-stage project; never present output as medical advice.

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

## 개발 자동화 시작·중지 규칙
- 사용자가 "자동화프로젝트 시작해"라고 하면 마지막 승인 작업의 현재 코드·로컬 인계 기록·리뷰 상태를 확인하고, 그 범위에서 구현 → 검증 → 독립 Codex 검토 → 수정·재검토를 이어갑니다. 일반적인 다음 단계마다 허락을 다시 묻지 않습니다. 재개가 새 기능·새 설계·배포 권한을 뜻하지는 않습니다.
- 커밋·푸시·PR은 사용자가 해당 행위를 명시적으로 지시할 때만 합니다. 개발 완료 보고를 커밋·푸시·PR 권유나 승인 질문으로 끝내지 않습니다. 병합·배포·기능 활성화·접근 모드 변경도 기존 권한 경계를 유지합니다.
- "멈춰", "중지"는 구현과 예약된 재시도를 함께 멈추라는 뜻입니다. 진행 중인 호출의 안전한 종료 상태를 확인하고, 코드 변경·실제 검증·미완료 항목·job ID·다음 명령을 `.codex-review/handoff.md`에 남깁니다. 파일에 비밀값은 쓰지 않습니다. 프로세스를 끝내지 못했으면 끝났다고 보고하지 않습니다.
- 시작 명령은 실제로 수신한 대화에서만 처리할 수 있습니다. 현재 연결은 기존 Claude Code 대화가 로컬 Codex 검토기를 호출하는 구조이며, 다른 데스크톱 앱의 대화를 자동으로 깨우거나 한도를 해제하지 않습니다. 두 구현자가 동시에 같은 파일을 수정하지 않습니다.
