package com.buyology.buyology_courier.courier.domain;

import com.buyology.buyology_courier.courier.domain.enums.CourierStatus;
import com.buyology.buyology_courier.courier.domain.enums.VehicleType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "couriers",
        indexes = {
                @Index(name = "idx_couriers_status", columnList = "status"),
                @Index(name = "idx_couriers_is_available", columnList = "is_available"),
                @Index(name = "idx_couriers_status_available", columnList = "status, is_available"),
                @Index(name = "idx_couriers_deleted_at", columnList = "deleted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Courier {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // Optimistic locking — detects concurrent updates and throws ObjectOptimisticLockingFailureException
    // instead of silently overwriting another writer's changes
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "first_name", length = 100, nullable = false)
    private String firstName;

    @Column(name = "last_name", length = 100, nullable = false)
    private String lastName;

    @Column(name = "phone", length = 30, nullable = false, unique = true)
    private String phone;

    @Column(name = "email", length = 150)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", length = 50, nullable = false)
    private VehicleType vehicleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private CourierStatus status;

    @Column(name = "is_available", nullable = false)
    private boolean isAvailable;

    // Computed average — updated via background job, not per-request
    @Column(name = "rating", precision = 3, scale = 1)
    private BigDecimal rating;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Soft delete — never hard-delete a courier; historical data must remain intact
    @Column(name = "deleted_at")
    private Instant deletedAt;
}
