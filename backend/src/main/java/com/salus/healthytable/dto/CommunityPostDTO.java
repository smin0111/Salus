package com.salus.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 커뮤니티 게시글 응답 DTO입니다.
 * 게시글 내용에 작성자 이름, 좋아요 수, 댓글 수, 현재 사용자의 좋아요 여부를 더해 내려 줍니다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommunityPostDTO {
    private Long id;
    private Long userId;
    private String userName;
    private String title;
    private String content;
    private List<String> ingredients;
    private List<String> steps;
    private List<String> tags;
    private String imageUrl;
    private Long likeCount;
    private Long commentCount;
    private Boolean isLikedByCurrentUser;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
