package com.buyology.buyology_courier.auth.domain;

import com.buyology.buyology_courier.auth.domain.enums.AdminAction;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit record of every admin write action.
 * Never update or delete rows — this table is the forensic trail.
 */
@Entity
@Table(
        name = "admin_audit_log",
        indexes = {
                @Index(name = "idx_admin_audit_log_admin_id",    columnList = "admin_id"),
                @Index(name = "idx_admin_audit_log_resource_id", columnList = "resource_id"),
                @Index(name = "idx_admin_audit_log_created_at",  columnList = "created_at"),
                @Index(name = "idx_admin_audit_log_admin_time",  columnList = "admin_id, created_at")
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // From Keycloak JWT sub claim
    @Column(name = "admin_id", nullable = false, updatable = false)
    private UUID adminId;

    // Snapshot of the email claim at the time of action
    @Column(name = "admin_email", length = 255, updatable = false)
    private String adminEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 100, nullable = false, updatable = false)
    private AdminAction action;

    // The courier (or other resource) this action targeted
    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    // JSON string with extra context (vehicle type, status change, etc.)
    @Column(name = "details", updatable = false)
    private String details;

    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    @Column(name = "user_agent", length = 500, updatable = false)
    private String userAgent;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
