package com.buyology.buyology_courier.auth.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "courier_refresh_tokens",
        indexes = {
                @Index(name = "idx_refresh_tokens_courier_id", columnList = "courier_id"),
                @Index(name = "idx_refresh_tokens_token_hash", columnList = "token_hash"),
                @Index(name = "idx_refresh_tokens_expires_at", columnList = "expires_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourierRefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "courier_id", nullable = false)
    private UUID courierId;

    // SHA-256 hex digest of the raw token — never the plain token
    @Column(name = "token_hash", length = 64, nullable = false, unique = true)
    private String tokenHash;

    @CreationTimestamp
    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    // NULL = still valid; set on logout or token rotation
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "device_info", length = 255)
    private String deviceInfo;

    // IPv4/IPv6 stored as string
    @Column(name = "ip_address", length = 45)
    private String ipAddress;
}
