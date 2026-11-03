package com.salus.healthytable.service.ingredient;

import java.util.List;
import java.util.Optional;

/** 재료 이름 비교용 도우미입니다. */
final class IngredientNames {

    // "송이버섯"·"명이나물"처럼 종류를 뒤에 붙여 부르는 말. 위키백과 제목은 "송이"처럼 붙이지 않을 때가 있습니다.
    private static final List<String> KIND_SUFFIXES = List.of("버섯", "나물");
    // 제목이 이름 뒤에 식물·종류 말을 붙인 경우(두릅→두릅나무, 가자미→가자미과). 같은 것이라는 근거는 아니어서 '관련 문서'로만 씁니다.
    static final List<String> TITLE_SUFFIXES = List.of("나무", "버섯", "나물", "류", "과");

    private IngredientNames() {
    }

    static Optional<String> withoutKindSuffix(String name) {
        for (String suffix : KIND_SUFFIXES) {
            if (name.endsWith(suffix) && name.length() > suffix.length()) {
                return Optional.of(name.substring(0, name.length() - suffix.length()));
            }
        }
        return Optional.empty();
    }
}
