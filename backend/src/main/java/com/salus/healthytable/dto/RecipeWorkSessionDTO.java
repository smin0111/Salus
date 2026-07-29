package com.salus.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    @Builder.Default
    private Map<String, Object> agentSession = new LinkedHashMap<>();
    private String status;
    private LocalDateTime updatedAt;
}
