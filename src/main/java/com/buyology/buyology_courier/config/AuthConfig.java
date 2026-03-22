package com.buyology.buyology_courier.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
public class AuthConfig {

    /**
     * BCrypt password encoder with strength 12.
     * Higher strength increases brute-force cost; 12 is a good production baseline.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * JWT decoder for courier-issued tokens (HMAC-SHA256).
     * Named "courierJwtDecoder" to avoid ambiguity with the Keycloak decoder.
     * Defining this bean suppresses Spring Boot's JwtDecoderAutoConfiguration,
     * so keycloakJwtDecoder() below must create the Keycloak decoder explicitly.
     */
    @Bean("courierJwtDecoder")
    NimbusJwtDecoder courierJwtDecoder(@Value("${auth.jwt.secret}") String secret) {
        SecretKeySpec key = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * JWT decoder for admin Keycloak tokens — replaces the Spring Boot auto-configured one.
     * Uses OIDC discovery (fetches JWKS from issuer/.well-known/openid-configuration).
     */
    @Bean("keycloakJwtDecoder")
    JwtDecoder keycloakJwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        return JwtDecoders.fromIssuerLocation(issuerUri);
    }
}
