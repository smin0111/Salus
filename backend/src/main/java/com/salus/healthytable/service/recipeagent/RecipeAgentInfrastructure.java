package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.service.RecipeNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
class DefaultRecipeRequestPlanner {

    private final RecipeNormalizer recipeNormalizer;

    RecipeResearchPlan plan(String message, boolean useFridgeIngredients, boolean hasUserContext) {
        return plan(message, useFridgeIngredients, hasUserContext ? UserRecipeContext.empty(null) : null);
    }

    RecipeResearchPlan plan(String message, boolean useFridgeIngredients, UserRecipeContext context) {
        String normalizedMessage = message == null ? "" : message.trim();
        String dishName = recipeNormalizer.normalize(normalizedMessage);
        CreatorDish creatorDish = extractCreatorDish(normalizedMessage, dishName);
        if (!creatorDish.dishName().isBlank()) {
            dishName = creatorDish.dishName();
        }
        RecipeRequestMode mode = resolveMode(normalizedMessage);
        if (!creatorDish.creatorName().isBlank()) {
            mode = RecipeRequestMode.CREATOR_SPECIFIC;
        }
        boolean latest = normalizedMessage.contains("최신") || normalizedMessage.toLowerCase().contains("latest");
        List<String> searchQueries = searchQueries(dishName, creatorDish.creatorName(), normalizedMessage, useFridgeIngredients, context);
        return new RecipeResearchPlan(
                dishName,
                creatorDish.creatorName(),
                mode,
                latest,
                useFridgeIngredients,
                context != null,
                searchQueries,
                3,
                5);
    }

    List<String> extractExplicitExclusions(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        List<String> exclusions = new ArrayList<>();
        for (String marker : List.of("빼 주세요", "빼줘", "빼고", "빼", "제외", "말고", "없는", "안 들어간", "안들어간")) {
            int index = message.indexOf(marker);
            if (index <= 0) {
                continue;
            }
            String before = message.substring(0, index).trim();
            String candidate = before.replaceAll(".*\\s", "")
                    .replaceFirst("(은|는|을|를)$", "")
                    .trim();
            if (candidate.length() >= 2) {
                exclusions.add(candidate);
            }
        }
        return AgentText.distinct(exclusions);
    }

    private RecipeRequestMode resolveMode(String message) {
        String normalized = message.replaceAll("\\s+", "");
        if (normalized.contains("자세")) {
            return RecipeRequestMode.DETAIL;
        }
        if (normalized.contains("대신") || normalized.contains("대체")) {
            return RecipeRequestMode.SUBSTITUTE;
        }
        if (normalized.contains("빼고") || normalized.contains("제외") || normalized.contains("없는")) {
            return RecipeRequestMode.EXCLUDE;
        }
        if (normalized.contains("추천")) {
            return RecipeRequestMode.RECOMMEND;
        }
        return RecipeRequestMode.CREATE;
    }

    private List<String> searchQueries(
            String dishName,
            String creatorName,
            String originalMessage,
            boolean useFridgeIngredients,
            UserRecipeContext context) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String prefix = creatorName == null || creatorName.isBlank() ? "" : creatorName.trim() + " ";
        if (dishName != null && !dishName.isBlank()) {
            queries.add(prefix + dishName + " 레시피");
            queries.add(prefix + dishName + " 만드는 법");
        } else if (originalMessage != null && !originalMessage.isBlank()) {
            queries.add(originalMessage);
        }
        if (useFridgeIngredients && context != null) {
            fridgeSearchTerms(dishName, context).forEach(term -> {
                if (dishName != null && !dishName.isBlank()) {
                    queries.add(prefix + dishName + " " + term + " 레시피");
                } else {
                    queries.add(term + " 레시피");
                }
            });
        }
        return List.copyOf(queries);
    }

    private List<String> fridgeSearchTerms(String dishName, UserRecipeContext context) {
        if (context == null || context.fridgeIngredients().isEmpty()) {
            return List.of();
        }
        String normalizedDish = RecipeCandidate.normalize(dishName);
        return context.fridgeIngredients().stream()
                .filter(fridge -> fridge.name() != null && !fridge.name().isBlank())
                .filter(fridge -> {
                    String normalizedName = RecipeCandidate.normalize(fridge.name());
                    return normalizedDish.contains(normalizedName)
                            || (fridge.expirationDate() != null && !fridge.expirationDate().isAfter(java.time.LocalDate.now().plusDays(3)));
                })
                .map(FridgeIngredientContext::name)
                .distinct()
                .limit(2)
                .toList();
    }

    private CreatorDish extractCreatorDish(String message, String normalizedDish) {
        if (message == null || message.isBlank()) {
            return new CreatorDish("", normalizedDish == null ? "" : normalizedDish);
        }
        String cleaned = recipeNormalizer.normalize(message)
                .replaceAll("\\s+", " ")
                .trim();
        String[] tokens = cleaned.split("\\s+");
        if (tokens.length != 2 || isGenericRequestToken(tokens[0]) || !hasExplicitCreatorSignal(message, tokens[0])) {
            return new CreatorDish("", cleaned);
        }
        String creator = tokens[0].trim();
        String dish = cleaned.substring(creator.length()).trim();
        if (creator.length() < 2 || dish.length() < 2) {
            return new CreatorDish("", cleaned);
        }
        return new CreatorDish(creator, dish);
    }

    private boolean hasExplicitCreatorSignal(String message, String firstToken) {
        String token = firstToken == null ? "" : firstToken.trim();
        if (token.isBlank()) {
            return false;
        }
        return message.contains(token + "의 ")
                || token.startsWith("@")
                || token.contains("-")
                || token.matches(".*[A-Za-z].*");
    }

    private boolean isGenericRequestToken(String token) {
        String normalized = RecipeCandidate.normalize(token);
        return List.of("일반", "요즘", "최신", "유튜브", "냉장고", "오늘", "집밥").contains(normalized);
    }

    private record CreatorDish(String creatorName, String dishName) {
    }
}

