package com.salus.healthytable.service;

import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.UserResponseDTO;
import com.salus.healthytable.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NicknameService} 단위 테스트입니다. 실제 DB의 utf8mb4_bin UNIQUE 판정과 동시 요청은 NicknameMySqlIT에서 확인합니다.
 */
class NicknameServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final NicknameService service = new NicknameService(userRepository,
            Clock.fixed(Instant.parse("2026-10-24T01:00:00Z"), ZoneId.of("Asia/Seoul")));
    private User me;

    @BeforeEach
    void setUp() {
        me = new User();
        me.setId(1L);
        me.setName("소셜 실명");
        when(userRepository.findById(1L)).thenReturn(Optional.of(me));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findByNicknameNormalized(any())).thenReturn(Optional.empty());
    }

    // 정규화: 앞뒤 공백 제거, 전각 → 반각, 한글 자모 조합 → 완성형. 중복 판정 키는 소문자입니다.
    @Test
    void normalizesWidthCompositionAndCase() {
        assertThat(NicknameService.normalize("  김치러버  ").display()).isEqualTo("김치러버");
        NicknameService.Normalized fullWidth = NicknameService.normalize("ＣｈｅｆＫｉｍ");
        assertThat(fullWidth.display()).isEqualTo("ChefKim");
        assertThat(fullWidth.key()).isEqualTo("chefkim");
        // "가나"를 자모(ᄀ ᅡ ᄂ ᅡ)로 입력해도 완성형과 같은 닉네임입니다.
        assertThat(NicknameService.normalize("가나").key()).isEqualTo("가나");
        assertThat(NicknameService.normalize("cook_99").key()).isEqualTo("cook_99");
    }

    // 형식: 2~12자, 한글 완성형·영문·숫자·밑줄만. 공백·이모지·기호·낱자모·너무 짧거나 긴 값은 400입니다.
    @Test
    void rejectsInvalidFormats() {
        for (String invalid : List.of("가", "a".repeat(13), "김 치", "김치!", "김치😀", "ㄱㄴㄷ", "㈜살림", "")) {
            assertThatThrownBy(() -> NicknameService.normalize(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> NicknameService.normalize(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // 금지어: 관리자·서비스 사칭 낱말이 들어가거나(부분 일치), 짧은 금지어와 같으면 쓸 수 없습니다. 대소문자·전각도 같이 막습니다.
    @Test
    void rejectsReservedWords() {
        for (String reserved : List.of("관리자", "살루스요리사", "SALUS_chef", "ＡＤＭＩＮ", "official_kr", "root", "운영")) {
            assertThatThrownBy(() -> NicknameService.normalize(reserved))
                    .isInstanceOfSatisfying(IllegalArgumentException.class,
                            e -> assertThat(e.getMessage()).isEqualTo(NicknameService.RESERVED_MESSAGE));
        }
        // 짧은 금지어가 다른 낱말 안에 들어간 것은 허용합니다.
        assertThat(NicknameService.normalize("rooftop").key()).isEqualTo("rooftop");
    }

    // 저장: 화면용 값과 판정용 값을 함께 저장하고, 응답에 닉네임이 담깁니다(실명은 그대로).
    @Test
    void setStoresDisplayAndKey() {
        UserResponseDTO response = service.set(1L, " ChefKim ");

        assertThat(me.getNickname()).isEqualTo("ChefKim");
        assertThat(me.getNicknameNormalized()).isEqualTo("chefkim");
        assertThat(response.getNickname()).isEqualTo("ChefKim");
        assertThat(me.getName()).isEqualTo("소셜 실명");
    }

    // 다른 사람이 쓰는 닉네임(대소문자만 다른 경우 포함)은 409이고 저장하지 않습니다. 내 닉네임은 대소문자만 바꿔 다시 정할 수 있습니다.
    @Test
    void setRejectsNicknameOwnedByOtherButAllowsMine() {
        User other = new User();
        other.setId(2L);
        when(userRepository.findByNicknameNormalized("chefkim")).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.set(1L, "CHEFKIM"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(userRepository, never()).saveAndFlush(any());

        when(userRepository.findByNicknameNormalized("chefkim")).thenReturn(Optional.of(me));
        assertThat(service.set(1L, "CHEFKIM").getNickname()).isEqualTo("CHEFKIM");
    }

    // 미리 확인한 뒤 동시에 같은 닉네임이 저장돼 UNIQUE 위반이 나면 409입니다.
    @Test
    void uniqueViolationIsConflict() {
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("uk_users_nickname_normalized"));

        assertThatThrownBy(() -> service.set(1L, "김치러버"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    // 공개 글 작성 전 확인: 닉네임이 없으면 428, 있으면 통과합니다.
    @Test
    void requireNicknameRejectsUserWithoutNickname() {
        assertThatThrownBy(() -> service.requireNickname(1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED));

        me.setNickname("김치러버");
        service.requireNickname(1L);
    }

    // 확인 API: 형식 위반·금지어·다른 사람이 쓰는 닉네임은 사용 불가와 이유를, 내 닉네임과 빈 닉네임은 사용 가능을 돌려줍니다.
    @Test
    void checkReportsAvailabilityWithReason() {
        User other = new User();
        other.setId(2L);
        when(userRepository.findByNicknameNormalized("taken")).thenReturn(Optional.of(other));
        when(userRepository.findByNicknameNormalized("mine")).thenReturn(Optional.of(me));

        assertThat(service.check(1L, "Taken")).isEqualTo(new NicknameService.CheckResult(false, NicknameService.TAKEN_MESSAGE));
        assertThat(service.check(1L, "관리자")).isEqualTo(new NicknameService.CheckResult(false, NicknameService.RESERVED_MESSAGE));
        assertThat(service.check(1L, "a")).isEqualTo(new NicknameService.CheckResult(false, NicknameService.FORMAT_MESSAGE));
        assertThat(service.check(1L, "MINE").available()).isTrue();
        assertThat(service.check(1L, "새닉네임").available()).isTrue();
    }
}
