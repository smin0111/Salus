#!/usr/bin/env bash
#
# 같은 설정으로 평가를 N회 "별도 실행"해 실행 간 변동 폭을 측정합니다.
#
#   ./scripts/eval-repeat-runs.sh 3 qwen3:8b gemma3:4b
#     └ 3회 반복, 대상 qwen3:8b, 폴백 gemma3:4b
#   ./scripts/eval-repeat-runs.sh 6 qwen3:8b gemma3:4b backend/target/eval-final backend/target/eval-runs-…
#     └ 넷째 인자부터는 같은 코드로 돌린 이전 실행 디렉터리. 변동 폭 계산에 함께 넣습니다.
#
# --repeat 옵션과 다릅니다. 한 실행 안에서 생성은 결정적이라(24개 조합 전부 3/3 또는 0/3)
# repeat를 올려도 같은 결과가 반복될 뿐입니다. 결과가 달라지는 것은 모델이 재로드되는
# 실행 사이이므로, 실행 자체를 반복하고 사이에 모델을 언로드합니다.
#
# 절전이 개입하면 지연이 오염되고 타임아웃도 멈추므로 caffeinate로 감쌉니다.
set -euo pipefail

RUNS="${1:-3}"
MODEL="${2:-qwen3:8b}"
FALLBACK="${3:-}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$REPO_ROOT/backend"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_ROOT="$BACKEND_DIR/target/eval-runs-$STAMP"
mkdir -p "$OUT_ROOT"

if [[ -z "${JAVA_HOME:-}" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
    if JAVA_17_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null)"; then
        export JAVA_HOME="$JAVA_17_HOME"
    fi
fi

echo "반복 ${RUNS}회 / 모델 ${MODEL} / 폴백 ${FALLBACK:-없음}"
echo "출력 $OUT_ROOT"
echo

for i in $(seq 1 "$RUNS"); do
    # 이전 실행의 모델이 상주해 있으면 메모리 압박으로 지연이 오염됩니다.
    for loaded in $(ollama ps 2>/dev/null | tail -n +2 | awk '{print $1}'); do
        ollama stop "$loaded" >/dev/null 2>&1 || true
    done

    RUN_OUT="target/eval-runs-$STAMP/run$i"
    ARGS=(-Dsalus.eval.mode=live
          -Dsalus.eval.models="$MODEL"
          -Dsalus.eval.repeat=1
          -Dsalus.eval.gate=false
          -Dsalus.eval.out="$RUN_OUT")
    [[ -n "$FALLBACK" ]] && ARGS+=(-Dsalus.eval.fallback-model="$FALLBACK")

    echo "[$i/$RUNS] 실행 중…"
    caffeinate -dimsu mvn -q -B -f "$BACKEND_DIR/pom.xml" test \
        -Dtest=SalusLlmEvalHarness -Dsurefire.failIfNoSpecifiedTests=false \
        "${ARGS[@]}" \
        -DRECIPE_GENERATION_TOTAL_TIMEOUT_SECONDS=600 \
        -DRECIPE_GENERATION_INITIAL_TIMEOUT_SECONDS=300 \
        -DRECIPE_GENERATION_REPAIR_TIMEOUT_SECONDS=200 \
        -DOLLAMA_TIMEOUT_SECONDS=300 -DOLLAMA_RECIPE_TIMEOUT_SECONDS=300 \
        -DWEBCLIENT_RESPONSE_TIMEOUT_SECONDS=700 -DWEBCLIENT_READ_TIMEOUT_SECONDS=700 \
        >"$OUT_ROOT/run$i.log" 2>&1 || echo "  (게이트 없음이므로 비정상 종료도 계속 진행)"

    SUMMARY="$OUT_ROOT/run$i/latest.txt"
    if [[ -f "$SUMMARY" ]]; then
        head -1 "$SUMMARY" | sed 's/^/  /'
    else
        echo "  리포트 없음. 로그: $OUT_ROOT/run$i.log"
    fi
done

echo
python3 "$REPO_ROOT/scripts/eval-run-variance.py" "$OUT_ROOT" "${@:4}"
