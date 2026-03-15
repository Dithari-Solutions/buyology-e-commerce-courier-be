package com.buyology.buyology_courier.delivery.domain;

import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "delivery_status_history",
        indexes = {
                // Primary access pattern: all status events for a delivery in order
                @Index(name = "idx_delivery_status_history_delivery_created", columnList = "delivery_id, created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false, foreignKey = @ForeignKey(name = "fk_delivery_status_history_order"))
    private DeliveryOrder delivery;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50, nullable = false)
    private DeliveryStatus status;

    // Location snapshot at the time of status change
    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    // Who triggered this status change: COURIER, SYSTEM, OPS
    @Column(name = "changed_by", length = 50)
    private String changedBy;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
