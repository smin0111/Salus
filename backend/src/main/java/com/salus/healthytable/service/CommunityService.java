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
                // 1. Public으로 공유된 레시피 share 가져오기
                List<RecipeShare> shares = recipeShareRepository.findByVisibilityOrderByCreatedAtDesc("PUBLIC");

                // 2. 필요한 Recipe와 User ID 추출
                List<Long> recipeIds = shares.stream().map(RecipeShare::getRecipeId).distinct()
                                .collect(Collectors.toList());
                List<Long> userIds = shares.stream().map(RecipeShare::getUserId).distinct()
                                .collect(Collectors.toList());

                // 3. Batch 조회 (N+1 방지)
                Map<Long, Recipe> recipeMap = recipeRepository.findByIdIn(recipeIds).stream()
                                .collect(Collectors.toMap(Recipe::getId, r -> r));
                Map<Long, User> userMap = userRepository.findByIdIn(userIds).stream()
                                .collect(Collectors.toMap(User::getId, u -> u));

                // 4. DTO 변환
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
