package com.salus.healthytable.service.recipeagent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recipe Agent 세션에 저장된 출처 종류 복원 테스트입니다.
 */
class RecipeAgentSourceTypeTest {

    // 알 수 없거나 비어 있는 출처 종류는 내부 DB 수준으로 신뢰하지 않고 가장 낮은 GENERAL_WEB으로 복원해야 합니다.
    @Test
    void unknownPersistedSourceIsNeverPromotedToInternalDatabaseTrust() {
        RecipeAgentOrchestrator orchestrator = new RecipeAgentOrchestrator(
                null, null, null, null, null, null, null, null, null, null, null);

        assertThat(orchestrator.sourceType("UNRECOGNIZED_SOURCE"))
                .isEqualTo(RecipeSourceType.GENERAL_WEB);
        assertThat(orchestrator.sourceType(null))
                .isEqualTo(RecipeSourceType.GENERAL_WEB);
    }
}
