package com.salus.healthytable.config;

import com.salus.healthytable.security.JwtAuthenticationFilter;
import com.salus.healthytable.security.IpWhitelistFilter;
import com.salus.healthytable.security.ApiSecurityErrorHandler;
import com.salus.healthytable.security.AdminAuthenticationFilter;
import com.salus.healthytable.security.DisplayTokenFilter;
import com.salus.healthytable.service.adminauth.AdminSessionService;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 설정 클래스입니다.
 *
 * 경로마다 인증 수단이 다른 세 보안 설정을 둡니다.
 * - /api/monitor/** : 관제 화면(디스플레이 토큰, 통계 조회만)
 * - /api/admin/**   : 관리자(관리자 전용 토큰 + 서버 세션)
 * - 그 외           : 사용자 앱(사용자 JWT)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final IpWhitelistFilter ipWhitelistFilter;
    private final CoopHeaderFilter coopHeaderFilter;
    private final ApiSecurityErrorHandler apiSecurityErrorHandler;

    // 쉼표로 구분된 허용 도메인 목록 (예: http://localhost:8081,https://salus.example)
    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    // 관제 화면 디스플레이 토큰의 SHA-256 해시 목록(쉼표 구분). 비어 있으면 관제 API는 모두 401입니다.
    @Value("${app.monitor.display-token-hashes:}")
    private String displayTokenHashes;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
            IpWhitelistFilter ipWhitelistFilter,
            CoopHeaderFilter coopHeaderFilter,
            ApiSecurityErrorHandler apiSecurityErrorHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.ipWhitelistFilter = ipWhitelistFilter;
        this.coopHeaderFilter = coopHeaderFilter;
        this.apiSecurityErrorHandler = apiSecurityErrorHandler;
    }

    /**
     * 관제 화면 전용 보안 설정(/api/monitor/**). 디스플레이 토큰만 받고, 사용자·관리자 토큰은 검사하지 않습니다.
     * 경로마다 보안 설정을 따로 두어 인증 수단이 서로 섞이지 않게 합니다(@Order 순서로 먼저 매칭).
     */
    @Bean
    @Order(1)
    public SecurityFilterChain monitorFilterChain(HttpSecurity http) throws Exception {
        applyApiDefaults(http.securityMatcher("/api/monitor/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(CorsUtils::isPreFlightRequest).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/monitor/**").hasRole("MONITOR")
                        .anyRequest().denyAll())
                // 스프링 빈으로 등록하지 않아야 서블릿 필터로 자동 등록되어 다른 경로에서 실행되는 일이 없습니다.
                .addFilterBefore(new DisplayTokenFilter(displayTokenHashes), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 관리자 API 보안 설정(/api/admin/**). 관리자 access 토큰(JWT_ADMIN_SECRET + 서버 세션)만 받습니다.
     * 사용자 JWT 필터는 이 설정에 없으므로 사용자 토큰으로는 관리자 API에 들어올 수 없습니다.
     * ADMIN_VIEWER는 조회(GET)만, ADMIN은 변경까지 가능합니다.
     * IP 제한은 운영 환경에서 선택적으로 켜는 추가 방어선이고, 권한 검사를 대체하지 않습니다.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain adminFilterChain(HttpSecurity http,
            ObjectProvider<AdminSessionService> adminSessionService) throws Exception {
        applyApiDefaults(http.securityMatcher("/api/admin/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(CorsUtils::isPreFlightRequest).permitAll()
                        // 로그인 단계 API는 요청 본문의 challenge 토큰을 서비스에서 검증합니다.
                        .requestMatchers(HttpMethod.POST, AdminAuthenticationFilter.LOGIN_STEP_PATHS.toArray(String[]::new))
                        .permitAll()
                        .requestMatchers("/api/admin/auth/me", "/api/admin/auth/logout").hasAnyRole("ADMIN", "ADMIN_VIEWER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/**").hasAnyRole("ADMIN", "ADMIN_VIEWER")
                        .anyRequest().hasRole("ADMIN"))
                .addFilterBefore(ipWhitelistFilter, UsernamePasswordAuthenticationFilter.class);

        AdminSessionService sessionService = adminSessionService.getIfAvailable();
        if (sessionService != null) {
            http.addFilterBefore(new AdminAuthenticationFilter(sessionService, apiSecurityErrorHandler),
                    UsernamePasswordAuthenticationFilter.class);
        }
        // 세션 서비스가 없으면 인증 필터를 붙이지 않으므로 로그인 단계를 제외한 관리자 API는 모두 401입니다(fail closed).
        return http.build();
    }

    /**
     * 사용자 앱(모바일·웹) API 보안 설정. 위 두 설정에 매칭되지 않은 나머지 요청을 모두 처리합니다.
     * 규칙은 위에서 아래 순서로 검사되므로, 더 구체적인 규칙을 먼저 적어야 합니다.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain appFilterChain(HttpSecurity http) throws Exception {
        applyApiDefaults(http)
                .authorizeHttpRequests(auth -> auth
                        // 브라우저가 실제 요청 전에 보내는 CORS 사전 확인 요청은 인증 없이 통과시킵니다.
                        .requestMatchers(CorsUtils::isPreFlightRequest).permitAll()
                        // 운영 헬스체크는 로드밸런서/컨테이너가 인증 없이 확인할 수 있어야 함
                        .requestMatchers("/actuator/health/**").permitAll()
                        // 게스트 채팅은 허용하되, 세션/음성 업로드처럼 사용자 데이터나 파일을 다루는 API는 인증 필요
                        .requestMatchers(HttpMethod.POST, "/api/chat/message").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        // 홈 화면 공개 레시피 조회와 게스트 추천만 열어두고, 향후 추가될 쓰기 API는 기본 인증 규칙을 따르게 합니다.
                        .requestMatchers(HttpMethod.GET, "/api/recipes/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/recipes/recommend").permitAll()
                        // 개인화 추천은 조회 요청이어도 사용자 데이터 기반이므로 인증 필요
                        .requestMatchers(HttpMethod.GET, "/api/community/recommendations").authenticated()
                        // 커뮤니티 읽기 엔드포인트는 공개, 작성/수정/삭제/좋아요는 인증 필요
                        .requestMatchers(HttpMethod.GET, "/api/community/**").permitAll()
                        // 그 외 요청은 인증 필요
                        .requestMatchers("/api/community/**").authenticated()
                        .requestMatchers("/api/fridge/**").authenticated()
                        .requestMatchers("/api/health-checkups/**").authenticated()
                        .requestMatchers("/api/users/**").authenticated()
                        .anyRequest().authenticated()
                )
                // 커스텀 필터들을 스프링 기본 로그인 필터보다 앞에 끼워 넣어, 인증 정보가 먼저 준비되게 합니다.
                .addFilterBefore(coopHeaderFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    // 세 보안 설정이 공통으로 쓰는 API 서버 기본값입니다.
    private HttpSecurity applyApiDefaults(HttpSecurity http) throws Exception {
        return http
                // 쿠키 세션 대신 요청 헤더의 토큰을 쓰므로 CSRF 보호와 세션 기반 기능은 끕니다.
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // API 서버는 서버 세션을 만들지 않아야 여러 인스턴스로 확장하기 쉽습니다.
                // 인증 실패(401)와 권한 부족(403)을 분리하면 프론트엔드가 로그인 유도와 접근 차단을 다르게 처리할 수 있습니다.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(apiSecurityErrorHandler::handleAuthenticationException)
                        .accessDeniedHandler(apiSecurityErrorHandler::handleAccessDeniedException))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()));
    }

    /**
     * 인증 필터들은 @Component라 Spring Boot가 모든 요청에 적용하는 서블릿 필터로도 자동 등록합니다.
     * 그러면 보안 설정을 경로별로 나눠도 사용자 JWT 필터가 관제·관리자 경로에서 다시 실행되므로 자동 등록을 끕니다.
     * 필터는 각 보안 설정(addFilterBefore) 안에서만 실행됩니다.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    public FilterRegistrationBean<IpWhitelistFilter> ipWhitelistFilterRegistration(IpWhitelistFilter filter) {
        return disabledRegistration(filter);
    }

    private <T extends Filter> FilterRegistrationBean<T> disabledRegistration(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * CORS(다른 도메인의 브라우저 요청 허용) 정책을 정의합니다.
     * 프론트엔드(Expo 웹)와 관리자 웹이 백엔드와 다른 주소에서 실행되므로 필요합니다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(parseAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // 브라우저가 사전 확인(preflight) 결과를 1시간 동안 캐시하도록 합니다.
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    // "a, b ,c" 형태의 설정 문자열을 공백 없는 목록으로 바꿉니다.
    private List<String> parseAllowedOrigins() {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
    }
}
