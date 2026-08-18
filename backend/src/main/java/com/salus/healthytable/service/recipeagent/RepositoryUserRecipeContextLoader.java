package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.domain.FridgeItem;
import com.salus.healthytable.domain.HealthProfile;
import com.salus.healthytable.repository.FridgeItemRepository;
import com.salus.healthytable.repository.HealthProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
class RepositoryUserRecipeContextLoader implements UserRecipeContextLoader {

    private static final Pattern QUANTITY_PATTERN = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)?\\s*([^\\d\\s]+)?.*$");

    private final HealthProfileRepository healthProfileRepository;
    private final FridgeItemRepository fridgeItemRepository;

    @Override
    public UserRecipeContext load(Long userId) {
        return loadWithStatus(userId).context();
    }

    @Override
    public UserRecipeContextLoadResult loadWithStatus(Long userId) {
        if (userId == null) {
            return new UserRecipeContextLoadResult(
                    UserRecipeContext.empty(null),
                    UserRecipeContextLoadStatus.NOT_REGISTERED);
        }

        Optional<HealthProfile> profile = Optional.empty();
        List<FridgeIngredientContext> fridgeIngredients = List.of();
        boolean profileFailed = false;
        boolean fridgeFailed = false;
        try {
            profile = healthProfileRepository.findByUserId(userId);
        } catch (Exception e) {
            profileFailed = true;
        }
        try {
            fridgeIngredients = fridgeItemRepository.findByUserIdOrderByExpiryDate(userId)
                    .stream()
                    .map(this::toContext)
                    .filter(item -> item.name() != null && !item.name().isBlank())
                    .toList();
        } catch (Exception e) {
            fridgeFailed = true;
        }

        UserRecipeContext context = new UserRecipeContext(
                userId,
                profile.map(HealthProfile::getAllergies).orElse(List.of()),
                profile.map(HealthProfile::getChronicConditions).orElse(List.of()),
                profile.map(HealthProfile::getDietaryRestrictions).orElse(List.of()),
                profile.map(HealthProfile::getMedications).orElse(List.of()),
                profile.map(HealthProfile::getGoals).orElse(List.of()),
                fridgeIngredients,
                List.of());
        UserRecipeContextLoadStatus status;
        if (profileFailed && fridgeFailed) {
            status = UserRecipeContextLoadStatus.LOAD_FAILED;
        } else if (profileFailed || fridgeFailed) {
            status = UserRecipeContextLoadStatus.PARTIALLY_LOADED;
        } else if (profile.isEmpty() && fridgeIngredients.isEmpty()) {
            status = UserRecipeContextLoadStatus.NOT_REGISTERED;
        } else {
            status = UserRecipeContextLoadStatus.LOADED;
        }
        return new UserRecipeContextLoadResult(
                context,
                status,
                profileFailed ? ContextSectionLoadStatus.LOAD_FAILED : ContextSectionLoadStatus.LOADED,
                fridgeFailed ? ContextSectionLoadStatus.LOAD_FAILED : ContextSectionLoadStatus.LOADED);
    }

    private FridgeIngredientContext toContext(FridgeItem item) {
        ParsedQuantity parsed = parseQuantity(item.getQuantity());
        return new FridgeIngredientContext(
                item.getName(),
                parsed.amount(),
                parsed.unit(),
                item.getExpiryDate());
    }

    private ParsedQuantity parseQuantity(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedQuantity(null, null);
        }
        Matcher matcher = QUANTITY_PATTERN.matcher(raw.trim());
        if (!matcher.matches()) {
            return new ParsedQuantity(null, raw.trim());
        }
        Double amount = matcher.group(1) == null ? null : Double.valueOf(matcher.group(1));
        String unit = matcher.group(2) == null ? null : matcher.group(2).trim();
        return new ParsedQuantity(amount, unit);
    }

    private record ParsedQuantity(Double amount, String unit) {
    }
}
