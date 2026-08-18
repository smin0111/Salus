package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.domain.RecipeShare;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.CommunityFeedItemDTO;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.repository.RecipeShareRepository;
import com.salus.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommunityService {

        private final RecipeShareRepository recipeShareRepository;
        private final RecipeRepository recipeRepository;
        private final UserRepository userRepository;
        private final Clock clock;

        public List<CommunityFeedItemDTO> getPublicFeed() {
                // 공개 상태로 공유된 레시피 목록을 최신순으로 가져옵니다.
                List<RecipeShare> shares = recipeShareRepository.findByVisibilityOrderByCreatedAtDesc("PUBLIC");

                // 화면 조립에 필요한 Recipe ID와 User ID만 먼저 모읍니다.
                List<Long> recipeIds = shares.stream().map(RecipeShare::getRecipeId).distinct()
                                .collect(Collectors.toList());
                List<Long> userIds = shares.stream().map(RecipeShare::getUserId).distinct()
                                .collect(Collectors.toList());

                // 관련 Recipe와 User를 한 번에 조회해 공유 글마다 DB를 다시 조회하는 N+1 문제를 막습니다.
                Map<Long, Recipe> recipeMap = recipeRepository.findByIdIn(recipeIds).stream()
                                .collect(Collectors.toMap(Recipe::getId, r -> r));
                Map<Long, User> userMap = userRepository.findByIdIn(userIds).stream()
                                .collect(Collectors.toMap(User::getId, u -> u));

                // DB Entity를 그대로 노출하지 않고 화면에 필요한 값만 DTO로 변환합니다.
                return shares.stream()
                                .map(share -> {
                                        Recipe recipe = recipeMap.get(share.getRecipeId());
                                        User user = userMap.get(share.getUserId());

                                        return new CommunityFeedItemDTO(
                                                        share.getId(),
                                                        user != null ? user.getId() : null,
                                                        user != null ? user.getName() : "알 수 없음",
                                                        recipe != null ? recipe.getId() : null,
                                                        recipe != null ? recipe.getTitle() : "삭제된 레시피",
                                                        recipe != null ? recipe.getDescription() : "",
                                                        recipe != null ? recipe.getImageUrl() : "",
                                                        recipe != null ? recipe.getCalories() : 0,
                                                        share.getShareMessage(),
                                                        share.getCreatedAt());
                                })
                                .collect(Collectors.toList());
        }

        public RecipeShare shareRecipe(Long userId, Long recipeId, String message, String visibility) {
                RecipeShare share = new RecipeShare();
                share.setUserId(userId);
                share.setRecipeId(recipeId);
                share.setShareMessage(message);
                share.setVisibility(visibility);
                share.setCreatedAt(LocalDateTime.now(clock));

                return recipeShareRepository.save(share);
        }
}
