package com.salus.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

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
