package com.salus.healthytable.util;

import com.salus.healthytable.domain.User;

/**
 * 다른 사람에게 보이는 작성자 이름을 정합니다(게시글·댓글·공유 피드).
 *
 * User.name은 소셜 계정에서 받은 실명일 수 있어 공개 응답에 쓰지 않습니다. 닉네임이 있으면 닉네임을,
 * 없으면(닉네임 도입 전 회원) 모두 같은 일반 표시명을 보여 줍니다. 탈퇴 등으로 회원을 찾지 못하면 "알 수 없음"입니다.
 */
public final class PublicUserName {

    public static final String DEFAULT_NAME = "Salus 사용자";
    public static final String UNKNOWN_NAME = "알 수 없음";

    private PublicUserName() {
    }

    public static String of(User user) {
        if (user == null) {
            return UNKNOWN_NAME;
        }
        String nickname = user.getNickname();
        return nickname == null || nickname.isBlank() ? DEFAULT_NAME : nickname;
    }
}
