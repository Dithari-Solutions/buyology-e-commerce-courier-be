package com.buyology.buyology_courier.config;

import com.buyology.buyology_courier.common.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nimbusds.jwt.JWTParser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    // Comma-separated origins: CORS_ALLOWED_ORIGINS=https://app.buyology.com,https://ops.buyology.com
    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    // Must match auth.jwt.issuer in application.properties
    @Value("${auth.jwt.issuer:buyology-courier-service}")
    private String courierIssuer;

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationManagerResolver<HttpServletRequest> jwtAuthManagerResolver,
            ObjectMapper objectMapper
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Root landing page and static assets
                        .requestMatchers("/", "/index.html", "/*.css", "/*.js", "/*.ico", "/*.png").permitAll()
                        // Kubernetes liveness / readiness probes — must be public
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // All other actuator endpoints require ADMIN
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // OpenAPI / Swagger UI — public in dev, restrict in prod via profile
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // WebSocket upgrade endpoint — auth happens inside STOMP CONNECT via JWT interceptor
                        .requestMatchers("/ws/**").permitAll()
                        // Dev-only test endpoints — controller is @Profile("dev") so these paths
                        // don't exist in prod; permitting here avoids needing a token for quick testing
                        .requestMatchers("/api/dev/**").permitAll()
                        // Courier auth endpoints — public (login, refresh, logout)
                        .requestMatchers(
                                "/api/auth/courier/login",
                                "/api/auth/courier/refresh",
                                "/api/auth/courier/logout"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        // Routes JWT validation to the correct decoder based on the token issuer
                        .authenticationManagerResolver(jwtAuthManagerResolver)
                        .authenticationEntryPoint((request, response, ex) -> {
                            String rawToken = extractBearerToken(request);
                            String tokenSnippet = rawToken != null && rawToken.length() > 10
                                    ? rawToken.substring(0, 6) + "…" + rawToken.substring(rawToken.length() - 4)
                                    : "(none)";
                            log.warn("[JWT-AUTH] 401 on {} {} — {}: {} | token={}",
                                    request.getMethod(), request.getRequestURI(),
                                    ex.getClass().getSimpleName(), ex.getMessage(), tokenSnippet);
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    ErrorResponse.of(401, "Unauthorized", "Authentication required.", request.getRequestURI())
                            ));
                        })
                        .accessDeniedHandler((request, response, ex) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    ErrorResponse.of(403, "Forbidden", "You do not have permission to perform this action.", request.getRequestURI())
                            ));
                        })
                )
                .build();
    }

    /**
     * Routes incoming JWTs to either the courier decoder (HMAC-SHA256, self-issued)
     * or the Keycloak decoder (RSA, admin tokens), based on the {@code iss} claim.
     *
     * Reading the {@code iss} claim without verification is safe here because the
     * claim is only used to choose a decoder — the chosen decoder then performs full
     * cryptographic validation.
     */
    @Value("${ecommerce.service.jwt.issuer:buyology-ecommerce-service}")
    private String ecommerceServiceIssuer;

    @Bean
    AuthenticationManagerResolver<HttpServletRequest> jwtAuthManagerResolver(
            @Qualifier("courierJwtDecoder") NimbusJwtDecoder courierDecoder,
            @Qualifier("keycloakJwtDecoder") JwtDecoder keycloakDecoder,
            @Qualifier("ecommerceServiceJwtDecoder") NimbusJwtDecoder ecommerceServiceDecoder
    ) {
        JwtAuthenticationProvider courierProvider = new JwtAuthenticationProvider(courierDecoder);
        courierProvider.setJwtAuthenticationConverter(jwtAuthenticationConverter());

        JwtAuthenticationProvider keycloakProvider = new JwtAuthenticationProvider(keycloakDecoder);
        keycloakProvider.setJwtAuthenticationConverter(jwtAuthenticationConverter());

        JwtAuthenticationProvider ecommerceServiceProvider = new JwtAuthenticationProvider(ecommerceServiceDecoder);
        ecommerceServiceProvider.setJwtAuthenticationConverter(jwtAuthenticationConverter());

        return request -> {
            String token = extractBearerToken(request);
            if (token == null) {
                log.warn("[JWT-ROUTER] No Bearer token on {} {}", request.getMethod(), request.getRequestURI());
                return keycloakProvider::authenticate;
            }
            String iss = extractIss(token);
            log.info("[JWT-ROUTER] iss='{}' uri={}", iss, request.getRequestURI());
            if (isCourierToken(token)) {
                log.info("[JWT-ROUTER] → courierDecoder");
                return courierProvider::authenticate;
            }
            if (isEcommerceServiceToken(token)) {
                logEcommerceTokenDetails(token);
                return ecommerceServiceProvider::authenticate;
            }
            log.warn("[JWT-ROUTER] iss='{}' did not match courier('{}') or ecommerce('{}') — falling back to Keycloak decoder",
                    iss, courierIssuer, ecommerceServiceIssuer);
            return keycloakProvider::authenticate;
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Trim each origin to prevent " https://admin.com" (with leading space) from
        // being registered as a broken, never-matching allowed origin.
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        config.setAllowedOriginPatterns(origins);

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Required for cookies/auth headers on cross-origin requests
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        // Expects JWT claims: { "roles": ["ADMIN", "COURIER"], "sub": "<courier-uuid>" }
        // Works for BOTH Keycloak admin tokens and courier-issued tokens — both use "roles" claim.
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            if (token.length() != header.substring(7).length()) {
                log.warn("[JWT-ROUTER] Authorization header had leading/trailing whitespace in token part (rawLen={} trimmedLen={})",
                        header.substring(7).length(), token.length());
            }
            return token;
        }
        return null;
    }

    /**
     * Read the {@code iss} claim WITHOUT signature verification to decide which
     * decoder to use. The actual verification happens inside the chosen provider.
     */
    private String extractIss(String token) {
        try {
            return (String) JWTParser.parse(token).getJWTClaimsSet().getClaim("iss");
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isCourierToken(String token) {
        try {
            String iss = (String) JWTParser.parse(token).getJWTClaimsSet().getClaim("iss");
            return courierIssuer.equals(iss);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isEcommerceServiceToken(String token) {
        try {
            String iss = (String) JWTParser.parse(token).getJWTClaimsSet().getClaim("iss");
            return ecommerceServiceIssuer.equals(iss);
        } catch (Exception e) {
            return false;
        }
    }

    private String extractAlg(String token) {
        try {
            return JWTParser.parse(token).getHeader().getAlgorithm().getName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void logEcommerceTokenDetails(String token) {
        try {
            var jwt = JWTParser.parse(token);
            var claims = jwt.getJWTClaimsSet();
            log.info("[JWT-ROUTER] → ecommerceServiceDecoder alg='{}' tokenLen={} sub='{}' iss='{}' roles={} iat={} exp={}",
                    jwt.getHeader().getAlgorithm().getName(),
                    token.length(),
                    claims.getSubject(),
                    claims.getIssuer(),
                    claims.getClaim("roles"),
                    claims.getIssueTime(),
                    claims.getExpirationTime());
        } catch (Exception e) {
            log.warn("[JWT-ROUTER] → ecommerceServiceDecoder (failed to decode token details: {})", e.getMessage());
        }
    }
}
