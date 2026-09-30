package com.salus.healthytable.config;

import com.salus.healthytable.domain.AdminRole;
import com.salus.healthytable.service.adminauth.AdminAccountProvisioner;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 서버에서 관리자 계정을 만들거나 복구하는 일회성 명령입니다. 지정한 옵션이 있을 때만 실행되고, 작업 후 종료합니다.
 *
 * 첫 관리자 만들기:
 *   java -jar app.jar --spring.main.web-application-type=none --admin.cli.create=alice --admin.cli.role=ADMIN
 * 비밀번호·인증 앱 분실 복구:
 *   java -jar app.jar --spring.main.web-application-type=none --admin.cli.reset=alice
 *
 * 임시 비밀번호는 표준 출력에 한 번만 표시됩니다. 로그 수집기가 표준 출력을 저장하는 환경이라면 따로 전달하세요.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${admin.cli.create:}' != '' or '${admin.cli.reset:}' != ''")
public class AdminAccountCliRunner implements ApplicationRunner {

    private final AdminAccountProvisioner provisioner;
    private final ConfigurableApplicationContext context;

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            AdminAccountProvisioner.Issued issued;
            if (args.containsOption("admin.cli.create")) {
                String role = firstValue(args, "admin.cli.role", "ADMIN_VIEWER");
                issued = provisioner.create(firstValue(args, "admin.cli.create", ""), AdminRole.valueOf(role.toUpperCase()));
                System.out.println("[관리자 계정 생성 완료]");
            } else {
                issued = provisioner.reset(firstValue(args, "admin.cli.reset", ""));
                System.out.println("[관리자 계정 복구 완료] 기존 세션과 인증 앱 등록이 초기화되었습니다.");
            }
            System.out.println("아이디: " + issued.username());
            System.out.println("임시 비밀번호(한 번만 표시): " + issued.temporaryPassword());
            System.out.println("만료: " + issued.expiresAt() + " (첫 로그인에서 비밀번호 변경과 인증 앱 등록이 필요합니다)");
        } catch (IllegalArgumentException e) {
            System.err.println("관리자 계정 작업 실패: " + e.getMessage());
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    private String firstValue(ApplicationArguments args, String name, String fallback) {
        if (!args.containsOption(name) || args.getOptionValues(name).isEmpty()) {
            return fallback;
        }
        return args.getOptionValues(name).get(0);
    }
}
