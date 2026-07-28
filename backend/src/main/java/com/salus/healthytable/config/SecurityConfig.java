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
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(apiSecurityErrorHandler::handleAuthenticationException)
                        .accessDeniedHandler(apiSecurityErrorHandler::handleAccessDeniedException))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        // CORS Preflight 요청 허용
                        .requestMatchers(CorsUtils::isPreFlightRequest).permitAll()
                        // 운영 헬스체크는 로드밸런서/컨테이너가 인증 없이 확인할 수 있어야 함
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // 게스트 채팅은 허용하되, 세션/음성 업로드처럼 사용자 데이터나 파일을 다루는 API는 인증 필요
                        .requestMatchers(HttpMethod.POST, "/api/chat/message").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/recipes/**").permitAll() // 홈 화면용
                        // 개인화 추천은 조회 요청이어도 사용자 데이터 기반이므로 인증 필요
                        .requestMatchers(HttpMethod.GET, "/api/community/recommendations").authenticated()
                        // 커뮤니티 읽기 엔드포인트는 공개, 작성/수정/삭제/좋아요는 인증 필요
                        .requestMatchers(HttpMethod.GET, "/api/community/**").permitAll()
                        // 그 외 요청은 인증 필요
                        .requestMatchers("/api/community/**").authenticated()
                        .requestMatchers("/api/fridge/**").authenticated()
                        .requestMatchers("/api/health-checkups/**").authenticated()
                        .requestMatchers("/api/users/**").authenticated()
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
