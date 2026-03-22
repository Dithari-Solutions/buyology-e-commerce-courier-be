package com.buyology.buyology_courier.auth.domain;

import com.buyology.buyology_courier.auth.domain.enums.AccountStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "courier_credentials",
        indexes = {
                @Index(name = "idx_courier_credentials_courier_id", columnList = "courier_id"),
                @Index(name = "idx_courier_credentials_phone",      columnList = "phone_number"),
                @Index(name = "idx_courier_credentials_status",     columnList = "account_status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourierCredentials {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // 1-to-1 with couriers; UNIQUE enforced by DB constraint
    @Column(name = "courier_id", nullable = false, unique = true)
    private UUID courierId;

    // Login identifier — mirrors couriers.phone to avoid a join on auth hot path
    @Column(name = "phone_number", length = 30, nullable = false, unique = true)
    private String phoneNumber;

    // BCrypt hash (strength ≥ 12). Never plain-text.
    @Column(name = "password_hash", length = 255, nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", length = 30, nullable = false)
    private AccountStatus accountStatus;

    // Incremented on each failed login attempt; reset to 0 on success
    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    // Non-null = locked until this instant regardless of account_status
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    // UUID of the admin who created this account — not a FK (admin lives in Keycloak)
    @Column(name = "created_by_admin_id")
    private UUID createdByAdminId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
