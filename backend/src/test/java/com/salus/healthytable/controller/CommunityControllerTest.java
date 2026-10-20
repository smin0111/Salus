package com.salus.healthytable.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.dto.CreateCommentRequestDTO;
import com.salus.healthytable.dto.CreatePostRequestDTO;
import com.salus.healthytable.dto.RecipeShareRequestDTO;
import com.salus.healthytable.dto.UpdatePostRequestDTO;
import com.salus.healthytable.exception.GlobalExceptionHandler;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.CommunityPostService;
import com.salus.healthytable.service.CommunityService;
import com.salus.healthytable.service.PostCommentService;
import com.salus.healthytable.service.RecommendationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link CommunityController} 테스트입니다. MockMvc(standalone)로 요청 검증 흐름을 확인합니다.
 */
class CommunityControllerTest {

    private CommunityService communityService;
    private CommunityPostService communityPostService;
    private PostCommentService postCommentService;
    private RecommendationService recommendationService;
    private AuthenticatedUserProvider authenticatedUserProvider;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        communityService = mock(CommunityService.class);
        communityPostService = mock(CommunityPostService.class);
        postCommentService = mock(PostCommentService.class);
        recommendationService = mock(RecommendationService.class);
        authenticatedUserProvider = mock(AuthenticatedUserProvider.class);

        CommunityController controller = new CommunityController(
                communityService,
                communityPostService,
                postCommentService,
                recommendationService,
                authenticatedUserProvider);

        // 단순 메서드 호출 테스트가 아니라 MockMvc로 Spring MVC 요청 흐름을 태웁니다.
        // 그래야 @Valid, JSON 변환, ControllerAdvice가 실제 HTTP 요청처럼 동작하는지 검증할 수 있습니다.
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(
                        new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()
                )
                .build();

