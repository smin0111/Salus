package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.dto.RecipeWorkSessionDTO;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 직전에 보여 준 승인 레시피의 인분·맵기 조정({@link ChatFollowUpService#buildApprovedRecipeAdjustment})이
 * 다른 요리 요청을 조정으로 오인하지 않는지 확인합니다.
 */
class ChatFollowUpApprovedAdjustmentTest {

    private final RecipeWorkSessionService workSessions = mock(RecipeWorkSessionService.class);
    private final ApprovedRecipeService approvedRecipeService = new ApprovedRecipeService(
            mock(RecipeRepository.class),
            mock(JdbcTemplate.class),
            new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneId.of("Asia/Seoul")));
    private final ChatFollowUpService followUp = new ChatFollowUpService(
            mock(LlmService.class), workSessions, mock(MealLogService.class), mock(UserRepository.class),
            mock(ChatSessionService.class), mock(RecipeGenerationCoordinator.class), mock(RecipeResponseSanitizer.class),
            mock(ChatRequestParser.class), mock(RecipeReplyParser.class), mock(ChatSafetyContextService.class),
            approvedRecipeService, Clock.systemDefaultZone());

    // 직전 레시피가 김치찌개여도 "불닭계란말이 레시피"는 새 요리 요청입니다. 아주 맵게 조정한 김치찌개를 내면 안 됩니다.
    @Test
    void newDishRequestContainingSpiceWordIsNotAnAdjustment() {
        ChatSession session = new ChatSession();
        session.setId(7L);

        Optional<?> response = followUp.buildApprovedRecipeAdjustment(1L, session, "불닭계란말이 레시피 알려줘");

        assertThat(response).isEmpty();
        verify(workSessions, never()).find(anyLong(), anyLong());
    }

    // 조정 표현만 있으면 직전 레시피를 찾아 조정 단계로 넘어갑니다(작업 세션이 없으면 다음 단계로 넘김).
    @Test
    void adjustmentOnlyMessageLooksUpPreviousRecipe() {
        ChatSession session = new ChatSession();
        session.setId(7L);
        when(workSessions.find(1L, 7L)).thenReturn(Optional.of(RecipeWorkSessionDTO.builder().build()));

        followUp.buildApprovedRecipeAdjustment(1L, session, "3인분으로 바꿔줘");

        verify(workSessions).find(1L, 7L);
    }
}
