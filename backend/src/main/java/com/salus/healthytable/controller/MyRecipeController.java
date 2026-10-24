package com.salus.healthytable.controller;

import com.salus.healthytable.dto.MyRecipeDto;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.UserRecipeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 레시피 API(/api/my-recipes)입니다. 로그인한 사용자만 쓰고(SecurityConfig의 기본 규칙),
 * 사용자는 언제나 인증 정보에서 꺼냅니다(요청 본문의 사용자 정보는 받지 않음). 남의 레시피·카드는 404입니다.
 *
 * <p>/api/recipes 아래에 두지 않는 이유: 그 경로의 GET은 로그인 없이 열려 있습니다.
 */
@RestController
@RequestMapping("/api/my-recipes")
@RequiredArgsConstructor
public class MyRecipeController {

    private final UserRecipeService userRecipeService;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    @GetMapping
    public MyRecipeDto.Page list(@RequestParam(required = false) String cursor,
                                 @RequestParam(required = false) Integer size) {
        return userRecipeService.list(authenticatedUserProvider.requireUserId(), cursor, size);
    }

    @GetMapping("/{id}")
    public MyRecipeDto.Detail get(@PathVariable Long id) {
        return userRecipeService.get(authenticatedUserProvider.requireUserId(), id);
    }

    /** 채팅 카드를 저장합니다. 새로 만들면 201, 이미 저장한 카드면 200과 기존 레시피입니다. */
    @PostMapping("/from-card")
    public ResponseEntity<MyRecipeDto.Detail> saveFromCard(@RequestBody MyRecipeDto.FromCardRequest request) {
        UserRecipeService.SaveResult result = userRecipeService.saveFromCard(authenticatedUserProvider.requireUserId(), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.detail());
    }

    @PostMapping
    public ResponseEntity<MyRecipeDto.Detail> create(@RequestBody MyRecipeDto.CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userRecipeService.create(authenticatedUserProvider.requireUserId(), request));
    }

    @PatchMapping("/{id}")
    public MyRecipeDto.Detail update(@PathVariable Long id, @RequestBody MyRecipeDto.UpdateRequest request) {
        return userRecipeService.update(authenticatedUserProvider.requireUserId(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userRecipeService.delete(authenticatedUserProvider.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
