package com.mychefai.healthytable.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mychefai.healthytable.domain.FridgeItem;
import com.mychefai.healthytable.repository.FridgeItemRepository;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import com.mychefai.healthytable.service.GeminiService;
import com.mychefai.healthytable.util.ExpiryDateCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fridge")
@RequiredArgsConstructor
public class FridgeController {

    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_QUANTITY_LENGTH = 80;
    private static final int MAX_CATEGORY_LENGTH = 80;
    private static final int MAX_SCAN_IMAGE_BASE64_LENGTH = 8 * 1024 * 1024;

    private final FridgeItemRepository fridgeItemRepository;
    private final GeminiService geminiService;
    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @GetMapping
    public List<FridgeItem> getFridgeItems() {
        Long userId = authenticatedUserProvider.requireUserId();
        return fridgeItemRepository.findByUserIdOrderByExpiryDate(userId);
    }

    @PostMapping
    public FridgeItem addFridgeItem(@RequestBody FridgeItem item) {
        Long userId = authenticatedUserProvider.requireUserId();
        normalizeFridgeItem(item, true);
        item.setId(null);
        item.setUserId(userId);
        return fridgeItemRepository.save(item);
    }

    @DeleteMapping("/{id}")
    public void deleteFridgeItem(@PathVariable Long id) {
        Long userId = authenticatedUserProvider.requireUserId();
        FridgeItem item = fridgeItemRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "냉장고 항목을 찾을 수 없습니다."));
        if (!userId.equals(item.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 냉장고 항목만 삭제할 수 있습니다.");
        }
        fridgeItemRepository.delete(item);
    }

    @PutMapping("/{id}")
    public FridgeItem updateFridgeItem(@PathVariable Long id,
            @RequestBody FridgeItem item) {
        Long userId = authenticatedUserProvider.requireUserId();
        normalizeFridgeItem(item, false);

        return fridgeItemRepository.findById(id)
                .filter(existingItem -> userId.equals(existingItem.getUserId()))
                .map(existingItem -> {
                    existingItem.setName(item.getName());
                    existingItem.setQuantity(item.getQuantity());
                    existingItem.setCategory(item.getCategory());
                    existingItem.setExpiryDate(item.getExpiryDate());
                    return fridgeItemRepository.save(existingItem);
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "냉장고 항목을 찾을 수 없습니다."));
    }

    @PatchMapping("/{id}/quantity")
    public FridgeItem adjustQuantity(@PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Long userId = authenticatedUserProvider.requireUserId();
        String quantityStr = normalizeOptional(
                body != null ? body.get("quantity") : null,
                "1개",
                MAX_QUANTITY_LENGTH,
                "수량은 80자 이하로 입력해 주세요.");

        return fridgeItemRepository.findById(id)
                .filter(item -> userId.equals(item.getUserId()))
                .map(item -> {
                    item.setQuantity(quantityStr);
                    return fridgeItemRepository.save(item);
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "냉장고 항목을 찾을 수 없습니다."));
    }

    @PostMapping("/scan")
    public Mono<List<Map<String, String>>> scanReceipt(@RequestBody Map<String, String> body) {
        authenticatedUserProvider.requireUserId();
        String base64Image = normalizeBase64Image(body != null ? body.get("image") : null);
        if (base64Image.isEmpty()) {
            return Mono.just(List.of());
        }
        if (base64Image.length() > MAX_SCAN_IMAGE_BASE64_LENGTH) {
            throw new IllegalArgumentException("영수증 이미지는 8MB 이하로 업로드해 주세요.");
        }

        // Spring WebFlux가 비동기 흐름을 올바르게 처리할 수 있도록 Mono 객체를 즉시 반환
        return geminiService.analyzeReceipt(base64Image)
                .map(this::parseScannedItems);
    }

    private void normalizeFridgeItem(FridgeItem item, boolean fillDefaultExpiryDate) {
        if (item == null) {
            throw new IllegalArgumentException("재료 이름을 입력해 주세요.");
        }
        item.setName(normalizeRequired(item.getName(), MAX_NAME_LENGTH, "재료 이름은 120자 이하로 입력해 주세요."));
        item.setQuantity(normalizeOptional(item.getQuantity(), "1개", MAX_QUANTITY_LENGTH, "수량은 80자 이하로 입력해 주세요."));
        item.setCategory(normalizeOptional(item.getCategory(), "기타", MAX_CATEGORY_LENGTH, "카테고리는 80자 이하로 입력해 주세요."));
        if (fillDefaultExpiryDate && item.getExpiryDate() == null) {
            item.setExpiryDate(ExpiryDateCalculator.calculateExpiryDate(item.getCategory(), clock));
        }
    }

    private String normalizeRequired(String value, int maxLength, String lengthMessage) {
        String normalized = normalizeText(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("재료 이름을 입력해 주세요.");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(lengthMessage);
        }
        return normalized;
    }

    private String normalizeOptional(String value, String fallback, int maxLength, String lengthMessage) {
        String normalized = normalizeText(value);
        if (normalized.isBlank()) {
            return fallback;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(lengthMessage);
        }
        return normalized;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String normalizeBase64Image(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private List<Map<String, String>> parseScannedItems(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return List.of();
        }

        try {
            List<Map<String, String>> parsed = objectMapper.readValue(
                    rawResponse,
                    new TypeReference<>() {
                    });
            List<Map<String, String>> items = new ArrayList<>();

            for (Map<String, String> item : parsed) {
                toScannedItem(item).ifPresent(items::add);
            }

            return items;
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private java.util.Optional<Map<String, String>> toScannedItem(Map<String, String> item) {
        if (item == null) {
            return java.util.Optional.empty();
        }

        try {
            Map<String, String> normalized = new LinkedHashMap<>();
            normalized.put("name", normalizeRequired(item.get("name"), MAX_NAME_LENGTH, "재료 이름은 120자 이하로 입력해 주세요."));
            normalized.put("quantity", normalizeOptional(item.get("quantity"), "1개", MAX_QUANTITY_LENGTH, "수량은 80자 이하로 입력해 주세요."));
            normalized.put("category", normalizeOptional(item.get("category"), "기타", MAX_CATEGORY_LENGTH, "카테고리는 80자 이하로 입력해 주세요."));
            return java.util.Optional.of(normalized);
        } catch (IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
    }
}
