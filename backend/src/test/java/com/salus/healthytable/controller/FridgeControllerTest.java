package com.salus.healthytable.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.FridgeItem;
import com.salus.healthytable.repository.FridgeItemRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.GeminiService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FridgeControllerTest {

    private final FridgeItemRepository fridgeItemRepository = mock(FridgeItemRepository.class);
    private final GeminiService geminiService = mock(GeminiService.class);
    private final AuthenticatedUserProvider authenticatedUserProvider = mock(AuthenticatedUserProvider.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-05T15:30:00Z"), ZoneId.of("Asia/Seoul"));
    private final FridgeController controller = new FridgeController(
            fridgeItemRepository,
            geminiService,
            authenticatedUserProvider,
            new ObjectMapper(),
            clock);

    @Test
    void getFridgeItemsReadsOnlyCurrentUsersItems() {
        FridgeItem item = fridgeItem(7L, 1L, "우유");
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(fridgeItemRepository.findByUserIdOrderByExpiryDate(1L)).thenReturn(List.of(item));

        List<FridgeItem> response = controller.getFridgeItems();

        assertThat(response).containsExactly(item);
        verify(fridgeItemRepository).findByUserIdOrderByExpiryDate(1L);
    }

    @Test
    void addFridgeItemClearsClientControlledIdAndUserId() {
        FridgeItem request = new FridgeItem();
        request.setId(99L);
        request.setUserId(999L);
        request.setName("  달걀  ");
        request.setQuantity("  10 개  ");
        request.setCategory("  달걀  ");
        request.setExpiryDate(LocalDate.of(2026, 7, 10));

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(fridgeItemRepository.save(any(FridgeItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FridgeItem response = controller.addFridgeItem(request);

        ArgumentCaptor<FridgeItem> captor = ArgumentCaptor.forClass(FridgeItem.class);
        verify(fridgeItemRepository).save(captor.capture());
        FridgeItem saved = captor.getValue();

        assertThat(saved.getId()).isNull();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getName()).isEqualTo("달걀");
        assertThat(saved.getQuantity()).isEqualTo("10 개");
        assertThat(saved.getCategory()).isEqualTo("달걀");
        assertThat(response).isSameAs(saved);
    }

    @Test
    void addFridgeItemFillsDefaultExpiryDateWithConfiguredClock() {
        FridgeItem request = new FridgeItem();
        request.setName("사과");
        request.setCategory("과일");

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(fridgeItemRepository.save(any(FridgeItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FridgeItem response = controller.addFridgeItem(request);

        assertThat(response.getExpiryDate()).isEqualTo(LocalDate.of(2026, 7, 13));
    }

    @Test
    void addFridgeItemRejectsBlankNameBeforeSaving() {
        FridgeItem request = new FridgeItem();
        request.setName("   ");
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);

        assertThatThrownBy(() -> controller.addFridgeItem(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재료 이름을 입력해 주세요.");

        verifyNoInteractions(fridgeItemRepository);
    }

    @Test
    void addFridgeItemDefaultsBlankQuantityAndCategory() {
        FridgeItem request = new FridgeItem();
        request.setName("양파");
        request.setQuantity(" ");
        request.setCategory(null);

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(fridgeItemRepository.save(any(FridgeItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FridgeItem response = controller.addFridgeItem(request);

        assertThat(response.getQuantity()).isEqualTo("1개");
        assertThat(response.getCategory()).isEqualTo("기타");
    }

    @Test
    void updateFridgeItemChangesOnlyCurrentUsersItemFields() {
        FridgeItem existing = fridgeItem(7L, 1L, "우유");
        FridgeItem request = fridgeItem(999L, 999L, "  두유  ");
        request.setQuantity(" 2개 ");
        request.setCategory(" 유제품 ");
        request.setExpiryDate(LocalDate.of(2026, 7, 20));

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(fridgeItemRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(fridgeItemRepository.save(any(FridgeItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FridgeItem response = controller.updateFridgeItem(7L, request);

        assertThat(response.getId()).isEqualTo(7L);
        assertThat(response.getUserId()).isEqualTo(1L);
        assertThat(response.getName()).isEqualTo("두유");
        assertThat(response.getQuantity()).isEqualTo("2개");
        assertThat(response.getCategory()).isEqualTo("유제품");
        assertThat(response.getExpiryDate()).isEqualTo(LocalDate.of(2026, 7, 20));
    }

    @Test
    void updateFridgeItemReturnsNotFoundWhenItemDoesNotBelongToCurrentUser() {
        FridgeItem existing = fridgeItem(7L, 2L, "우유");
        FridgeItem request = fridgeItem(null, null, "두유");

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(fridgeItemRepository.findById(7L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> controller.updateFridgeItem(7L, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).isEqualTo("냉장고 항목을 찾을 수 없습니다.");
                });
    }

    @Test
    void deleteFridgeItemRejectsOtherUsersItem() {
        FridgeItem existing = fridgeItem(7L, 2L, "우유");

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(fridgeItemRepository.findById(7L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> controller.deleteFridgeItem(7L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getReason()).isEqualTo("본인의 냉장고 항목만 삭제할 수 있습니다.");
                });
    }

    @Test
    void adjustQuantityRejectsOtherUsersItem() {
        FridgeItem existing = fridgeItem(7L, 2L, "우유");

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(fridgeItemRepository.findById(7L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> controller.adjustQuantity(7L, Map.of("quantity", "2개")))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).isEqualTo("냉장고 항목을 찾을 수 없습니다.");
                });
    }

    @Test
    void adjustQuantityNormalizesBlankQuantityToDefault() {
        FridgeItem existing = fridgeItem(7L, 1L, "우유");

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(fridgeItemRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(fridgeItemRepository.save(any(FridgeItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FridgeItem response = controller.adjustQuantity(7L, Map.of("quantity", " "));

        assertThat(response.getQuantity()).isEqualTo("1개");
        verify(fridgeItemRepository).save(existing);
    }

    @Test
    void scanReceiptReturnsEmptyListWhenImageIsMissing() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);

        List<Map<String, String>> response = controller.scanReceipt(null).block();

        assertThat(response).isEmpty();
        verifyNoInteractions(geminiService);
    }

    @Test
    void scanReceiptRejectsTooLargeImageBeforeAiCall() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        String largeImage = "a".repeat(8 * 1024 * 1024 + 1);

        assertThatThrownBy(() -> controller.scanReceipt(Map.of("image", largeImage)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("영수증 이미지는 8MB 이하로 업로드해 주세요.");

        verifyNoInteractions(geminiService);
    }

    @Test
    void scanReceiptParsesAndNormalizesAiJsonResponse() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(geminiService.analyzeReceipt("base64-image")).thenReturn(reactor.core.publisher.Mono.just("""
                [
                  {"name":" 두부 ","quantity":" 1모 ","category":" "},
                  {"name":" ","quantity":"1개","category":"채소"}
                ]
                """));

        List<Map<String, String>> response = controller.scanReceipt(Map.of("image", " base64-image ")).block();

        assertThat(response).containsExactly(Map.of(
                "name", "두부",
                "quantity", "1모",
                "category", "기타"));
        verify(geminiService).analyzeReceipt("base64-image");
    }

    @Test
    void scanReceiptReturnsEmptyListWhenAiResponseIsNotJsonArray() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(geminiService.analyzeReceipt("base64-image")).thenReturn(reactor.core.publisher.Mono.just("not json"));

        List<Map<String, String>> response = controller.scanReceipt(Map.of("image", "base64-image")).block();

        assertThat(response).isEmpty();
    }

    private FridgeItem fridgeItem(Long id, Long userId, String name) {
        FridgeItem item = new FridgeItem();
        item.setId(id);
        item.setUserId(userId);
        item.setName(name);
        item.setQuantity("1개");
        item.setCategory("기타");
        return item;
    }
}