        objectMapper = new ObjectMapper();
    }

    @Test
    void createPostWithoutTitleThrowsValidationException() throws Exception {
        CreatePostRequestDTO request = new CreatePostRequestDTO();
        request.setContent("본문입니다.");
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);

        // 제목 누락은 Service까지 내려가기 전에 Bean Validation에서 400으로 막혀야 합니다.
        // 이 테스트가 깨지면 Controller 검증 책임이 다시 수동 코드나 Service로 새고 있다는 신호입니다.
        mockMvc.perform(post("/api/community/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("제목을 입력해 주세요."));

        verifyNoInteractions(communityPostService);
    }

    // 게시글 작성 시 제목/내용 공백을 정리하고 현재 사용자 ID로 저장해야 합니다.
    @Test
    void createPostNormalizesContentAndUsesCurrentUser() throws Exception {
        CreatePostRequestDTO request = new CreatePostRequestDTO();
        request.setTitle("  제목  ");
        request.setContent("  본문입니다.  ");
        request.setUserId(999L);
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);

        mockMvc.perform(post("/api/community/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().string("게시글이 작성되었습니다."));

        ArgumentCaptor<CreatePostRequestDTO> captor = ArgumentCaptor.forClass(CreatePostRequestDTO.class);
        verify(communityPostService).createPost(captor.capture());
        CreatePostRequestDTO saved = captor.getValue();

        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getTitle()).isEqualTo("제목");
        assertThat(saved.getContent()).isEqualTo("본문입니다.");
    }

    // 요청 본문이 없으면 서비스 호출 전에 400이어야 합니다.
    @Test
    void createPostRejectsNullBodyBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/community/posts")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 본문 JSON 형식이 올바르지 않습니다."));

        verifyNoInteractions(communityPostService);
    }

    // 레시피 공유 시 공개 범위를 대문자로 정규화하고 메시지 공백을 정리해야 합니다.
    @Test
    void shareRecipeNormalizesVisibilityAndMessage() throws Exception {
        RecipeShareRequestDTO request = new RecipeShareRequestDTO();
        request.setRecipeId(7L);
        request.setMessage("  공유합니다  ");
        request.setVisibility(" private ");
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);

        mockMvc.perform(post("/api/community/share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().string("레시피가 공유되었습니다."));

        verify(communityService).shareRecipe(1L, 7L, "공유합니다", "PRIVATE");
    }

    @Test
    void shareRecipeRejectsMissingRecipeIdBeforeServiceCall() throws Exception {
        RecipeShareRequestDTO request = new RecipeShareRequestDTO();
        request.setMessage("공유합니다");

        // 필수 recipeId가 없을 때 shareRecipe가 호출되지 않는지 함께 확인합니다.
        // 실패 요청이 비즈니스 로직을 실행하지 않는다는 점이 Controller 검증 테스트의 핵심입니다.
        mockMvc.perform(post("/api/community/share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("공유할 레시피를 선택해 주세요."));

        verifyNoInteractions(communityService);
    }

    // 댓글 작성 요청 본문이 없으면 400이어야 합니다.
    @Test
    void createCommentRejectsNullBodyBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/community/posts/7/comments")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 본문 JSON 형식이 올바르지 않습니다."));

        verifyNoInteractions(postCommentService);
    }

    // 댓글 작성 시 내용 공백을 정리하고 현재 사용자 ID를 사용해야 합니다.
    @Test
    void createCommentNormalizesContentAndUsesCurrentUser() throws Exception {
        CreateCommentRequestDTO request = new CreateCommentRequestDTO();
        request.setUserId(999L);
        request.setContent("  댓글입니다.  ");
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);

        mockMvc.perform(post("/api/community/posts/7/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().string("댓글이 작성되었습니다."));

        ArgumentCaptor<CreateCommentRequestDTO> captor = ArgumentCaptor.forClass(CreateCommentRequestDTO.class);
        verify(postCommentService).createComment(eq(7L), captor.capture());
        CreateCommentRequestDTO saved = captor.getValue();

        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getContent()).isEqualTo("댓글입니다.");
    }

    // 댓글 수정 요청 본문이 없으면 400이어야 합니다.
    @Test
    void updateCommentRejectsNullBodyBeforeServiceCall() throws Exception {
        mockMvc.perform(put("/api/community/comments/3")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 본문 JSON 형식이 올바르지 않습니다."));

        verifyNoInteractions(postCommentService);
    }

    // 댓글 수정 내용의 공백을 정리해야 합니다.
    @Test
    void updateCommentNormalizesContent() throws Exception {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);

        mockMvc.perform(put("/api/community/comments/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "  수정 댓글  "))))
                .andExpect(status().isOk())
                .andExpect(content().string("댓글이 수정되었습니다."));

        verify(postCommentService).updateComment(3L, 1L, "수정 댓글");
    }

    // 인기글 조회 기간 값을 소문자로 정규화해야 합니다.
    @Test
    void getPopularPostsNormalizesTimeframe() throws Exception {
        when(authenticatedUserProvider.getCurrentUserId()).thenReturn(Optional.of(1L));
        when(communityPostService.getPopularPosts(1L, 10, "weekly")).thenReturn(List.of());

        mockMvc.perform(get("/api/community/posts/popular")
                        .param("limit", "10")
                        .param("timeframe", " WEEKLY "))
                .andExpect(status().isOk());

        verify(communityPostService).getPopularPosts(1L, 10, "weekly");
    }

    // 잘못된 조회 개수는 서비스 호출 전에 400이어야 합니다.
    @Test
    void getPopularPostsRejectsInvalidLimitBeforeServiceCall() throws Exception {
        mockMvc.perform(get("/api/community/posts/popular")
                        .param("limit", "0")
                        .param("timeframe", "weekly"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("조회 개수는 1부터 50 사이로 입력해 주세요."));

        verifyNoInteractions(communityPostService);
    }

    // 잘못된 조회 기간은 서비스 호출 전에 400이어야 합니다.
    @Test
    void getPopularPostsRejectsInvalidTimeframeBeforeServiceCall() throws Exception {
        mockMvc.perform(get("/api/community/posts/popular")
                        .param("limit", "10")
                        .param("timeframe", "yearly"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("조회 기간은 daily, weekly, monthly, all 중 하나로 입력해 주세요."));

        verifyNoInteractions(communityPostService);
    }

    // 검색어 앞뒤 공백을 정리해야 합니다.
    @Test
    void searchPostsNormalizesKeyword() throws Exception {
        when(authenticatedUserProvider.getCurrentUserId()).thenReturn(Optional.of(1L));
        when(communityPostService.searchPosts("수박", 1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/community/posts/search")
                        .param("keyword", "  수박  "))
                .andExpect(status().isOk());

        verify(communityPostService).searchPosts("수박", 1L);
    }

    // 빈 검색어는 서비스 호출 전에 400이어야 합니다.
    @Test
    void searchPostsRejectsBlankKeywordBeforeServiceCall() throws Exception {
        mockMvc.perform(get("/api/community/posts/search")
                        .param("keyword", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("검색어를 입력해 주세요."));

        verifyNoInteractions(communityPostService);
    }

    // 서비스의 404 예외는 그대로 404 JSON 응답이 되어야 합니다.
    @Test
    void toggleLikePropagatesServiceNotFoundException() throws Exception {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(communityPostService.toggleLike(99L, 1L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."));

        mockMvc.perform(post("/api/community/posts/99/like"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("게시글을 찾을 수 없습니다."));
    }
}
