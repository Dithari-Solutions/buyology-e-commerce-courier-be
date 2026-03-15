package com.buyology.buyology_courier.pricing.domain;

import com.buyology.buyology_courier.courier.domain.enums.VehicleType;
import com.buyology.buyology_courier.delivery.domain.DeliveryZone;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "delivery_pricing_rules",
        indexes = {
                @Index(name = "idx_pricing_rules_is_active", columnList = "is_active"),
                @Index(name = "idx_pricing_rules_zone_id", columnList = "zone_id"),
                @Index(name = "idx_pricing_rules_vehicle_type", columnList = "vehicle_type"),
                // Find active rules for a specific zone + vehicle combination
                @Index(name = "idx_pricing_rules_active_zone_vehicle", columnList = "is_active, zone_id, vehicle_type"),
                @Index(name = "idx_pricing_rules_effective_range", columnList = "effective_from, effective_to")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryPricingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // null zone_id = applies globally; non-null = zone-specific override
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id", foreignKey = @ForeignKey(name = "fk_pricing_rules_zone"))
    private DeliveryZone zone;

    // null vehicle_type = applies to all vehicle types
    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", length = 50)
    private VehicleType vehicleType;

    @Column(name = "base_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "price_per_km", nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePerKm;

    @Column(name = "min_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal minFee;

    @Column(name = "max_distance_km", precision = 5, scale = 2)
    private BigDecimal maxDistanceKm;

    // Multiplier applied for EXPRESS priority orders
    @Column(name = "priority_multiplier", precision = 5, scale = 2)
    private BigDecimal priorityMultiplier;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    // Inclusive date range during which this rule is in effect
    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
