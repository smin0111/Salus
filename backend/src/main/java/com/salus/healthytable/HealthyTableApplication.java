package com.salus.healthytable;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Salus 백엔드 애플리케이션의 시작점(main 클래스)입니다.
 *
 * {@code @SpringBootApplication}은 이 패키지(com.salus.healthytable) 아래의
 * {@code @Component}, {@code @Service}, {@code @Controller} 등을 자동으로 찾아 Bean으로 등록합니다.
 * 그래서 새 클래스는 반드시 이 패키지 하위에 두어야 스프링이 인식할 수 있습니다.
 */
@SpringBootApplication
public class HealthyTableApplication {
    // 내장 톰캣 서버를 띄우고 스프링 컨테이너(ApplicationContext)를 초기화합니다.
    public static void main(String[] args) {
        SpringApplication.run(HealthyTableApplication.class, args);
    }
}
