package com.salus.healthytable.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.FridgeItem;
import com.salus.healthytable.repository.FridgeItemRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.GeminiService;
import com.salus.healthytable.util.ExpiryDateCalculator;
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

/**
 * 냉장고 재료 관리 API(/api/fridge)입니다.
 *
 * 재료 조회/추가/수정/삭제, 수량 변경, 영수증 사진 스캔(AI로 재료 목록 추출)을 제공합니다.
 * 모든 API는 로그인이 필요하며, 본인 소유의 재료만 수정/삭제할 수 있습니다.
 */
@RestController
@RequestMapping("/api/fridge")
@RequiredArgsConstructor
public class FridgeController {

    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_QUANTITY_LENGTH = 80;
    private static final int MAX_CATEGORY_LENGTH = 80;
    // Base64 문자열 기준 최대 약 8MB
    private static final int MAX_SCAN_IMAGE_BASE64_LENGTH = 8 * 1024 * 1024;

    private final FridgeItemRepository fridgeItemRepository;
    private final GeminiService geminiService;
    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 내 냉장고 재료를 유통기한이 가까운 순서로 조회합니다.
     */
    @GetMapping
    public List<FridgeItem> getFridgeItems() {
        Long userId = authenticatedUserProvider.requireUserId();
        return fridgeItemRepository.findByUserIdOrderByExpiryDate(userId);
    }

    /**
     * 재료를 추가합니다. 유통기한이 없으면 카테고리별 기본 유통기한으로 채웁니다.
     */
    @PostMapping
    public FridgeItem addFridgeItem(@RequestBody FridgeItem item) {
        Long userId = authenticatedUserProvider.requireUserId();
        normalizeFridgeItem(item, true);
        // 클라이언트가 id나 userId를 보내도 무시하고, 새 항목 + 현재 사용자 소유로 저장합니다.
        item.setId(null);
        item.setUserId(userId);
        return fridgeItemRepository.save(item);
    }

    /**
     * 재료를 삭제합니다. 다른 사용자의 재료이면 403 Forbidden입니다.
     */
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

    /**
     * 재료 정보를 수정합니다. 본인 재료가 아니면 filter에서 걸러져 404로 응답합니다.
     */
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

    /**
     * 재료 수량만 변경합니다. 요청 본문 예: {"quantity": "2개"}
     */
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

    /**
     * 영수증 이미지(Base64)를 AI(Gemini)로 분석해 재료 후보 목록을 반환합니다.
     * 여기서는 저장하지 않고, 사용자가 확인한 뒤 추가 API로 저장합니다.
     */
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

    // 재료 입력값을 정리합니다: 이름은 필수, 수량/카테고리는 비어 있으면 기본값("1개", "기타")으로 채웁니다.
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

    /**
     * AI 응답(JSON 배열 문자열)을 재료 목록으로 변환합니다.
     * AI 출력은 신뢰할 수 없으므로 JSON이 깨졌으면 빈 목록을 반환하고, 항목마다 다시 검증합니다.
     */
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

    // 항목 하나를 검증합니다. 이름이 없거나 너무 길면 예외 대신 빈 Optional을 반환해 해당 항목만 건너뜁니다.
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
