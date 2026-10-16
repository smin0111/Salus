package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.domain.HealthProfile;
import com.salus.healthytable.repository.HealthProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * DB(건강 프로필)에서 Recipe Agent용 사용자 맥락을 읽는 구현체입니다.
 *
 * - 조회 실패: LOAD_FAILED(알레르기를 모르는 상태라 개인화 레시피를 주지 않음) / 프로필 없음: NOT_REGISTERED / 그 외: LOADED
 */
@Service
@RequiredArgsConstructor
class RepositoryUserRecipeContextLoader implements UserRecipeContextLoader {

    private final HealthProfileRepository healthProfileRepository;

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
        boolean profileFailed = false;
        try {
            profile = healthProfileRepository.findByUserId(userId);
        } catch (Exception e) {
            profileFailed = true;
        }

        UserRecipeContext context = new UserRecipeContext(
                userId,
                profile.map(HealthProfile::getAllergies).orElse(List.of()),
                profile.map(HealthProfile::getChronicConditions).orElse(List.of()),
                profile.map(HealthProfile::getDietaryRestrictions).orElse(List.of()),
                profile.map(HealthProfile::getMedications).orElse(List.of()),
                profile.map(HealthProfile::getGoals).orElse(List.of()),
                List.of());
        UserRecipeContextLoadStatus status;
        if (profileFailed) {
            status = UserRecipeContextLoadStatus.LOAD_FAILED;
        } else if (profile.isEmpty()) {
            status = UserRecipeContextLoadStatus.NOT_REGISTERED;
        } else {
            status = UserRecipeContextLoadStatus.LOADED;
        }
        return new UserRecipeContextLoadResult(
                context,
                status,
                profileFailed ? ContextSectionLoadStatus.LOAD_FAILED : ContextSectionLoadStatus.LOADED);
    }
}