@Service
@RequiredArgsConstructor
class InternalRecipeSourceDiscoveryAdapter implements RecipeSourceDiscoveryPort {

    private final RecipeRepository recipeRepository;

    @Override
    public List<RecipeSourceDocument> search(RecipeResearchPlan plan, UserRecipeContext context) {
        if (plan == null || plan.dishName() == null || plan.dishName().isBlank()) {
            return List.of();
        }
        return recipeRepository.findByTitleContaining(plan.dishName()).stream()
                .limit(plan.maxSources())
                .map(this::toDocument)
                .toList();
    }

    private RecipeSourceDocument toDocument(Recipe recipe) {
        String content = """
                title: %s
                description: %s
                ingredients:
                %s
                steps:
                %s
                """.formatted(
                nullToBlank(recipe.getTitle()),
                nullToBlank(recipe.getDescription()),
                String.join("\n", safeList(recipe.getIngredients())),
                String.join("\n", safeList(recipe.getSteps())));
        return new RecipeSourceDocument(
                "recipe:" + recipe.getId(),
                RecipeSourceType.INTERNAL_DB,
                recipe.getTitle(),
                "",
                "",
                content,
                recipe.getCreatedAt() == null ? LocalDateTime.now() : recipe.getCreatedAt(),
                0.95);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}

@Component
class RecipeEvidenceExtractor {

    List<RecipeSourceDocument> extract(List<RecipeSourceDocument> documents) {
        return documents == null ? List.of() : documents.stream()
                .filter(document -> document != null && document.content() != null && !document.content().isBlank())
                .limit(5)
                .toList();
    }
}

@Component
class RecipeCandidateBuilder {

    RecipeCandidate build(RecipeResearchPlan plan, List<RecipeSourceDocument> sources) {
        if (sources == null || sources.isEmpty()) {
            return RecipeCandidate.empty(plan == null ? "" : plan.dishName());
        }
        RecipeSourceDocument source = sources.get(0);
        ParsedRecipe parsed = parse(source.content());
        return new RecipeCandidate(
                blankToFallback(parsed.title(), source.title()),
                parsed.description(),
                parsed.ingredients(),
                parsed.steps(),
                null,
                null,
                null,
                parsed.coreIngredients().isEmpty() ? parsed.ingredients().stream().limit(4).toList() : parsed.coreIngredients(),
                parsed.optionalIngredients(),
                parsed.healthRiskTags());
    }

    private ParsedRecipe parse(String content) {
        List<String> ingredients = new ArrayList<>();
        List<String> steps = new ArrayList<>();
        List<String> core = new ArrayList<>();
        List<String> optional = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        String title = "";
        String description = "";
        String section = "";
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String lower = trimmed.toLowerCase();
            if (lower.startsWith("title:")) {
                title = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                continue;
            }
            if (lower.startsWith("description:")) {
                description = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                continue;
            }
            if (lower.startsWith("ingredients") || trimmed.equals("[재료]")) {
                section = "ingredients";
                continue;
            }
            if (lower.startsWith("steps") || trimmed.equals("[조리 순서]")) {
                section = "steps";
                continue;
            }
            if (lower.startsWith("core:")) {
                core.addAll(splitCsv(trimmed.substring(trimmed.indexOf(':') + 1)));
                continue;
            }
            if (lower.startsWith("optional:")) {
                optional.addAll(splitCsv(trimmed.substring(trimmed.indexOf(':') + 1)));
                continue;
            }
            if (lower.startsWith("risk:")) {
                risks.addAll(splitCsv(trimmed.substring(trimmed.indexOf(':') + 1)));
                continue;
            }
            String value = trimmed.replaceFirst("^[-*]\\s*", "").replaceFirst("^\\d+[.)]\\s*", "").trim();
            if ("ingredients".equals(section)) {
                ingredients.add(value);
            } else if ("steps".equals(section)) {
                steps.add(value);
            }
        }
        return new ParsedRecipe(title, description, AgentText.distinct(ingredients), AgentText.distinct(steps),
                AgentText.distinct(core), AgentText.distinct(optional), AgentText.distinct(risks));
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(",")).stream().map(String::trim).filter(token -> !token.isBlank()).toList();
    }

    private String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record ParsedRecipe(
            String title,
            String description,
            List<String> ingredients,
            List<String> steps,
            List<String> coreIngredients,
            List<String> optionalIngredients,
            List<String> healthRiskTags
    ) {
    }
}
