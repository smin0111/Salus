package com.salus.healthytable.service.recipeagent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeAgentSourceTypeTest {

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
