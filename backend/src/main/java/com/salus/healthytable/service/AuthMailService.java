package com.salus.healthytable.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 이메일 로그인용 메일(가입 인증 코드, 비밀번호 재설정 코드, 안내)을 SMTP로 보냅니다.
 * spring.mail.host와 app.mail.from이 없으면 보내지 않고 available()=false입니다(가짜로 성공하지 않음).
 * 보내기는 요청 스레드가 아니라 전용 스레드 하나에서 합니다(대기 100통, 넘치면 버리고 로그). SMTP를 기다리는 시간이나
 * 발송 실패가 응답에 드러나면 가입 여부를 알 수 있기 때문입니다. 메일 본문·받는 주소는 로그에 남기지 않습니다.
 */
@Service
@Slf4j
public class AuthMailService {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;
    private final String host;
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(100), runnable -> {
                Thread thread = new Thread(runnable, "auth-mail");
                thread.setDaemon(true);
                return thread;
            }, (runnable, pool) -> log.warn("Auth mail dropped. reason=queue_full"));

    public AuthMailService(ObjectProvider<JavaMailSender> mailSender, @Value("${app.mail.from:}") String from,
            @Value("${spring.mail.host:}") String host) {
        this.mailSender = mailSender;
        this.from = from == null ? "" : from.trim();
        this.host = host == null ? "" : host.trim();
    }

    public boolean available() {
        return !from.isEmpty() && !host.isEmpty() && mailSender.getIfAvailable() != null;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public void sendSignupCode(String to, String code) {
        sendLater(to, "[Salus] 이메일 인증 코드",
                "Salus 가입 인증 코드는 " + code + " 입니다.\n10분 안에 입력해 주세요.\n\n"
                        + "직접 요청하지 않았다면 이 메일을 무시해 주세요.");
    }

    public void sendPasswordResetCode(String to, String code) {
        sendLater(to, "[Salus] 비밀번호 재설정 코드",
                "Salus 비밀번호 재설정 코드는 " + code + " 입니다.\n10분 안에 입력해 주세요.\n\n"
                        + "직접 요청하지 않았다면 이 메일을 무시해 주세요. 비밀번호는 바뀌지 않았습니다.");
    }

    // 이미 가입된 이메일로 가입 코드를 요청했을 때: 가입 여부를 화면에 드러내지 않고 메일로만 알립니다.
    public void sendAlreadyRegistered(String to) {
        sendLater(to, "[Salus] 이미 가입된 이메일입니다",
                "이 이메일로 이미 Salus 계정이 있어요. 기존에 쓰던 방법(이메일 또는 구글·카카오·네이버)으로 로그인해 주세요.\n"
                        + "비밀번호가 기억나지 않으면 로그인 화면의 '비밀번호 찾기'를 이용해 주세요.\n\n"
                        + "직접 요청하지 않았다면 이 메일을 무시해 주세요.");
    }

    // 소셜 로그인으로만 가입한 이메일로 비밀번호 재설정을 요청했을 때
    public void sendSocialOnlyNotice(String to) {
        sendLater(to, "[Salus] 비밀번호 재설정 안내",
                "이 이메일의 Salus 계정은 구글·카카오·네이버 로그인으로 가입되어 비밀번호가 없어요. 가입할 때 쓴 방법으로 로그인해 주세요.\n\n"
                        + "직접 요청하지 않았다면 이 메일을 무시해 주세요.");
    }

    private void sendLater(String to, String subject, String text) {
        executor.execute(() -> {
            try {
                send(to, subject, text);
            } catch (MailUnavailableException e) {
                // 원인은 send에서 로그로 남겼습니다. 화면에는 이미 같은 "보냈어요"를 응답했습니다.
            } catch (RuntimeException e) {
                log.warn("Auth mail send failed. type={}", e.getClass().getSimpleName());
            }
        });
    }

    void send(String to, String subject, String text) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || !available()) {
            throw new MailUnavailableException();
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        try {
            sender.send(message);
        } catch (MailException e) {
            log.warn("Auth mail send failed. type={}", e.getClass().getSimpleName());
            throw new MailUnavailableException();
        }
    }

    /** 메일 발송 설정이 없거나 보내지 못했습니다. */
    public static class MailUnavailableException extends RuntimeException {
    }
}
