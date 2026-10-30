<!--
Maintainer note (HTML comments are stripped before Claude sees this file):
- Shared rules for Claude Code and Codex live in AGENTS.md (imported below). Do not duplicate them here.
- Only Claude Code-specific guidance belongs in this file.
- The pre-harness CLAUDE.md (2026-08-21) is preserved verbatim at docs/ai/bench/variants/CLAUDE.before.md for before/after benchmarks.
- Harness design, maintenance and benchmark: docs/ai/README.md, docs/ai/AGENT_EVALUATION.md.
-->
@AGENTS.md

## Claude Code specifics
- Project skills: `.claude/skills/` (`salus-llm-recipe-pipeline`, `salus-allergen-safety`, `salus-backend-change`). Invoke the matching skill before exploring. The source copies live in `.agents/skills/` (shared with Codex); change them there and copy to `.claude/skills/`.
- Large files: use `Read` with `offset`/`limit` after locating lines with `Grep`; prefer the `Grep` tool over Bash `grep -r`.
- Use a subagent only for broad multi-directory sweeps where just the conclusion matters; do single-symbol lookups directly.
- `/context` shows which memory files, skills and MCP tools are consuming context.
- Personal preferences (language, model choice, verbosity) stay in the gitignored `CLAUDE.local.md`.
- 구현 검토: 승인된 구현과 로컬 검증을 마친 뒤 완료 보고 전에 프로젝트 `codex-review` 스킬을 사용합니다. 현재 Claude 대화에서 읽기 전용 Codex의 지적을 코드로 검증하고 수정 또는 반박합니다. 완료 검토는 같은 job에서 최대 3회이며, 한도·일시적 실행 실패는 3회 이후 1시간 간격으로 같은 모델을 재시도합니다. 모델 교체·API 키 전환·추가 결제는 하지 않습니다. 대기 상태는 중복 호출하지 말고 조회합니다. 미해결 의견 차이·증거 부족·영구 오류·완료 회차 한도는 사용자에게 보고합니다. 커밋·푸시·PR·병합·배포·접근 모드 변경·기능 활성화는 별도 사용자 권한을 따릅니다. 읽기 전용 질문·검토와 사용자가 생략한 작업에는 이 루프를 실행하지 않습니다.

- "자동화프로젝트 시작해"를 받으면 AGENTS.md의 개발 자동화 시작·중지 규칙을 적용합니다. 중지 후 재개 시 `.codex-review/handoff.md`와 해당 job 상태를 먼저 확인하고, 저장된 범위로 재개합니다. 커밋·푸시·PR은 사용자의 별도 지시 전에는 실행하거나 매번 권유하지 않습니다.
