package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.FridgeItem;
import com.mychefai.healthytable.repository.FridgeRepository;
import com.mychefai.healthytable.security.JwtTokenProvider;
import com.mychefai.healthytable.service.GeminiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fridge")
@RequiredArgsConstructor
public class FridgeController {

    private final FridgeRepository fridgeRepository;
    private final GeminiService geminiService;
    private final JwtTokenProvider jwtTokenProvider;

    @GetMapping
    public List<FridgeItem> getFridgeItems(@RequestHeader("Authorization") String token) {
        Long userId = Long.valueOf(jwtTokenProvider.getUserId(token.replace("Bearer ", "")));
        return fridgeRepository.findByUserIdOrderByExpiryDateAsc(userId);
    }

    @PostMapping
    public FridgeItem addFridgeItem(@RequestHeader("Authorization") String token, @RequestBody FridgeItem item) {
        Long userId = Long.valueOf(jwtTokenProvider.getUserId(token.replace("Bearer ", "")));
        item.setUserId(userId);
        return fridgeRepository.save(item);
    }

    @DeleteMapping("/{id}")
    public void deleteFridgeItem(@PathVariable Long id, @RequestHeader("Authorization") String token) {
        Long userId = Long.valueOf(jwtTokenProvider.getUserId(token.replace("Bearer ", "")));
        FridgeItem item = fridgeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "냉장고 항목을 찾을 수 없습니다."));
        if (!item.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 냉장고 항목만 삭제할 수 있습니다.");
        }
        fridgeRepository.delete(item);
    }

    @PutMapping("/{id}")
    public FridgeItem updateFridgeItem(@PathVariable Long id,
            @RequestHeader("Authorization") String token,
            @RequestBody FridgeItem item) {
        Long userId = Long.valueOf(jwtTokenProvider.getUserId(token.replace("Bearer ", "")));

        return fridgeRepository.findById(id)
                .filter(existingItem -> existingItem.getUserId().equals(userId))
                .map(existingItem -> {
                    existingItem.setName(item.getName());
                    existingItem.setQuantity(item.getQuantity());
                    existingItem.setCategory(item.getCategory());
                    existingItem.setExpiryDate(item.getExpiryDate());
                    return fridgeRepository.save(existingItem);
                })
                .orElseThrow(() -> new RuntimeException("Item not found or unauthorized"));
    }

    @PatchMapping("/{id}/quantity")
    public FridgeItem adjustQuantity(@PathVariable Long id,
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, String> body) {
        Long userId = Long.valueOf(jwtTokenProvider.getUserId(token.replace("Bearer ", "")));
        String quantityStr = body.get("quantity");

        return fridgeRepository.findById(id)
                .filter(item -> item.getUserId().equals(userId))
                .map(item -> {
                    item.setQuantity(quantityStr);
                    return fridgeRepository.save(item);
                })
                .orElseThrow(() -> new RuntimeException("Item not found or unauthorized"));
    }

    @PostMapping("/scan")
    public Mono<String> scanReceipt(@RequestHeader("Authorization") String token,
            @RequestBody Map<String, String> body) {
        String base64Image = body.get("image");
        if (base64Image == null || base64Image.isEmpty()) {
            return Mono.just("[]");
        }

        // Spring WebFlux가 비동기 흐름을 올바르게 처리할 수 있도록 Mono 객체를 즉시 반환
        return geminiService.analyzeReceipt(base64Image);
    }
}
