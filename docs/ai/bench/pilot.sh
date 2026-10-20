#!/usr/bin/env bash
# Salus AI harness: local verification + A/D pilot (protocol: docs/ai/AGENT_EVALUATION.md, section 8).
#
#   docs/ai/bench/pilot.sh preflight   # NO model calls: git state, CLI versions/help, skill sync,
#                                      # working-tree facts, benchmark worktrees, dry-run
#   docs/ai/bench/pilot.sh run         # instruction-loading probes + pilot (tasks D,A x 4 conditions x 1 run)
#                                      # + metrics + raw-log field report + WIP protection check
#
# All output goes to $PILOT_OUT (default: ../Salus-harness-pilot, outside the repository).
# Use a new PILOT_OUT to repeat a pilot. Works with macOS bash 3.2.
# Safety: git is only read (--no-optional-locks). No commit/push/branch/reset/restore/clean/stash, no deletes.
# Writes: .claude/skills (copies of .agents/skills; a differing existing file is never overwritten),
#         detached ../Salus-bench-* worktrees, and $PILOT_OUT.
set -uo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(git -C "$BENCH_DIR" rev-parse --show-toplevel)" || exit 1
OUT="${PILOT_OUT:-$(dirname "$REPO")/Salus-harness-pilot}"
export BENCH_RESULTS_DIR="$OUT/results"
VARIANTS="claude-before claude-after codex-base codex-optimized"
PRODUCTION_PATHS="backend/src frontend admin poc"
J=backend/src/main/java/com/salus/healthytable
T=backend/src/test/java/com/salus/healthytable

g() { git -C "$REPO" --no-optional-locks "$@"; }
section() { printf '\n===== %s\n' "$*"; }
gg() { g grep --untracked -n -I -E "$@" || true; }

capture() { # <output file name> <command...>
  local name="$1" rc
  shift
  "$@" > "$OUT/$name" 2>&1
  rc=$?
  echo "\$ $* -> exit $rc"
  head -n 3 "$OUT/$name" | sed 's/^/    /'
}

wip_manifest() { # <output file>: fingerprints of every modified/untracked production file + tracked diff
  (
    cd "$REPO" || exit 1
    echo "# tracked diff sha1: $(g diff --binary -- $PRODUCTION_PATHS | shasum -a 1 | cut -d' ' -f1)"
    g ls-files -m -o --exclude-standard -- $PRODUCTION_PATHS | sort -u | while IFS= read -r f; do
      if [ -f "$f" ]; then shasum -a 1 "$f"; else echo "missing  $f"; fi
    done
  ) > "$1"
}

sync_skills() {
  (
    cd "$REPO" || exit 1
    find .agents/skills -type f ! -name .DS_Store | sort | while IFS= read -r src; do
      dst=".claude/skills/${src#.agents/skills/}"
      if [ ! -e "$dst" ]; then
        mkdir -p "$(dirname "$dst")" && cp "$src" "$dst" && echo "copied: $dst"
      elif cmp -s "$src" "$dst"; then
        echo "identical: $dst"
      else
        echo "CONFLICT (left unchanged): $dst differs from $src"
      fi
    done
    find .claude/skills -type f ! -name .DS_Store 2>/dev/null | sort | while IFS= read -r dst; do
      [ -e ".agents/skills/${dst#.claude/skills/}" ] || echo "EXTRA (left unchanged): $dst"
    done
  )
}

