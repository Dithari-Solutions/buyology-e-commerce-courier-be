package com.buyology.buyology_courier.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
     * JWT decoder for courier-issued tokens (HMAC-SHA256, self-signed).
     * Defining any JwtDecoder bean suppresses Spring Boot's auto-configuration,
     * so the Keycloak decoder below must be created explicitly too.
     */
    @Bean("courierJwtDecoder")
    NimbusJwtDecoder courierJwtDecoder(@Value("${auth.jwt.secret}") String secret) {
        SecretKeySpec key = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        // Validate issuer on courier tokens so a Keycloak token is never accepted here
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("${auth.jwt.issuer:buyology-courier-service}"),
                new JwtTimestampValidator()
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * JWT decoder for admin Keycloak tokens.
     *
     * Security hardening applied here:
     *
     * 1. Issuer validation  — rejects tokens not issued by this realm.
     * 2. Audience validation — rejects tokens where the `aud` claim does not include
     *    the value of `auth.admin.jwt.audience` (default: buyology-courier-service).
     *    This is the critical Option-A guard: even if an admin's Keycloak JWT is stolen
     *    from the main buyology app, it cannot be replayed against this service unless
     *    Keycloak explicitly issued it with `aud = buyology-courier-service`.
     * 3. Timestamp validation — exp / nbf / iat checked by JwtValidators.createDefault*.
     *
     * Keycloak configuration required:
     *   Client → buyology-courier-service → Settings → "Audience" mapper → add
     *   "buyology-courier-service" to the access token audience.
     */
    @Bean("keycloakJwtDecoder")
    JwtDecoder keycloakJwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${auth.admin.jwt.audience:buyology-courier-service}") String expectedAudience
    ) {
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);

        OAuth2TokenValidator<Jwt> issuerValidator    = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> audienceValidator  = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(expectedAudience)
        );

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
        return decoder;
    }
}
