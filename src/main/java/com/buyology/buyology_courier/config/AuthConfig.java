package com.buyology.buyology_courier.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

@Slf4j
@Configuration
@EnableConfigurationProperties(EcommerceJwtProperties.class)
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
    NimbusJwtDecoder courierJwtDecoder(
            @Value("${auth.jwt.secret}") String secret,
            @Value("${auth.jwt.issuer:buyology-courier-service}") String issuer
    ) {
        SecretKeySpec key = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        // Validate issuer on courier tokens so a Keycloak token is never accepted here.
        // createDefaultWithIssuer already includes JwtTimestampValidator (exp/nbf/iat).
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
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
    /**
     * JWT decoder for ecommerce-backend service-to-service tokens (RSA-256).
     * The ecommerce backend signs tokens with its private key.
     * The courier service verifies them with the corresponding public key stored in
     * src/main/resources/ecommerce-public.pem — committed to the repo since public keys
     * are not sensitive. No shared secret to manage or rotate on both sides.
     */
    @Bean("ecommerceServiceJwtDecoder")
    NimbusJwtDecoder ecommerceServiceJwtDecoder(
            @Value("${ecommerce.service.jwt.public-key-location:classpath:ecommerce-public.pem}") Resource publicKeyResource,
            @Value("${ecommerce.service.jwt.issuer:buyology-ecommerce-service}") String issuer
    ) throws Exception {
        String pem = new String(publicKeyResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        RSAPublicKey publicKey = parseRsaPublicKey(pem);
        log.info("[ECOMMERCE-DECODER] loaded RSA public key modulus={}bits issuer='{}'",
                publicKey.getModulus().bitLength(), issuer);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    private RSAPublicKey parseRsaPublicKey(String pem) throws Exception {
        String cleaned = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(cleaned);
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    @Bean("keycloakJwtDecoder")
    JwtDecoder keycloakJwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${auth.admin.jwt.audience:buyology-courier-service}") String expectedAudience
    ) {
        // withJwkSetUri defers the HTTP call to Keycloak until a token is actually validated,
        // so the app starts up even if Keycloak is not yet reachable.
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(issuerUri + "/protocol/openid-connect/certs")
                .build();

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
