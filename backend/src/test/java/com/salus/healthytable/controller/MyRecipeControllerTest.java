package com.salus.healthytable.controller;

import com.salus.healthytable.dto.MyRecipeDto;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.UserRecipeService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MyRecipeController} 테스트: 상태 코드와, 사용자를 언제나 인증 정보에서 꺼내는지 확인합니다.
 */
class MyRecipeControllerTest {

    private final UserRecipeService service = mock(UserRecipeService.class);
    private final AuthenticatedUserProvider authenticatedUserProvider = mock(AuthenticatedUserProvider.class);
    private final MyRecipeController controller = new MyRecipeController(service, authenticatedUserProvider);

    // 카드 저장: 새로 만들면 201, 이미 저장한 카드면 200입니다.
    @Test
    void saveFromCardReturns201ForNewAnd200ForExisting() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(3L);
        MyRecipeDto.FromCardRequest request = new MyRecipeDto.FromCardRequest("card-1");
        MyRecipeDto.Detail detail = detail();
        when(service.saveFromCard(3L, request))
                .thenReturn(new UserRecipeService.SaveResult(detail, true))
                .thenReturn(new UserRecipeService.SaveResult(detail, false));

        assertThat(controller.saveFromCard(request).getStatusCode().value()).isEqualTo(201);
        assertThat(controller.saveFromCard(request).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.saveFromCard(request).getBody()).isEqualTo(detail);
    }

    // 작성 201, 삭제 204, 목록·수정은 인증된 사용자 ID로만 서비스를 부릅니다.
    @Test
    void otherEndpointsUseAuthenticatedUser() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(3L);
        when(service.create(eq(3L), any())).thenReturn(detail());

        assertThat(controller.create(new MyRecipeDto.CreateRequest("김치찌개", null, null, null, null, null, null, null, null))
                .getStatusCode().value()).isEqualTo(201);
        assertThat(controller.delete(9L).getStatusCode().value()).isEqualTo(204);
        controller.list("cursor", 10);
        controller.get(9L);

        verify(service).delete(3L, 9L);
        verify(service).list(3L, "cursor", 10);
        verify(service).get(3L, 9L);
    }

    private static MyRecipeDto.Detail detail() {
        return new MyRecipeDto.Detail(1L, null, null, null, null, java.util.List.of(), null, 0, null, null);
    }
}
