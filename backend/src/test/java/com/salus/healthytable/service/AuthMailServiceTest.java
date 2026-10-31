package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 인증 메일은 요청 스레드가 아니라 메일 스레드에서 보내고, 발송 실패가 호출한 쪽으로 전해지지 않는지 확인합니다
 * (등록된 이메일만 SMTP를 기다리거나 503이 나면 가입 여부가 드러나기 때문).
 */
class AuthMailServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void mailIsSentOffRequestThreadAndFailuresDoNotPropagate() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        CountDownLatch sent = new CountDownLatch(1);
        String[] threadName = new String[1];
        doAnswer(invocation -> {
            threadName[0] = Thread.currentThread().getName();
            sent.countDown();
            throw new MailSendException("smtp down");
        }).when(sender).send(any(SimpleMailMessage.class));
        AuthMailService service = new AuthMailService(provider, "no-reply@salus.test", "smtp.example.test");

        service.sendPasswordResetCode("user@example.test", "123456");

        assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName[0]).isEqualTo("auth-mail").isNotEqualTo(Thread.currentThread().getName());
        service.shutdown();
    }

    @Test
    @SuppressWarnings("unchecked")
    void unavailableWithoutHostOrFrom() {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(JavaMailSender.class));

        assertThat(new AuthMailService(provider, "", "smtp.example.test").available()).isFalse();
        assertThat(new AuthMailService(provider, "no-reply@salus.test", "").available()).isFalse();
        assertThat(new AuthMailService(provider, "no-reply@salus.test", "smtp.example.test").available()).isTrue();
    }
}
