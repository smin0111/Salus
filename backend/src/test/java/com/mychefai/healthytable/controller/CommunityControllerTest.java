package com.mychefai.healthytable.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mychefai.healthytable.dto.CreateCommentRequestDTO;
import com.mychefai.healthytable.dto.CreatePostRequestDTO;
import com.mychefai.healthytable.dto.RecipeShareRequestDTO;
import com.mychefai.healthytable.dto.UpdatePostRequestDTO;
import com.mychefai.healthytable.exception.GlobalExceptionHandler;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import com.mychefai.healthytable.service.CommunityPostService;
import com.mychefai.healthytable.service.CommunityService;
import com.mychefai.healthytable.service.PostCommentService;
import com.mychefai.healthytable.service.RecommendationService;
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

        mockMvc.perform(post("/api/community/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("제목을 입력해 주세요."));

        verifyNoInteractions(communityPostService);
    }

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

    @Test
    void createPostRejectsNullBodyBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/community/posts")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 본문 JSON 형식이 올바르지 않습니다."));

        verifyNoInteractions(communityPostService);
    }

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

        mockMvc.perform(post("/api/community/share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("공유할 레시피를 선택해 주세요."));

        verifyNoInteractions(communityService);
    }

    @Test
    void createCommentRejectsNullBodyBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/community/posts/7/comments")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 본문 JSON 형식이 올바르지 않습니다."));

        verifyNoInteractions(postCommentService);
    }

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

    @Test
    void updateCommentRejectsNullBodyBeforeServiceCall() throws Exception {
        mockMvc.perform(put("/api/community/comments/3")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 본문 JSON 형식이 올바르지 않습니다."));

        verifyNoInteractions(postCommentService);
    }

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

    @Test
    void getPopularPostsRejectsInvalidLimitBeforeServiceCall() throws Exception {
        mockMvc.perform(get("/api/community/posts/popular")
                        .param("limit", "0")
                        .param("timeframe", "weekly"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("조회 개수는 1부터 50 사이로 입력해 주세요."));

        verifyNoInteractions(communityPostService);
    }

    @Test
    void getPopularPostsRejectsInvalidTimeframeBeforeServiceCall() throws Exception {
        mockMvc.perform(get("/api/community/posts/popular")
                        .param("limit", "10")
                        .param("timeframe", "yearly"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("조회 기간은 daily, weekly, monthly, all 중 하나로 입력해 주세요."));

        verifyNoInteractions(communityPostService);
    }

    @Test
    void searchPostsNormalizesKeyword() throws Exception {
        when(authenticatedUserProvider.getCurrentUserId()).thenReturn(Optional.of(1L));
        when(communityPostService.searchPosts("수박", 1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/community/posts/search")
                        .param("keyword", "  수박  "))
                .andExpect(status().isOk());

        verify(communityPostService).searchPosts("수박", 1L);
    }

    @Test
    void searchPostsRejectsBlankKeywordBeforeServiceCall() throws Exception {
        mockMvc.perform(get("/api/community/posts/search")
                        .param("keyword", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("검색어를 입력해 주세요."));

        verifyNoInteractions(communityPostService);
    }

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
