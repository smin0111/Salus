package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.domain.RecipeApprovalStatus;
import com.salus.healthytable.repository.WebRecipeSourceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 웹에서 레시피 근거를 찾아 저장해 둔 요리 이름인지 확인합니다.
 * "크룽지!"처럼 요청 표현 없이 요리 이름만 보낸 메시지를 레시피 요청으로 볼지 정할 때 씁니다.
 * 근거가 저장된 요리만 인정하므로 "안녕!" 같은 짧은 대화를 요리로 착각하지 않습니다.
 */
@Slf4j
@Service
public class KnownWebDishLookup {

    private final WebRecipeSourceRepository repository;

    public KnownWebDishLookup(WebRecipeSourceRepository repository) {
        this.repository = repository;
    }

    // 캐시 키는 "요리|만든 사람|모드|언어|선택 규칙 버전"이므로 요리 부분으로 앞부분을 찾습니다.
    @Transactional(readOnly = true)
    public boolean hasStoredSource(String dishName) {
        String dish = RecipeCandidate.normalize(dishName);
        if (dish.isBlank()) {
            return false;
        }
        try {
            return repository.existsByCacheKeyStartingWithAndReviewStatusNotAndExpiresAtAfter(
                    dish + "|", RecipeApprovalStatus.REJECTED, LocalDateTime.now());
        } catch (Exception e) {
            // 조회 실패는 "모르는 요리"입니다. 일반 대화로 답할 뿐 안전 판단에는 쓰지 않습니다.
            log.warn("[KnownWebDishLookup] Lookup failed. failureCategory={}", e.getClass().getSimpleName());
            return false;
        }
    }
}
