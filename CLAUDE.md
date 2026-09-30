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
