package com.buyology.buyology_courier.delivery.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Real-time courier position snapshots scoped to a specific delivery.
 *
 * Separation from courier_locations is intentional:
 *  - courier_locations = full courier GPS history (operational, always recorded)
 *  - delivery_tracking  = position trail visible to the customer for a specific delivery window
 *
 * This table grows very fast under load. Consider range partitioning by recorded_at
 * and a TTL retention policy (e.g., purge rows older than 30 days post-delivery).
 */
@Entity
@Table(
        name = "delivery_tracking",
        indexes = {
                // Dominant query: replay position trail for a delivery, latest first
                @Index(name = "idx_delivery_tracking_delivery_recorded", columnList = "delivery_id, recorded_at DESC")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryTracking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false, foreignKey = @ForeignKey(name = "fk_delivery_tracking_order"))
    private DeliveryOrder delivery;

    @Column(name = "courier_lat", nullable = false, precision = 10, scale = 7)
    private BigDecimal courierLat;

    @Column(name = "courier_lng", nullable = false, precision = 10, scale = 7)
    private BigDecimal courierLng;

    @Column(name = "heading", precision = 10, scale = 7)
    private BigDecimal heading;

    @Column(name = "speed", precision = 10, scale = 2)
    private BigDecimal speed;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
