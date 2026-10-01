#!/usr/bin/env bash
#
# LLM 평가 전용 worktree를 만들고, 레시피·평가 관련 작업 파일만 복사합니다.
#
#   ./scripts/eval-worktree-sync.sh            # worktree가 없으면 만들고 동기화
#
# 메인 작업 트리에서 다른 작업(예: 소셜 로그인)이 mvn clean을 돌리면 backend/target의 평가
# 결과가 지워지고, 평가 도중 클래스가 다시 컴파일돼 결과가 오염될 수 있습니다(2026-09-30 실제 발생).
# 평가는 이 worktree에서만 돌리고, 결과도 이 worktree의 backend/target에 쌓습니다.
#
# worktree는 커밋된 HEAD에서 detached로 만들며 브랜치를 만들지 않습니다. 아래 목록에 없는
# 미커밋 변경(다른 작업의 파일)은 복사하지 않습니다. 레시피 파이프라인에 새 파일을 추가하면
# 목록에도 추가하세요.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKTREE="${EVAL_WORKTREE:-$REPO_ROOT/../Salus-recipe-eval}"
MAIN=backend/src/main/java/com/salus/healthytable/service
TEST=backend/src/test/java/com/salus/healthytable

FILES=(
    "$MAIN/ChatSafetyContextService.java"
    "$MAIN/ChatService.java"
    "$MAIN/FallbackSearchEngine.java"
    "$MAIN/OllamaRecipeGenerationClient.java"
    "$MAIN/RecipeDraftValidator.java"
    "$MAIN/RecipeGenerationClient.java"
    "$MAIN/RecipeGenerationCoordinator.java"
    "$MAIN/RecipePromptFactory.java"
    "$MAIN/SearxngSearchEngine.java"
    "$MAIN/recipeagent/RecipeAgentOrchestrator.java"
    backend/src/main/resources/application.properties
    "$TEST/eval/EvalGrader.java"
    "$TEST/eval/EvalRun.java"
    "$TEST/eval/SalusLlmEvalHarness.java"
    "$TEST/service/ChatSafetyContextServiceTest.java"
    "$TEST/service/ChatServiceSafetyTest.java"
    "$TEST/service/FallbackSearchEngineTest.java"
    "$TEST/service/RecipeDraftValidatorTest.java"
    "$TEST/service/RecipeGenerationCoordinatorTimeoutTest.java"
    "$TEST/service/RecipePromptFactoryTest.java"
    "$TEST/service/recipeagent/RecipeAgentPersonalizationTest.java"
    scripts/eval-ollama-metrics.py
    scripts/eval-order-experiment.py
    scripts/eval-repeat-runs.sh
    scripts/eval-run-variance.py
    scripts/eval-worktree-sync.sh
    # 참조 테스트(CrossContact/IngredientReferenceTest)가 읽는 미추적 원본 워크북
    docs/reference/Salus_Strict_Web_Reference_20_2026-09-09.xlsx
)

if [[ ! -d "$WORKTREE" ]]; then
    git -C "$REPO_ROOT" worktree add --detach "$WORKTREE" HEAD
fi

changed=0
for f in "${FILES[@]}"; do
    if [[ ! -f "$REPO_ROOT/$f" ]]; then
        echo "없음(건너뜀): $f"
        continue
    fi
    mkdir -p "$(dirname "$WORKTREE/$f")"
    if ! cmp -s "$REPO_ROOT/$f" "$WORKTREE/$f"; then
        cp "$REPO_ROOT/$f" "$WORKTREE/$f"
        echo "복사: $f"
        changed=$((changed + 1))
    fi
done
echo "worktree: $WORKTREE (변경 ${changed}개)"
