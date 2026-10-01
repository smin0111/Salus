#!/usr/bin/env bash
#
# Salus LLM Evaluation Harness — 한 번의 명령으로 실행하는 진입점.
#
#   ./eval.sh                     # Ollama가 떠 있으면 실제 모델 평가, 없으면 녹화 응답 회귀 검사
#   ./eval.sh --mode replay       # Ollama 없이 결정적으로 실행 (CI용)
#   ./eval.sh --models qwen3:8b,gemma2:latest   # 여러 모델을 같은 조건에서 비교
#   ./eval.sh --mode live --repeat 3
#   ./eval.sh --suite chat_reply --case chat-basic-recipe-question
#
# 판정은 하네스가 새로 만들지 않는다. 프로덕션 프롬프트·호출·검증기·알레르겐 사전을
# 그대로 태우고 점수만 모은다. 자세한 설계는 docs/llm-eval-harness.md 참고.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$REPO_ROOT/backend"
REPORT_DIR="$BACKEND_DIR/target/eval"

MVN_ARGS=()

usage() {
    # 파일 상단 주석 블록(2행부터 첫 비주석 행 전까지)을 그대로 도움말로 쓴다.
    sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^#\{1,2\} \{0,1\}//'
    cat <<'USAGE'

옵션:
  --mode <auto|live|replay>   실행 모드 (기본 auto)
  --suite <a,b>               스위트 필터 (recipe_generation, chat_reply)
  --case <id,id>              케이스 ID 필터
  --cases <classpath>         데이터셋 경로 (기본 /eval/cases/salus-core.jsonl)
  --repeat <n>                케이스당 반복 횟수 (기본 1, 모델 편차 측정용)
  --model <name>              단일 모델 덮어쓰기 (예: qwen3:8b)
  --models <a,b,c>            여러 모델을 동일 조건에서 비교 실행
  --judge-model <name>        Judge 교차평가 모델 (프로덕션 판정과 별도 필드로만 기록)
  --no-warmup                 모델별 워밍업 호출 생략
  --repair <true|false>       실패 초안 1회 repair 재호출 포함 여부 (기본 live=true)
  --min-pass-rate <0~1>       통과율 게이트 (기본 replay=1.0, live=0)
  --no-gate                   게이트 없이 지표만 수집
  --out <dir>                 리포트 출력 디렉터리 (기본 backend/target/eval)
  -h, --help                  이 도움말
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --mode)           MVN_ARGS+=("-Dsalus.eval.mode=$2"); shift 2 ;;
        --suite)          MVN_ARGS+=("-Dsalus.eval.suites=$2"); shift 2 ;;
        --case)           MVN_ARGS+=("-Dsalus.eval.only=$2"); shift 2 ;;
        --cases)          MVN_ARGS+=("-Dsalus.eval.cases=$2"); shift 2 ;;
        --repeat)         MVN_ARGS+=("-Dsalus.eval.repeat=$2"); shift 2 ;;
        --model)          MVN_ARGS+=("-DOLLAMA_MODEL=$2"); shift 2 ;;
        --models)         MVN_ARGS+=("-Dsalus.eval.models=$2"); shift 2 ;;
        --judge-model)    MVN_ARGS+=("-Dsalus.eval.judge-model=$2"); shift 2 ;;
        --no-warmup)      MVN_ARGS+=("-Dsalus.eval.warmup=false"); shift ;;
        --repair)         MVN_ARGS+=("-Dsalus.eval.repair=$2"); shift 2 ;;
        --min-pass-rate)  MVN_ARGS+=("-Dsalus.eval.min-pass-rate=$2"); shift 2 ;;
        --no-gate)        MVN_ARGS+=("-Dsalus.eval.gate=false"); shift ;;
        --out)            REPORT_DIR="$2"; MVN_ARGS+=("-Dsalus.eval.out=$2"); shift 2 ;;
        -h|--help)        usage; exit 0 ;;
        *) echo "알 수 없는 옵션: $1" >&2; usage; exit 2 ;;
    esac
done

# Maven Enforcer가 JDK 17만 허용한다. macOS에서 다른 JDK가 기본일 때 조용히 깨지지 않도록 먼저 맞춘다.
if [[ -z "${JAVA_HOME:-}" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
    if JAVA_17_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null)"; then
        export JAVA_HOME="$JAVA_17_HOME"
    fi
fi

echo "Salus LLM Evaluation Harness 실행 중… (첫 실행은 테스트 컴파일 때문에 조금 걸립니다)"

BUILD_LOG="$BACKEND_DIR/target/eval-run.log"
mkdir -p "$BACKEND_DIR/target"

# Maven·Spring 로그는 로그 파일로 보내고 터미널에는 평가 요약만 남긴다.
set +e
mvn -q -B -f "$BACKEND_DIR/pom.xml" test \
    -Dtest=SalusLlmEvalHarness \
    -Dsurefire.failIfNoSpecifiedTests=false \
    "${MVN_ARGS[@]}" >"$BUILD_LOG" 2>&1
STATUS=$?
set -e

SUMMARY="$REPORT_DIR/latest.txt"
if [[ -f "$SUMMARY" ]]; then
    echo
    cat "$SUMMARY"
    echo
    echo "상세 리포트:      $REPORT_DIR/latest.md"
    echo "원본 JSON:        $REPORT_DIR/latest.json"
    echo "평면 레코드:      $REPORT_DIR/latest-records.json"
    echo "모델 응답 원문:   $REPORT_DIR/raw/<model>/<caseId>__iter<n>.txt"
else
    echo "리포트가 생성되지 않았습니다. 빌드 로그 마지막 40줄:" >&2
    tail -40 "$BUILD_LOG" >&2
fi

if [[ $STATUS -ne 0 ]]; then
    echo
    echo "게이트 실패 또는 실행 오류입니다. 전체 로그: $BUILD_LOG" >&2
fi

exit $STATUS
