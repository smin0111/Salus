package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.service.GeminiService;
import lombok.Data;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final GeminiService geminiService;

    public RecipeController(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    @PostMapping("/recommend")
    public Mono<String> recommendRecipe(@RequestBody RecommendationRequest request) {
        String healthContext = request.getHealthContext() != null ? request.getHealthContext() : "None";
        return geminiService.getRecipeRecommendation(request.getIngredients(), healthContext);
    }

    @Data
    public static class RecommendationRequest {
        private List<String> ingredients;
        private String healthContext;
    }
}
