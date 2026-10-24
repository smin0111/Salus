package com.salus.healthytable.service;

import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.UserResponseDTO;
import com.salus.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 커뮤니티 닉네임을 정하고 확인합니다. 소셜 로그인 실명(name)은 다른 사용자에게 보여 주지 않고 닉네임을 씁니다.
 *
 * <p>규칙: 앞뒤 공백을 지우고 NFKC로 정규화한 값(전각 → 반각, 한글 자모 → 완성형)이 2~12자의 한글 완성형·영문·숫자·밑줄이어야 합니다.
 * 중복은 그 값의 소문자(nickname_normalized)로 판정하고, DB의 UNIQUE(utf8mb4_bin)가 동시 요청까지 막습니다.
 * 관리자·서비스 사칭 낱말은 쓸 수 없습니다. 변경 횟수 제한은 두지 않습니다.
 */
@Service
@RequiredArgsConstructor
public class NicknameService {

    static final String FORMAT_MESSAGE = "닉네임은 2~12자의 한글, 영문, 숫자, 밑줄(_)만 쓸 수 있어요.";
    static final String RESERVED_MESSAGE = "사용할 수 없는 닉네임이에요.";
    static final String TAKEN_MESSAGE = "이미 사용 중인 닉네임이에요.";
    static final String NICKNAME_REQUIRED_MESSAGE = "닉네임을 정한 뒤에 글을 쓰거나 공유할 수 있어요.";

    private static final Pattern ALLOWED = Pattern.compile("^[가-힣A-Za-z0-9_]{2,12}$");
    // 이 낱말이 들어가면 쓸 수 없습니다(관리자·서비스 사칭 방지).
    private static final List<String> RESERVED_PARTS = List.of(
            "관리자", "운영자", "운영진", "살루스", "공식", "salus", "admin", "official", "moderator", "staff", "system");
    // 이 낱말과 똑같으면 쓸 수 없습니다(짧아서 다른 낱말 안에 흔히 들어가는 것).
    private static final List<String> RESERVED_EXACT = List.of("root", "mod", "gm", "운영", "관리");

    private final UserRepository userRepository;
    private final Clock clock;

    /** 정규화한 닉네임입니다. display는 화면에 보일 값, key는 중복 판정 값입니다. */
    record Normalized(String display, String key) {
    }

    /** 확인 결과입니다. available이 false면 message에 이유가 있습니다. */
    public record CheckResult(boolean available, String message) {
    }

    /** 형식·금지어·중복을 확인합니다(저장하지 않음). 내 현재 닉네임과 같으면 사용 가능합니다. */
    @Transactional(readOnly = true)
    public CheckResult check(Long userId, String raw) {
        Normalized normalized;
        try {
            normalized = normalize(raw);
        } catch (IllegalArgumentException e) {
            return new CheckResult(false, e.getMessage());
        }
        boolean takenByOther = userRepository.findByNicknameNormalized(normalized.key())
                .filter(owner -> !owner.getId().equals(userId))
                .isPresent();
        return takenByOther ? new CheckResult(false, TAKEN_MESSAGE) : new CheckResult(true, null);
    }

    /**
     * 닉네임을 정합니다(처음 정하기와 바꾸기 모두). 형식·금지어 위반은 400, 다른 사람이 쓰는 닉네임은 409입니다.
     * 미리 확인한 뒤에도 동시에 같은 닉네임을 정하면 DB UNIQUE 위반이 나므로 그것도 409로 바꿉니다.
     */
    @Transactional
    public UserResponseDTO set(Long userId, String raw) {
        Normalized normalized = normalize(raw);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        Optional<User> owner = userRepository.findByNicknameNormalized(normalized.key());
        if (owner.isPresent() && !owner.get().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, TAKEN_MESSAGE);
        }
        user.setNickname(normalized.display());
        user.setNicknameNormalized(normalized.key());
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, TAKEN_MESSAGE);
        }
        return UserResponseDTO.from(user, LocalDateTime.now(clock));
    }

    /**
     * 다른 사람에게 보이는 글을 쓰기 전(게시글·댓글·레시피 공유)에 닉네임이 있는지 확인합니다. 없으면 428입니다.
     * 조회와, 이미 쓴 글의 수정·삭제·좋아요는 막지 않습니다. 닉네임은 지울 수 없으므로 확인 뒤 사라지는 경우는 없습니다.
     */
    @Transactional(readOnly = true)
    public void requireNickname(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        if (user.getNickname() == null || user.getNickname().isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, NICKNAME_REQUIRED_MESSAGE);
        }
    }

    /** 공백 제거 → NFKC → 형식 확인 → 소문자 키 → 금지어 확인. 위반이면 IllegalArgumentException(400)입니다. */
    static Normalized normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("닉네임을 입력해 주세요.");
        }
        String display = Normalizer.normalize(raw.strip(), Normalizer.Form.NFKC);
        if (!ALLOWED.matcher(display).matches()) {
            throw new IllegalArgumentException(FORMAT_MESSAGE);
        }
        String key = display.toLowerCase(Locale.ROOT);
        if (RESERVED_EXACT.contains(key) || RESERVED_PARTS.stream().anyMatch(key::contains)) {
            throw new IllegalArgumentException(RESERVED_MESSAGE);
        }
        return new Normalized(display, key);
    }
}
