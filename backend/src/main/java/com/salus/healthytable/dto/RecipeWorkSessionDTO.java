package com.salus.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecipeWorkSessionDTO {
    private Long userId;
    private Long chatSessionId;
    private String lastRecommendation;
    @Builder.Default
    private List<String> modifiers = new ArrayList<>();
    private String status;
    private LocalDateTime updatedAt;
}
