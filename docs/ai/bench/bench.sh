#!/usr/bin/env bash
# Salus agent benchmark helper (read-only tasks). Protocol: docs/ai/AGENT_EVALUATION.md
#
#   docs/ai/bench/bench.sh prepare <variant> [commit]          # detached worktree + variant instruction files
#   docs/ai/bench/bench.sh check <variant> [commit]            # show what the agent will see
#   docs/ai/bench/bench.sh dry-run <variant> <task> [run-no] [commit]   # print everything `run` would do, no agent call
#   docs/ai/bench/bench.sh run <variant> <task> <run-no> [commit]
#   docs/ai/bench/bench.sh list
#
# variants: claude-base | claude-before | claude-after | codex-base | codex-optimized
# tasks:    MAIN A B C D; supplementary S1 (docs/ai/bench/tasks.md)
# env:      BENCH_TIMEOUT_SECONDS (default 900), BENCH_RESULTS_DIR (default docs/ai/bench/results),
#           BENCH_LABEL (suffix for result dirs, e.g. effort-low), BENCH_CLAUDE_ARGS, BENCH_CODEX_ARGS
#
# Safety: never deletes or resets anything. Creates only `../Salus-bench-<variant>-<sha>` worktrees
# (detached, no branch) and files under the results directory. Removing a worktree is left to you.
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$BENCH_DIR" rev-parse --show-toplevel)"
DEFAULT_COMMIT="7aaeefc"
TASK_TIMEOUT="${BENCH_TIMEOUT_SECONDS:-900}"
RESULTS_DIR="${BENCH_RESULTS_DIR:-$BENCH_DIR/results}"
RUNTIME_DOCS="docs/ai/PROJECT_CONTEXT.md docs/ai/DEVELOPMENT_RULES.md docs/ai/SAFETY_RULES.md"
CLAUDE_ALLOWED="Read,Grep,Glob,Bash(git status *),Bash(git diff *),Bash(git log *),Bash(git show *),Bash(git grep *),Bash(git ls-files *),Bash(rg *),Bash(grep *),Bash(ls *),Bash(find *),Bash(sed -n *),Bash(head *),Bash(tail *),Bash(wc *),Bash(nl *),Bash(cat *)"
CLAUDE_DISALLOWED="Edit,Write,NotebookEdit,WebFetch,WebSearch"

die() { echo "bench: $*" >&2; exit 1; }

agent_of() {
  case "$1" in
    claude-base|claude-before|claude-after) echo claude ;;
    codex-base|codex-optimized) echo codex ;;
    *) die "unknown variant: $1" ;;
  esac
}

worktree_path() { # <variant> <commit>
  local short
  short="$(git -C "$REPO_ROOT" rev-parse --short "$2^{commit}" 2>/dev/null)" || die "unknown commit: $2"
  echo "$(dirname "$REPO_ROOT")/Salus-bench-$1-$short"
}

copy_file() { # <repo-relative source> <dest root> [dest-relative path]
  local src="$REPO_ROOT/$1" dst="$2/${3:-$1}"
  [ -f "$src" ] || die "missing harness file: $1"
  mkdir -p "$(dirname "$dst")"
  cp "$src" "$dst"
}

