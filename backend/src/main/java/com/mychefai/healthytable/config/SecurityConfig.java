package com.mychefai.healthytable.config;

import com.mychefai.healthytable.security.JwtAuthenticationFilter;
import com.mychefai.healthytable.security.IpWhitelistFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final IpWhitelistFilter ipWhitelistFilter;
    private final CoopHeaderFilter coopHeaderFilter;

    @Value("${app.cors.allowed-origins:*}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
            IpWhitelistFilter ipWhitelistFilter,
            CoopHeaderFilter coopHeaderFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.ipWhitelistFilter = ipWhitelistFilter;
        this.coopHeaderFilter = coopHeaderFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        // CORS Preflight 요청 허용
                        .requestMatchers(CorsUtils::isPreFlightRequest).permitAll()
                        // 게스트 접근 허용 엔드포인트
                        .requestMatchers("/api/chat/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/recipes/**").permitAll() // 홈 화면용
                        // 커뮤니티 엔드포인트
                        .requestMatchers("/api/community/**").permitAll()
                        // 냉장고 스캔 엔드포인트
                        .requestMatchers("/api/fridge/scan").permitAll()
                        // 어드민 대시보드
                        .requestMatchers("/api/admin/**").permitAll()
                        // 그 외 요청은 인증 필요
                        .requestMatchers("/api/fridge/**").authenticated()
                        .requestMatchers("/api/health-checkups/**").authenticated()
                        .requestMatchers("/api/users/**").authenticated()
                        .anyRequest().permitAll() // 개발 편의를 위해 나머지 허용
                )
                .addFilterBefore(coopHeaderFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(ipWhitelistFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