cmd_preflight() {
  mkdir -p "$OUT"
  exec > >(tee "$OUT/preflight.log") 2>&1
  cd "$REPO" || exit 1
  echo "pilot preflight $(date '+%Y-%m-%d %H:%M:%S %z')  out=$OUT"

  section "1. git state (read-only)"
  echo "pwd: $(pwd)"
  echo "branch: $(g branch --show-current)"
  echo "HEAD: $(g rev-parse --short HEAD)"
  g status --short > "$OUT/git-status-before.txt"
  echo "status --short entries: $(wc -l < "$OUT/git-status-before.txt" | tr -d ' ')"
  g diff --check > "$OUT/git-diff-check-before.txt" 2>&1
  echo "diff --check exit: $? (output lines: $(wc -l < "$OUT/git-diff-check-before.txt" | tr -d ' '))"
  g diff --stat > "$OUT/git-diff-stat-before.txt"
  echo "diff --stat: $(tail -n 1 "$OUT/git-diff-stat-before.txt")"
  echo "harness paths (untracked files listed individually):"
  g status --short --untracked-files=all -- CLAUDE.md AGENTS.md docs/ai .agents .claude | sed 's/^/  /'
  wip_manifest "$OUT/wip-manifest-before.txt"
  echo "WIP manifest entries: $(wc -l < "$OUT/wip-manifest-before.txt" | tr -d ' ')"

  section "2. CLI versions and help"
  for c in claude codex git python3 perl java mvn node npx rg timeout gtimeout; do
    printf '  %-9s %s\n' "$c" "$(command -v "$c" || echo 'NOT INSTALLED')"
  done
  capture cli-claude-version.txt claude --version
  capture cli-codex-version.txt codex --version
  capture cli-claude-help.txt claude --help
  capture cli-codex-help.txt codex --help
  capture cli-codex-exec-help.txt codex exec --help
  capture cli-java-version.txt java -version
  capture cli-mvn-version.txt mvn -v
  capture cli-java17-home.txt /usr/libexec/java_home -v 17
  echo "flags used by bench.sh / pilot.sh, searched in help text (absence from help is not proof of no support):"
  for f in --print --output-format stream-json --verbose --permission-mode dontAsk --allowedTools --disallowedTools --effort --model; do
    if grep -q -- "$f" "$OUT/cli-claude-help.txt"; then echo "  claude help mentions:        $f"; else echo "  claude help DOES NOT mention: $f"; fi
  done
  for f in --json --sandbox read-only --output-last-message --ask-for-approval --model --config model_reasoning_effort; do
    if grep -q -- "$f" "$OUT/cli-codex-exec-help.txt"; then echo "  codex exec help mentions:        $f"; else echo "  codex exec help DOES NOT mention: $f"; fi
  done

  section "3. skill sync (.agents/skills -> .claude/skills)"
  echo "-- .agents/skills"; find .agents/skills -maxdepth 2 -type f -print | sort | sed 's/^/  /'
  echo "-- .claude/skills (before)"; find .claude/skills -maxdepth 2 -type f -print 2>/dev/null | sort | sed 's/^/  /'
  sync_skills | sed 's/^/  /'
  if diff -qr -x .DS_Store .agents/skills .claude/skills; then echo "Claude/Codex skill copies are identical"; else echo "diff -qr reported differences (above)"; fi

  section "4. working-tree facts (targeted search; tracked + untracked, .gitignore respected)"
  echo "-- allergen main"; ls "$J/service/allergen"
  echo "-- allergen tests"; ls "$T/service/allergen"
  echo "-- eval harness"; ls "$T/eval" 2>&1; ls backend/src/test/resources/eval 2>&1
  echo "-- migrations"; ls backend/src/main/resources/db/migration
  echo "-- recipes resources"; ls backend/src/main/resources/recipes 2>&1
  echo "-- Task A: controller"; gg -e '@PostMapping\("/message"\)' -e 'chatRateLimitService\.checkAllowed' -e 'chatService\.processChat' -- "$J/controller/ChatController.java"
  echo "-- Task A: ChatService"; gg -e 'Mono<ChatDto\.Response> processChat' -e 'chatIntentClassifier\.classify' -e 'buildSafetyContext\(' -e 'healthContextAvailable\(\)' -e 'recipeAgentOrchestrator\.handle' -e 'recipeEvidenceService\.resolve' -e 'recipeGenerationCoordinator\.[a-zA-Z]+' -e 'approvedRecipe[A-Za-z]*\.[a-zA-Z]+' -e 'llmService\.getChatResponse' -- "$J/service/ChatService.java"
  echo "-- Task A: coordinator"; gg -e 'private final [A-Za-z<>]+ [a-zA-Z]+;' -e 'recipeGenerationClient\.generate' -e 'recipeDraftMapper\.' -e 'findAllergyConflicts' -e 'recipeDraftValidator\.validate' -e 'repairOrFail\(' -e 'recipeReplyFormatter\.format' -e 'recipeValidator\.validate' -e 'saveGeneratedRecipeAudit' -e 'saveToRecipeDbSafely' -e 'saveRecommendation' -e 'buildRecipeCard' -e 'Timeout' -- "$J/service/RecipeGenerationCoordinator.java"
  echo "-- Task A: clients"; gg -e 'implements RecipeGenerationClient' -e 'thinkingSettingFor' -e 'recipePromptFactory\.' -- "$J/service"
  echo "-- validator version strings"; gg -e 'validator_version|validatorVersion|VALIDATOR_VERSION|"v[0-9]+\.[0-9]+"' -- "$J"
  echo "-- Tavily content selection"; gg -e 'rawContent|raw_content|content\(\)|selected' -- "$J/service/TavilySearchEngine.java"
  echo "-- declaration states and verification labels"; gg -e 'DECLARED_PRESENT|DECLARED_NONE|DECLARATION_NOT_FOUND|UNREADABLE|WEB_VERIFIED_STRICT|GOLD_PHYSICAL' -- backend/src/main backend/src/test/java | cut -c1-220 | head -60
  echo "-- declaration metrics in docs"; gg -e '15/15|15/20|20종|S001|S020|Cross-contact' -- docs/allergen-declaration-parser.md
  echo "-- Task D"; gg -e 'void singleSyllableAllergens[A-Za-z]*\(' -e '밀크티' -- "$T/service/allergen/AllergenMatcherTest.java"
  gg -e 'term\.length\(\) == 1' -e 'private boolean matches\(' -e 'public boolean conflicts\(' -- "$J/service/allergen/AllergenMatcher.java"
  gg -e 'requireJavaVersion' -e '\[17,18\)' -e '\[3\.9' -- backend/pom.xml

  section "5. read-only copies of key working-tree files (for offline doc and answer-key review)"
  mkdir -p "$OUT/wt-src"
  for f in "$J/controller/ChatController.java" "$J/service/ChatService.java" "$J/service/RecipeGenerationCoordinator.java" \
           "$J/service/OllamaRecipeGenerationClient.java" "$J/service/OllamaLlmService.java" "$J/service/TavilySearchEngine.java" \
           "$J/service/RecipeEvidenceService.java" "$J/service/GeneratedRecipeLifecycleService.java" \
           "$J/service/ApprovedRecipeService.java" "$J/service/ChatSafetyContextService.java" \
           "$J"/service/allergen/*.java "$T"/service/allergen/*.java backend/src/main/resources/application.properties; do
    if [ -f "$f" ]; then
      cp "$f" "$OUT/wt-src/$(echo "$f" | sed "s|^$J/|main__|; s|^$T/|test__|; s|^backend/src/main/resources/|res__|; s|/|__|g")"
    fi
  done
  ls "$OUT/wt-src" | sed 's/^/  /'

  section "6. benchmark worktrees (detached, next to the repository)"
  for v in $VARIANTS; do
    if [ -d "$(dirname "$REPO")/Salus-bench-$v-$(g rev-parse --short 7aaeefc)" ]; then
      bash "$BENCH_DIR/bench.sh" check "$v"
    else
      bash "$BENCH_DIR/bench.sh" prepare "$v"
    fi
    echo "prepare/check $v exit: $?"
  done

  section "7. dry-run (no agent calls)"
  for v in $VARIANTS; do for t in D A; do bash "$BENCH_DIR/bench.sh" dry-run "$v" "$t" 1; done; done

  section "PREFLIGHT DONE"
  sleep 1
}

latest_run_dir() { # <variant> <task>
  ls -d "$BENCH_RESULTS_DIR"/*/"$1"/"$2"-r1 2>/dev/null | tail -n 1
}

