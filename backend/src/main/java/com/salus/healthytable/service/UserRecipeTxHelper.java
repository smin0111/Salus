package com.salus.healthytable.service;

import com.salus.healthytable.domain.ChatRecipeCard;
import com.salus.healthytable.domain.UserRecipe;
import com.salus.healthytable.repository.ChatRecipeCardRepository;
import com.salus.healthytable.repository.UserRecipeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 내 레시피의 DB 작업을 트랜잭션으로 묶는 도우미입니다.
 *
 * <p>UserRecipeService와 분리한 이유: 같은 카드를 동시에 두 번 저장하면 유니크 제약 위반이 나는데,
 * 그 뒤의 기존 레시피 조회는 실패한(롤백 중인) 트랜잭션 밖의 새 트랜잭션에서 해야 하기 때문입니다.
 * 모든 조회는 사용자 ID를 함께 걸어, 남의 카드나 레시피는 없는 것과 똑같이 404로 답합니다.
 */
@Component
@RequiredArgsConstructor
public class UserRecipeTxHelper {

    static final String NOT_FOUND = "레시피를 찾을 수 없습니다.";
    static final String CARD_NOT_FOUND = "레시피 카드를 찾을 수 없습니다.";
    static final String VERSION_CONFLICT = "다른 곳에서 먼저 수정했어요. 다시 불러온 뒤 수정해 주세요.";

    private final ChatRecipeCardRepository chatRecipeCardRepository;
    private final UserRecipeRepository userRecipeRepository;

    /** 카드 저장 결과입니다. created가 false면 이미 저장해 둔 레시피를 그대로 돌려준 것입니다. */
    public record FromCardResult(UserRecipe recipe, boolean created) {
    }

    /**
     * 내 카드를 내 레시피로 복사합니다. 이미 저장한 카드면 새로 만들지 않고 기존 레시피를 돌려줍니다.
     *
     * @param toRecipe 카드로 새 레시피 엔티티를 만드는 함수(내용 변환은 서비스가 맡음)
     */
    @Transactional
    public FromCardResult createFromCard(Long userId, String cardPublicId, Function<ChatRecipeCard, UserRecipe> toRecipe) {
        ChatRecipeCard card = chatRecipeCardRepository.findByPublicIdAndUserId(cardPublicId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, CARD_NOT_FOUND));
        Optional<UserRecipe> existing = userRecipeRepository.findByUserIdAndSourceCardId(userId, card.getId());
        if (existing.isPresent()) {
            return new FromCardResult(existing.get(), false);
        }
        // flush해서 동시 저장의 유니크 위반이 이 메서드 안에서 드러나게 합니다(호출자가 잡아 기존 레시피를 조회).
        return new FromCardResult(userRecipeRepository.saveAndFlush(toRecipe.apply(card)), true);
    }

    /** 유니크 위반 뒤 새 트랜잭션에서 이미 저장된 레시피를 찾습니다. */
    @Transactional(readOnly = true)
    public Optional<UserRecipe> findSavedFromCard(Long userId, String cardPublicId) {
        return chatRecipeCardRepository.findByPublicIdAndUserId(cardPublicId, userId)
                .flatMap(card -> userRecipeRepository.findByUserIdAndSourceCardId(userId, card.getId()));
    }

    @Transactional
    public UserRecipe create(UserRecipe recipe) {
        return userRecipeRepository.saveAndFlush(recipe);
    }

    @Transactional(readOnly = true)
    public UserRecipe get(Long userId, Long id) {
        return userRecipeRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    /**
     * 지금 버전·제목·수정 메모만 바꿉니다. 요청 버전이 DB 버전과 다르면 바로 409이고,
     * 조회와 저장 사이에 다른 수정이 끼어들면 @Version 충돌도 409로 바꿉니다.
     */
    @Transactional
    public UserRecipe update(Long userId, Long id, Integer expectedVersion, String title, String currentRecipe,
                             String modificationNote, LocalDateTime now) {
        UserRecipe recipe = get(userId, id);
        if (!Objects.equals(recipe.getVersion(), expectedVersion)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, VERSION_CONFLICT);
        }
        recipe.applyEdit(title, currentRecipe, modificationNote, now);
        try {
            return userRecipeRepository.saveAndFlush(recipe);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, VERSION_CONFLICT);
        }
    }

    @Transactional
    public void delete(Long userId, Long id) {
        userRecipeRepository.delete(get(userId, id));
    }
}
