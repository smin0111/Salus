package com.salus.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 게시글 좋아요 요청 DTO입니다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LikeRequestDTO {
    private Long userId;
}
