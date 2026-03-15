package com.buyology.buyology_courier.delivery.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.locationtech.jts.geom.Geometry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "delivery_zones",
        indexes = {
                @Index(name = "idx_delivery_zones_zone_code", columnList = "zone_code", unique = true),
                @Index(name = "idx_delivery_zones_is_active", columnList = "is_active")
                // NOTE: spatial GIST index on 'polygon' must be created via migration:
                // CREATE INDEX idx_delivery_zones_polygon ON delivery_zones USING GIST (polygon);
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryZone {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "zone_code", length = 50, nullable = false, unique = true)
    private String zoneCode;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "max_delivery_time_minutes")
    private Integer maxDeliveryTimeMinutes;

    @Column(name = "max_distance_km", precision = 5, scale = 2)
    private BigDecimal maxDistanceKm;

    // PostGIS geography polygon — requires GIST index via migration (see above)
    @Column(name = "polygon", columnDefinition = "geography(POLYGON, 4326)")
    private Geometry polygon;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
