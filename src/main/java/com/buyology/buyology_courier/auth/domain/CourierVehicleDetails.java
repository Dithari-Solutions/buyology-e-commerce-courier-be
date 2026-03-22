package com.buyology.buyology_courier.auth.domain;

import com.buyology.buyology_courier.courier.domain.enums.VehicleType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "courier_vehicle_details",
        indexes = {
                @Index(name = "idx_vehicle_details_courier_id",    columnList = "courier_id"),
                @Index(name = "idx_vehicle_details_vehicle_type",  columnList = "vehicle_type"),
                @Index(name = "idx_vehicle_details_license_plate", columnList = "license_plate")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourierVehicleDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // 1-to-1 with couriers; UNIQUE enforced by DB constraint
    @Column(name = "courier_id", nullable = false, unique = true)
    private UUID courierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", length = 30, nullable = false)
    private VehicleType vehicleType;

    // Physical vehicle — NULL for BICYCLE / FOOT
    @Column(name = "vehicle_make",  length = 100)
    private String vehicleMake;

    @Column(name = "vehicle_model", length = 100)
    private String vehicleModel;

    @Column(name = "vehicle_year")
    private Integer vehicleYear;

    @Column(name = "vehicle_color", length = 50)
    private String vehicleColor;

    // UNIQUE when present (DB constraint); NULL for BICYCLE / FOOT
    @Column(name = "license_plate", length = 50, unique = true)
    private String licensePlate;

    @Column(name = "vehicle_registration_url")
    private String vehicleRegistrationUrl;

    // ── Driving licence (SCOOTER / CAR only) ──────────────────────────────────

    @Column(name = "driving_license_number", length = 100)
    private String drivingLicenseNumber;

    @Column(name = "driving_license_expiry")
    private LocalDate drivingLicenseExpiry;

    @Column(name = "driving_license_front_url")
    private String drivingLicenseFrontUrl;

    @Column(name = "driving_license_back_url")
    private String drivingLicenseBackUrl;

    // Set by the application from VehicleType.requiresDrivingLicense(); read-only after insert
    @Column(name = "requires_driving_license", nullable = false)
    private boolean requiresDrivingLicense;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
