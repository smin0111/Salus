package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 검색으로 보내는 검색어에 건강 상태·측정값·연락처가 섞이지 않는지 확인합니다.
 */
class ExternalSearchQueryPolicyTest {

    // 요리 이름 검색어는 보냅니다. 한 글자 겹침(약밥, 간장게장, 임연수, 당면)으로 막히면 안 됩니다.
    @Test
    void dishQueriesAreAllowed() {
        for (String query : List.of("김치찌개 레시피", "약밥 만드는 법", "약과 레시피", "간장게장 레시피", "임연수 구이 레시피",
                "당면 볶음 레시피", "저염 김치찌개 레시피", "다이어트 샐러드 레시피", "백종원 마늘간장치킨 만드는 법",
                "땅콩 없는 쿠키 레시피", "치즈 있는 파스타 레시피", "Gordon Ramsay beef wellington recipe")) {
            assertThat(ExternalSearchQueryPolicy.isAllowed(query)).as(query).isTrue();
        }
    }

    // 건강 상태, 측정값, 연락처, 긴 문장은 보내지 않습니다.
    @Test
    void sensitiveOrSentenceQueriesAreBlocked() {
        for (String query : List.of("당뇨 있는데 먹을 수 있는 국 레시피", "고혈압 환자 반찬 레시피", "임신 중 먹을 미역국",
                "항암 치료 중 죽 레시피", "체중 80kg 감량 식단", "공복혈당 130 아침 메뉴", "수술 후 회복식 레시피",
                "땅콩 알레르기 쿠키 레시피", "test@example.com 레시피", "010-1234-5678 레시피",
                "천식 있는 사람 닭죽 레시피", "엄마가 앓는 병에 좋은 국", "diabetes soup recipe", "pregnant pasta recipe",
                "blood sugar friendly cookies", "감기에 좋은 차 레시피", "어제 병원에서 콜레스테롤이 높다고 해서 저녁에 뭘 먹어야 할지 모르겠는데 추천해 줄래")) {
            assertThat(ExternalSearchQueryPolicy.isAllowed(query)).as(query).isFalse();
        }
        assertThat(ExternalSearchQueryPolicy.isAllowed(null)).isFalse();
        assertThat(ExternalSearchQueryPolicy.isAllowed("  ")).isFalse();
    }

    // 사용자가 등록한 복용 약·질환 이름이 들어가면 보내지 않습니다. 한 글자 등록값은 무시합니다.
    @Test
    void registeredPrivateTermsAreBlocked() {
        assertThat(ExternalSearchQueryPolicy.isAllowed("와파린 나물무침 레시피", List.of("와파린"))).isFalse();
        assertThat(ExternalSearchQueryPolicy.isAllowed("나물무침 레시피", List.of("와파린"))).isTrue();
        assertThat(ExternalSearchQueryPolicy.isAllowed("약밥 레시피", List.of("약"))).isTrue();
    }

    // 로그에는 요청 제목 내용을 남기지 않고 길이만 남깁니다(등록한 약 이름은 이 자리에서 알 수 없음).
    @Test
    void forLogKeepsOnlyLength() {
        assertThat(ExternalSearchQueryPolicy.forLog("와파린 나물무침")).isEqualTo("[length=8]");
        assertThat(ExternalSearchQueryPolicy.forLog(null)).isEqualTo("[length=0]");
    }
}