cmd_run() {
  [ -f "$OUT/preflight.log" ] || { echo "run 'pilot.sh preflight' first"; exit 1; }
  exec > >(tee "$OUT/run.log") 2>&1
  cd "$REPO" || exit 1
  echo "pilot run $(date '+%Y-%m-%d %H:%M:%S %z')  out=$OUT"
  wip_manifest "$OUT/wip-manifest-run-start.txt"
  g status --short > "$OUT/git-status-run-start.txt"

  section "1. instruction and skill loading probes (main working tree, read-only)"
  probe_claude='Do not inspect application source code.

Report only:
1. Which project instruction files are currently loaded or referenced.
2. Which Salus project skills are available.
3. Whether AGENTS.md is referenced through CLAUDE.md.

Do not modify any files.'
  probe_codex='Do not inspect application source code.

Report only:
1. Which repository instruction files you loaded.
2. Which repository skills are available.
3. Do not modify any files.'
  claude -p "$probe_claude" --output-format stream-json --verbose --permission-mode dontAsk \
    --disallowedTools "Bash,Read,Grep,Glob,Edit,Write,NotebookEdit,WebFetch,WebSearch" \
    > "$OUT/probe-claude.jsonl" 2> "$OUT/probe-claude.stderr"
  echo "claude probe exit: $?"
  claude -p "/context" > "$OUT/probe-claude-context.txt" 2>&1
  echo "claude -p /context exit: $?"
  codex exec --json --sandbox read-only -o "$OUT/probe-codex-final.md" "$probe_codex" \
    > "$OUT/probe-codex.jsonl" 2> "$OUT/probe-codex.stderr"
  echo "codex probe exit: $?"

  section "2. pilot: tasks D then A, 4 conditions, 1 run each"
  claude_ok=1; codex_ok=1
  for task in D A; do
    for v in $VARIANTS; do
      case "$v" in claude-*) ok=$claude_ok ;; *) ok=$codex_ok ;; esac
      if [ "$ok" != 1 ]; then echo "SKIPPED $v $task (an earlier run of this agent failed)"; continue; fi
      bash "$BENCH_DIR/bench.sh" run "$v" "$task" 1
      rc=$?
      dir="$(latest_run_dir "$v" "$task")"
      if [ "$rc" -ne 0 ] || [ -z "$dir" ] || ! grep -q -E '"type" *: *"(result|turn\.completed)"' "$dir/events.jsonl" 2>/dev/null; then
        echo "FAILED $v $task (exit $rc, dir=${dir:-none}); remaining runs for this agent are skipped"
        case "$v" in claude-*) claude_ok=0 ;; *) codex_ok=0 ;; esac
      else
        echo "OK $v $task -> $dir"
      fi
    done
  done

  section "3. metrics and raw-log field report"
  python3 "$BENCH_DIR/metrics.py" collect "$BENCH_RESULTS_DIR" -o "$OUT/metrics.csv"
  python3 "$BENCH_DIR/metrics.py" fields "$BENCH_RESULTS_DIR" > "$OUT/fields.txt" 2>&1
  echo "fields report: $OUT/fields.txt"

  section "4. ccusage cross-check (npx cache only; set PILOT_CCUSAGE=0 to skip)"
  if [ "${PILOT_CCUSAGE:-1}" = 1 ] && command -v npx >/dev/null 2>&1; then
    NO_COLOR=1 FORCE_COLOR=0 npx --yes ccusage@latest --help > "$OUT/ccusage-help.txt" 2>&1; echo "ccusage --help exit: $?"
    NO_COLOR=1 FORCE_COLOR=0 npx --yes ccusage@latest claude session > "$OUT/ccusage-claude-session.txt" 2>&1; echo "ccusage claude session exit: $?"
    NO_COLOR=1 FORCE_COLOR=0 npx --yes ccusage@latest codex session > "$OUT/ccusage-codex-session.txt" 2>&1; echo "ccusage codex session exit: $?"
  else
    echo "skipped"
  fi

  section "5. WIP protection check"
  wip_manifest "$OUT/wip-manifest-run-end.txt"
  if cmp -s "$OUT/wip-manifest-run-start.txt" "$OUT/wip-manifest-run-end.txt"; then
    echo "production/WIP manifest unchanged during the run"
  else
    echo "MANIFEST CHANGED during the run (check whether another session edited these files):"
    diff "$OUT/wip-manifest-run-start.txt" "$OUT/wip-manifest-run-end.txt" | head -40
  fi
  g status --short > "$OUT/git-status-after.txt"
  diff "$OUT/git-status-run-start.txt" "$OUT/git-status-after.txt" > "$OUT/git-status-diff.txt"
  echo "git status --short changes during the run: $(wc -l < "$OUT/git-status-diff.txt" | tr -d ' ') lines"
  g diff --check > "$OUT/git-diff-check-after.txt" 2>&1
  echo "diff --check exit: $?"

  section "RUN DONE"
  sleep 1
}

case "${1:-}" in
  preflight) cmd_preflight ;;
  run) cmd_run ;;
  *) sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