copy_skills() { # <skills dir relative to repo> <dest root>
  local d found=0
  for d in "$REPO_ROOT/$1"/*/; do
    [ -f "${d}SKILL.md" ] || continue
    copy_file "$1/$(basename "$d")/SKILL.md" "$2"
    found=1
  done
  [ "$found" = 1 ] || die "no skills found in $1"
}

cmd_prepare() {
  local variant="${1:?usage: prepare <variant> [commit]}" commit="${2:-$DEFAULT_COMMIT}" wt f
  agent_of "$variant" >/dev/null
  wt="$(worktree_path "$variant" "$commit")"
  [ ! -e "$wt" ] || die "already exists: $wt (reuse it, or remove it yourself first)"
  git -C "$REPO_ROOT" worktree add --detach "$wt" "$commit"
  case "$variant" in
    claude-base|codex-base) ;;
    claude-before)
      copy_file docs/ai/bench/variants/CLAUDE.before.md "$wt" CLAUDE.md ;;
    claude-after)
      copy_file CLAUDE.md "$wt"; copy_file AGENTS.md "$wt"
      for f in $RUNTIME_DOCS; do copy_file "$f" "$wt"; done
      copy_skills .claude/skills "$wt" ;;
    codex-optimized)
      copy_file AGENTS.md "$wt"
      for f in $RUNTIME_DOCS; do copy_file "$f" "$wt"; done
      copy_skills .agents/skills "$wt" ;;
  esac
  echo "prepared: $wt"
  cmd_check "$variant" "$commit"
}

cmd_check() {
  local variant="${1:?usage: check <variant> [commit]}" commit="${2:-$DEFAULT_COMMIT}" wt
  wt="$(worktree_path "$variant" "$commit")"
  [ -d "$wt" ] || die "not prepared: $wt"
  echo "worktree: $wt ($(git -C "$wt" rev-parse --short HEAD))"
  echo "untracked instruction files visible to the agent:"
  git -C "$wt" status --porcelain --untracked-files=all | sed 's/^/  /'
  [ ! -e "$wt/docs/ai/bench" ] || die "benchmark materials leaked into $wt/docs/ai/bench"
  [ -z "$(git -C "$wt" status --porcelain --untracked-files=no)" ] || die "tracked files modified in $wt"
}

cmd_list() {
  git -C "$REPO_ROOT" worktree list | grep 'Salus-bench-' || echo "no benchmark worktrees"
}

extract_prompt() { # <id>
  awk -v id="$1" '
    $0 == "<!-- prompt:" id " -->"  { on = 1; next }
    $0 == "<!-- /prompt:" id " -->" { on = 0 }
    on { print }
  ' "$BENCH_DIR/tasks.md"
}

build_prompt() { # <task>
  local body common
  body="$(extract_prompt "$1")"; [ -n "$body" ] || die "unknown task: $1"
  common="$(extract_prompt COMMON)"; [ -n "$common" ] || die "COMMON prompt block missing"
  printf '%s\n\n%s' "$body" "$common"
}

result_dir() { # <variant> <task> <run-no>
  echo "$RESULTS_DIR/$(date +%Y%m%d)/$1${BENCH_LABEL:++$BENCH_LABEL}/$2-r$3"
}

with_timeout() {
  if command -v timeout >/dev/null 2>&1; then timeout "$TASK_TIMEOUT" "$@"
  elif command -v gtimeout >/dev/null 2>&1; then gtimeout "$TASK_TIMEOUT" "$@"
  elif command -v perl >/dev/null 2>&1; then perl -e 'alarm shift @ARGV; exec @ARGV or die "exec failed: $!"' "$TASK_TIMEOUT" "$@"
  else echo "bench: no timeout, gtimeout or perl; running without a time limit" >&2; "$@"
  fi
}

describe_agent_command() { # <agent>
  case "$1" in
    claude) echo "CLAUDE_CODE_DISABLE_AUTO_MEMORY=1 claude -p <prompt> --output-format stream-json --verbose --permission-mode dontAsk --allowedTools \"$CLAUDE_ALLOWED\" --disallowedTools \"$CLAUDE_DISALLOWED\" ${BENCH_CLAUDE_ARGS:-}" ;;
    codex) echo "codex exec --json --sandbox read-only -o <out>/final.md ${BENCH_CODEX_ARGS:-} <prompt>" ;;
  esac
}

run_agent() { # <agent> <prompt> <out dir>   (cwd = worktree)
  case "$1" in
    claude)
      # Auto memory is shared across worktrees of one repo; disable it so daily-use memory does not leak in.
      # shellcheck disable=SC2086
      CLAUDE_CODE_DISABLE_AUTO_MEMORY=1 with_timeout claude -p "$2" \
        --output-format stream-json --verbose \
        --permission-mode dontAsk \
        --allowedTools "$CLAUDE_ALLOWED" \
        --disallowedTools "$CLAUDE_DISALLOWED" \
        ${BENCH_CLAUDE_ARGS:-} \
        > "$3/events.jsonl" 2> "$3/stderr.log"
      ;;
    codex)
      # shellcheck disable=SC2086
      with_timeout codex exec --json --sandbox read-only -o "$3/final.md" ${BENCH_CODEX_ARGS:-} "$2" \
        > "$3/events.jsonl" 2> "$3/stderr.log"
      ;;
  esac
}

cmd_dry_run() {
  local variant="${1:?usage: dry-run <variant> <task> [run-no] [commit]}" task="${2:?task}" run_no="${3:-1}"
  local commit="${4:-$DEFAULT_COMMIT}" agent wt
  agent="$(agent_of "$variant")"
  wt="$(worktree_path "$variant" "$commit")"
  echo "condition:  $variant (agent: $agent)"
  echo "task:       $task"
  echo "worktree:   $wt $( [ -d "$wt" ] && echo '(exists)' || echo '(NOT PREPARED)')"
  echo "output:     $(result_dir "$variant" "$task" "$run_no")"
  echo "timeout:    ${TASK_TIMEOUT}s"
  echo "command:    $(describe_agent_command "$agent")"
  echo "model/effort args: claude='${BENCH_CLAUDE_ARGS:-<CLI default>}' codex='${BENCH_CODEX_ARGS:-<CLI default>}'"
  echo "prompt:"
  build_prompt "$task" | sed 's/^/  | /'
  echo
}

cmd_run() {
  local variant="${1:?usage: run <variant> <task> <run-no> [commit]}" task="${2:?task}" run_no="${3:?run-no}"
  local commit="${4:-$DEFAULT_COMMIT}" agent wt out prompt start end rc version
  agent="$(agent_of "$variant")"
  wt="$(worktree_path "$variant" "$commit")"
  [ -d "$wt" ] || die "not prepared: run 'bench.sh prepare $variant $commit' first"
  [ -z "$(git -C "$wt" status --porcelain --untracked-files=no)" ] || die "tracked files modified in $wt; recreate the worktree"
  prompt="$(build_prompt "$task")"
  out="$(result_dir "$variant" "$task" "$run_no")"
  [ ! -e "$out" ] || die "result already exists: $out"
  mkdir -p "$out"
  printf '%s\n' "$prompt" > "$out/prompt.txt"
  git -C "$wt" status --porcelain --untracked-files=all > "$out/status-before.txt"
  version="$("$agent" --version 2>/dev/null | head -n 1 || echo unknown)"

  start="$(date +%s)"
  set +e
  (cd "$wt" && run_agent "$agent" "$prompt" "$out")
  rc=$?
  set -e
  end="$(date +%s)"
  git -C "$wt" status --porcelain --untracked-files=all > "$out/status-after.txt"

  BENCH_META_AGENT="$agent" BENCH_META_VARIANT="$variant" BENCH_META_TASK="$task" BENCH_META_RUN="$run_no" \
  BENCH_META_COMMIT="$(git -C "$wt" rev-parse HEAD)" BENCH_META_WORKTREE="$wt" BENCH_META_VERSION="$version" \
  BENCH_META_START="$start" BENCH_META_END="$end" BENCH_META_RC="$rc" BENCH_META_TIMEOUT="$TASK_TIMEOUT" \
  BENCH_META_LABEL="${BENCH_LABEL:-}" BENCH_META_ARGS="${BENCH_CLAUDE_ARGS:-}${BENCH_CODEX_ARGS:-}" \
    python3 "$BENCH_DIR/metrics.py" meta "$out"
  echo "done: $out (exit $rc, $((end - start))s)"
  if ! cmp -s "$out/status-before.txt" "$out/status-after.txt"; then
    echo "bench: WARNING worktree changed during the run (read-only violation); see $out/status-after.txt" >&2
  fi
  return "$rc"
}

case "${1:-}" in
  prepare) shift; cmd_prepare "$@" ;;
  check) shift; cmd_check "$@" ;;
  dry-run) shift; cmd_dry_run "$@" ;;
  run) shift; cmd_run "$@" ;;
  list) cmd_list ;;
  *) sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
