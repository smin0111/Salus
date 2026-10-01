# Development rules for coding agents (Claude Code and Codex)

Shared by both agents. `AGENTS.md` holds the short version; this file holds the detail. Read only the section you need.

## 1. Context efficiency
1. Read only files required for the current task.
2. Before reading a full file, prefer symbol search, `rg`, `git grep`, `git diff` and targeted reads.
3. Do not recursively scan the whole repository unless explicitly necessary.
4. Do not reread unchanged files unless required.
5. Prefer reading specific methods/classes instead of complete files when possible.
6. Do not paste large command output back into context when a summary is sufficient.
7. For logs, inspect the error, the surrounding lines and the relevant stack trace before reading the complete log.
8. Prefer targeted tests before running the entire test suite.
9. Do not run the same expensive test repeatedly without a reason (a code change or a flaky-test hypothesis).
10. Reuse information already present in the current context.
11. If the repository state changed, use `git status` / `git diff` rather than rescanning everything.
12. Return conclusions and evidence (`path:line`) rather than raw tool output.
13. Keep exploration narrow.
14. Expand scope only when evidence requires it.

## 2. Exploration ladder
Start at the lowest level that can answer the question; move up only when it cannot.

| Level | Action | Salus example |
|---|---|---|
| 1 | `git status --short`, `git diff --stat`, `git diff -- <path>` | what is already changed (WIP) |
| 2 | known symbol | `rg -n "class RecipeGenerationCoordinator" backend/src/main` |
| 3 | `rg` / `git grep` for usages | `rg -n "recipeDraftValidator\.validate" backend/src` |
| 4 | file outline | `rg -n "^\s+(public|protected|private|static).*\(" backend/src/main/java/com/salus/healthytable/service/RecipeValidator.java` |
| 5 | targeted range | `sed -n '60,130p' <file>` or Read with offset/limit |
| 6 | whole file | small files (< ~300 lines) or when the whole file is the subject |
| 7 | directory | `ls`, `rg --files backend/src/main/java/com/salus/healthytable/service/allergen` |
| 8 | repository | only for cross-cutting questions; still use `rg --files` / `git ls-files`, never recursive reads |

Search hygiene: run searches from the repo root with a path (`backend/src/main`, `frontend/src`); never from the parent folder (sibling worktrees). Exclude `target/`, `node_modules/`, eval `results/`. For frontend-only or backend-only tasks, do not search the other side.

## 3. Logs and command output
- Maven: prefer `mvn -q ...`; on failure read `backend/target/surefire-reports/*.txt` selectively: `rg -n "Tests run:.*(Failures: [1-9]|Errors: [1-9])|FAIL" backend/target/surefire-reports`.
- Any log: `rg -n "ERROR|Exception|FAILED|Caused by" <log> | head -40`, then `sed -n '<start>,<end>p' <log>` around the first real cause; `tail -n 80` for the end state.
- Spring startup failures: find the first `Caused by:`; ignore the repeated wrapper stack frames.
- Summarize: failing test name, assertion or exception, `path:line`, likely cause. Do not paste whole logs.

## 4. Test strategy
Order: single test method -> related test class -> related package/classes -> full backend suite.
- Single: `cd backend && mvn -Dtest='ClassName#method' test`; class list: `-Dtest='A,B'`.
- Map changes to tests with `docs/ai/PROJECT_CONTEXT.md` "Test map" or `rg -n "ClassUnderTest" backend/src/test`.
- Run the full suite (`mvn -q clean test`) when: a shared validator/allergen/security component changed, `pom.xml` or `application.properties` changed, a Flyway migration was added, or before the user opens a PR. State the reason.
- Live LLM tests need Ollama and are opt-in (`RecipeAccuracyLiveTest`, `eval.sh --mode live`); do not run them unless asked.
- Frontend/admin: build/export commands only (no test scripts exist).
- If you could not run a test (no JDK 17, no network, missing secrets), say so instead of implying success.

## 5. Git safety and WIP protection
- Never assume commit/push permission. Commit, push, branch, PR or merge only when the user explicitly asks for that action; one approval does not cover later actions.
- Before any destructive git operation: stop and ask. Do not use `git reset`, `git clean`, `git restore`, `git checkout -- <path>` or other discarding checkouts, `git stash drop|clear`, `git worktree remove --force`, or force push unless explicitly requested.
- Start of a task: `git status --short` once; note pre-existing changes so you never claim or revert them.
- If a file you must edit already has unrelated uncommitted changes, edit around them; if that is impossible, stop and ask.
- Do not create commits, stashes, branches or worktrees as a "backup". Do not run formatters over whole files (no formatter is configured; mass reformatting destroys reviewable diffs).
- End of a task: `git status --short` and `git diff --stat` limited to the paths you touched.

## 6. Session boundaries
Split sessions by task boundary, not by habit.
- New session when the goal changes area: allergen work, LLM pipeline/evaluation, DB/Flyway, CI/infra, frontend.
- Stay in the session while you are still chasing the same bug or feature and the existing context is evidence you would otherwise re-collect.
- Before ending a long session, leave a short handoff (what changed, what is verified, what is open) in the reply, not in new files.

## 7. Model and effort tiers (role-based)
| Tier | Use for |
|---|---|
| LOW / FAST | doc edits, single-symbol lookups, small DTO or config changes, mechanical renames, reading test output |
| BALANCED | normal feature work, bug fixes with a clear reproduction, refactors inside one package, writing tests |
| HIGH REASONING | architecture decisions, allergen/health safety logic, LLM pipeline and validator behavior, concurrency/transactions, multi-step root-cause analysis |

- Pick the tier by risk and ambiguity, not by file count. Safety-sensitive work is HIGH even when the diff is small.
- Tool knobs (verify names with your installed version): Claude Code `/model`, `/effort` (`low` ... `max`); Codex CLI `-m/--model` and `model_reasoning_effort` in `config.toml`.
- Personal model preferences and cost policy live in the gitignored `CLAUDE.local.md` (Claude) or `~/.codex/config.toml` (Codex), not in shared docs.

## 8. Avoid
- Re-reading the same file, re-listing packages already mapped, repeated `git status` without an intervening change.
- Reading `README.md` or large docs end to end for a narrow question (search the heading first).
- Exploring frontend and backend together when the task is one-sided.
- Dumping logs, JSON fixtures or eval results into context.
- Summarizing every file you opened; long end-of-task recaps.

## 9. Accuracy over savings
Narrow first -> if evidence is insufficient -> widen exactly as much as needed. Never answer from assumption to save tokens. When something cannot be verified (e.g. a WIP file, an external API), say so explicitly.

## 10. Where new knowledge goes
| Question | Put it in |
|---|---|
| Needed in almost every session and short? | `AGENTS.md` (keep it ~100 lines, <= 32 KiB Codex limit) |
| Claude-only behavior? | `CLAUDE.md` below the `@AGENTS.md` import |
| Needed only for one kind of task? | a skill in `.agents/skills/` (copied unchanged to `.claude/skills/`) pointing to docs |
| Detail or reference for one area? | `docs/ai/PROJECT_CONTEXT.md` or `docs/ai/SAFETY_RULES.md` |
| Obvious from code in one search? | nowhere |
| Personal preference? | `CLAUDE.local.md` / user-level config |
