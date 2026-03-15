package com.buyology.buyology_courier.courier.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "courier_locations",
        indexes = {
                // Most critical index: fetch latest location for a specific courier
                @Index(name = "idx_courier_locations_courier_recorded", columnList = "courier_id, recorded_at DESC"),
                @Index(name = "idx_courier_locations_recorded_at", columnList = "recorded_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourierLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "courier_id", nullable = false, foreignKey = @ForeignKey(name = "fk_courier_locations_courier"))
    private Courier courier;

    @Column(name = "latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    // Degrees 0–360 from north
    @Column(name = "heading", precision = 10, scale = 7)
    private BigDecimal heading;

    // km/h
    @Column(name = "speed", precision = 10, scale = 2)
    private BigDecimal speed;

    // GPS accuracy radius in meters
    @Column(name = "accuracy_meters", precision = 8, scale = 2)
    private BigDecimal accuracyMeters;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
