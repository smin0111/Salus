package com.salus.healthytable.config;

import com.salus.healthytable.security.JwtAuthenticationFilter;
import com.salus.healthytable.security.IpWhitelistFilter;
import com.salus.healthytable.security.ApiSecurityErrorHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final IpWhitelistFilter ipWhitelistFilter;
    private final CoopHeaderFilter coopHeaderFilter;
    private final ApiSecurityErrorHandler apiSecurityErrorHandler;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
            IpWhitelistFilter ipWhitelistFilter,
            CoopHeaderFilter coopHeaderFilter,
            ApiSecurityErrorHandler apiSecurityErrorHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.ipWhitelistFilter = ipWhitelistFilter;
        this.coopHeaderFilter = coopHeaderFilter;
        this.apiSecurityErrorHandler = apiSecurityErrorHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // JWT API 서버는 서버 세션을 만들지 않아야 여러 인스턴스로 확장하기 쉽습니다.
                // 인증 실패(401)와 권한 부족(403)을 분리하면 프론트엔드가 로그인 유도와 접근 차단을 다르게 처리할 수 있습니다.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(apiSecurityErrorHandler::handleAuthenticationException)
                        .accessDeniedHandler(apiSecurityErrorHandler::handleAccessDeniedException))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
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
                        // 관리자 API는 JWT 안의 role이 ADMIN인 사용자만 통과합니다.
                        // IP 제한은 운영 환경에서 선택적으로 켜는 추가 방어선이고, 권한 검사를 대체하지 않습니다.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(coopHeaderFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(ipWhitelistFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(parseAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private List<String> parseAllowedOrigins() {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
    }
}
