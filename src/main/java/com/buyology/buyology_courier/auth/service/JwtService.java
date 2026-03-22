package com.buyology.buyology_courier.auth.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class JwtService {

    private final NimbusJwtEncoder encoder;

    @Getter
    @Value("${auth.jwt.access-token-expiry-seconds:900}")
    private long accessTokenExpirySeconds;

    @Value("${auth.jwt.issuer:buyology-courier-service}")
    private String issuer;

    @Value("${auth.jwt.refresh-token-expiry-seconds:2592000}")
    private long refreshTokenExpirySeconds;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public JwtService(@Value("${auth.jwt.secret}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(keyBytes)
                .algorithm(JWSAlgorithm.HS256)
                .build();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    /** Issue a short-lived access JWT for the given courier. */
    public String generateAccessToken(UUID courierId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(courierId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(accessTokenExpirySeconds))
                // Same claim name used by Keycloak tokens → same JwtGrantedAuthoritiesConverter works for both
                .claim("roles", List.of("COURIER"))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Generate a cryptographically random raw refresh token (URL-safe Base64, 32 bytes of entropy). */
    public String generateRawRefreshToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Compute SHA-256 hex digest — store this, never the raw token. */
    public String hashToken(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public Instant refreshTokenExpiresAt() {
        return Instant.now().plusSeconds(refreshTokenExpirySeconds);
    }
}
