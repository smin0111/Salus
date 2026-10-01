# Salus AI Agent Harness (사람용 안내)

Claude Code와 Codex가 **하나의 프로젝트 지식**을 공유하면서, 필요한 지식만 필요한 시점에 읽도록 만든 구성이다.
에이전트는 이 파일을 읽을 필요가 없다(AGENTS.md가 하네스 작업일 때만 안내).

## 구조
```text
                 Shared knowledge (on demand, docs/ai/)
        PROJECT_CONTEXT.md · DEVELOPMENT_RULES.md · SAFETY_RULES.md
                                  ▲
                 read only when the task/skill needs it
          ┌───────────────────────┴───────────────────────┐
     Claude Code                                         Codex
  CLAUDE.md ──@AGENTS.md import──► AGENTS.md ◄── auto-loaded (repo root → cwd)
  (Claude-only notes)          (shared core rules)
  .claude/skills/<name>/SKILL.md  ══ identical ══  .agents/skills/<name>/SKILL.md
          └───────────────────────┬───────────────────────┘
                     Salus repository (code = source of truth)
```

## 로딩 시점과 크기 (2026-09-17 측정, bytes)
| 파일 | Claude Code | Codex | 크기 |
|---|---|---|---|
| `AGENTS.md` | 매 세션 (CLAUDE.md가 import) | 매 세션 | 60줄 / 5,977 |
| `CLAUDE.md` | 매 세션 (HTML 주석 제거 후 746) | 읽지 않음 | 15줄 / 1,222 |
| 스킬 name + description 3개 | 매 세션 목록 | 매 세션 목록 | 설명 합 934 |
| `SKILL.md` 본문 | 호출할 때만 | 사용할 때만 | 24~34줄 |
| `docs/ai/PROJECT_CONTEXT.md` / `DEVELOPMENT_RULES.md` / `SAFETY_RULES.md` | 필요할 때 부분 읽기 | 동일 | 93 / 95 / 51줄 |
| `docs/ai/README.md`, `AGENT_EVALUATION.md`, `bench/` | 사람용 | 사람용 | — |
| `CLAUDE.local.md` | 매 세션 (개인 설정, 하네스가 수정하지 않음) | 읽지 않음 | 17줄 |

토큰 수는 모델별 토크나이저가 달라 여기서 추정하지 않는다. Claude는 세션에서 `/context`의 Memory files, Codex는 벤치마크 `events.jsonl`의 usage로 측정한다.

## 설계 결정
- **AGENTS.md가 공통 단일 원본**: Claude Code는 `AGENTS.md`를 직접 읽지 않으므로 공식 권장 방식인 `CLAUDE.md`의 `@AGENTS.md` import로 공유한다. import는 lazy가 아니므로 AGENTS.md는 짧게 유지한다.
- **중첩 AGENTS.md / CLAUDE.md 없음**: Codex는 저장소 루트에서 현재 작업 디렉터리까지의 AGENTS.md만 자동으로 합친다. 세션을 루트에서 시작하면 `backend/.../allergen/AGENTS.md` 같은 하위 파일은 자동 주입되지 않는다. 대부분 작업이 backend를 건드려 중첩 CLAUDE.md도 절감 효과가 없다. 영역별 지식은 스킬(양쪽 모두 on-demand)로 둔다.
- **스킬 3개**: `salus-llm-recipe-pipeline`, `salus-allergen-safety`, `salus-backend-change`. 둘 다 Agent Skills 형식(`name`, `description`)만 사용해 파일을 그대로 공유한다. 스킬은 체크리스트와 진입점만 담고 상세는 `docs/ai`를 가리킨다.
- **에이전트용 문서는 영어**: 매번 로드되거나 자주 읽히는 파일이라 토큰 효율을 우선했다. 도메인 용어(빼고, 제외, 함유 등)는 원문 유지. 사람용 문서는 한국어.
- **(WIP) 표시**: 2026-09-17 미커밋 작업에만 있던 클래스/문서. 커밋되면 표시를 지우고, 폐기되면 항목을 지운다.

## 유지보수 규칙
1. 공통 규칙은 `AGENTS.md` 한 곳에만 쓴다. `CLAUDE.md`에는 Claude 전용 내용만 둔다.
2. 스킬 원본은 `.agents/skills/`(Codex 경로)이고 `.claude/skills/`는 같은 내용의 사본이다. 수정 후 `cp -R .agents/skills/. .claude/skills/` 후 `diff -r .agents/skills .claude/skills`로 확인한다. (Claude Code가 심볼릭 링크된 스킬 폴더를 따라가는지는 확인하지 않았으므로 복사를 기본으로 한다.)
3. 코드 동작이 바뀌면 같은 변경에서 해당 문서를 고친다. 새 지식의 위치는 `DEVELOPMENT_RULES.md` 10장 기준을 따른다.
4. `AGENTS.md`는 약 100줄 이내(Codex 기본 한도 32 KiB보다 훨씬 작게), 스킬 설명은 핵심 트리거 단어를 앞에 둔다.
5. 분기마다 또는 큰 리팩터링 후: 문서에 적힌 경로/클래스가 존재하는지 `rg`로 점검하고, 벤치마크 A~E를 파일럿으로 1회 돌린다.

## 설치 버전·동작 확인 체크리스트 (이번 작업에서 로컬 셸을 쓸 수 없어 미확인)
- **최초 1회 필수**: 원격 도구로는 `.claude/` 아래에 쓸 수 없어 Claude용 스킬 사본이 아직 없다. 저장소 루트에서 `mkdir -p .claude/skills && cp -R .agents/skills/. .claude/skills/ && diff -r .agents/skills .claude/skills`
- `claude --version`, `codex --version`
- Claude Code 세션에서 `/context`: Memory files에 `CLAUDE.md`와 import된 `AGENTS.md`가 보이는지, 스킬 3개가 목록에 있는지.
- Codex: `codex --ask-for-approval never "Summarize the current instructions."`로 AGENTS.md 반영 확인, 세션에서 `/skills`로 스킬 3개 확인.
- 판단 근거로 확인한 공식 문서: Claude Code memory/skills/large-codebases/headless 문서(2026-09 기준, npm 최신 2.1.274), Codex AGENTS.md/skills/non-interactive 문서(npm 최신 0.154.0). 설치 버전이 크게 낮으면 `.agents/skills`, `@import`, `dontAsk` 등의 지원 여부를 먼저 확인한다.

## MCP / 도구 context 전략
확인 범위: 저장소에는 `.mcp.json`, `.codex/` 설정이 없다. 사용자 전역 설정(`~/.claude.json`, `~/.codex/config.toml`)은 이번 작업에서 접근하지 않았다. 아래는 분류 기준이며, 실제 목록은 직접 확인한다: Claude `claude mcp list` + 세션 `/context`(MCP tools 항목의 토큰), Codex는 `config.toml`의 `[mcp_servers]`.

| 분류 | 기준 | Salus 예시 |
|---|---|---|
| KEEP | 거의 모든 Salus 작업에 쓰이고 도구 수가 적음 | 없음. 코드 탐색은 `rg`/`git`으로 충분하다 |
| OPTIONAL | 특정 작업에서만 필요 | GitHub(PR/CI 확인), 브라우저 자동화(프론트 UI 확인), 읽기 전용 DB 조회, 문서 도구(설계 문서 작업) |
| DISABLE WHEN UNUSED | 도구 정의가 크거나 기능이 중복되거나 Salus와 무관 | 도구 수십 개짜리 범용 서버, 브라우저/검색 중복 서버, 사용하지 않는 서비스 커넥터 |

원칙: MCP 서버 하나는 사용하지 않아도 도구 정의만큼 매 세션 context를 쓴다(버전에 따라 지연 로딩될 수 있으니 `/context`로 실제 값 확인). 작업 세션을 시작할 때 필요한 서버만 켠다.

## 외부 도구 판단 (설치하지 않음)
| 도구 | 판단 | 이유 |
|---|---|---|
| ccusage | NOW (필요할 때 `npx ccusage@latest ...`) | Claude Code·Codex 로컬 로그에서 일/세션별 사용량을 집계해 벤치마크 값을 교차 확인할 수 있다. 전역 설치 없이 실행 가능(npx는 npm 캐시에 내려받음) |
| Serena | LATER | 장점: Java LSP 기반 심볼 검색·참조 추적으로 600줄 이상 서비스 클래스의 전체 읽기를 줄일 수 있다. 단점: uv/Python + Java 언어 서버 설치와 인덱싱, MCP 도구 정의가 매 세션 context를 차지, Lombok 생성 코드 인식 설정 이슈 가능, 두 에이전트에 각각 설정 필요. 현재 Salus는 평면 `service/` + 필드 주입 구조라 `rg -n "fieldName\."` 한두 번으로 호출 관계가 추적된다(이번 조사도 그렇게 수행). 벤치마크에서 `large_full_reads`·반복 읽기가 높게 나오면 도입을 검토하고, 먼저 Claude Code 공식 code-intelligence(LSP) 플러그인에 Java가 있는지 확인한다 |
| Superpowers | NOT NEEDED (현재) | 범용 워크플로 스킬 묶음이라 이 하네스의 규칙(탐색·테스트·계획)과 겹치고, 스킬 목록이 늘어 매 세션 설명 비용이 커진다. 필요한 기법만 개별 스킬로 차용 |
| ECC (Everything Claude Code 계열 설정 번들) | NOT NEEDED | 대량의 agents/skills/hooks/rules를 한꺼번에 들여오는 방식이라 "적은 context, 중복 제거" 목표와 반대이며 규칙 충돌 위험이 크다. 다른 도구를 뜻한 것이면 재검토 |
| Repomix | NOT NEEDED (일상 작업) | 저장소를 한 파일로 묶어 넣는 방식은 에이전트의 "전체 읽기"를 조장한다. 파일 시스템 접근이 없는 외부 채팅 모델에 특정 디렉터리만 넘길 때만 제한적으로 유용 |
| Aider repo map 방식 | LATER (아이디어) | tree-sitter 심볼 지도 자동 생성. 지금은 `PROJECT_CONTEXT.md`가 수동 지도 역할을 한다. 지도가 자주 낡기 시작하면 생성 스크립트를 검토 |

여러 하네스를 동시에 설치하지 않는다. 도구를 추가할 때는 벤치마크 전/후 비교로 효과를 확인한다.
