package com.buyology.buyology_courier.auth.dto.request;

import com.buyology.buyology_courier.courier.domain.enums.VehicleType;
import jakarta.validation.constraints.*;
import lombok.Builder;

import java.time.LocalDate;

/**
 * Admin-only payload to register a new courier with credentials and vehicle details.
 * Image files (profile photo, vehicle registration, driving licence front/back)
 * are uploaded as multipart file parts alongside this JSON part — not as URL strings.
 *
 * Driving-licence fields are conditionally required based on {@link VehicleType}:
 * SCOOTER / CAR → required; BICYCLE / FOOT → must be null.
 */
@Builder
public record CourierSignupRequest(

        // ── Personal details ──────────────────────────────────────────────────
        @NotBlank @Size(max = 100)
        String firstName,

        @NotBlank @Size(max = 100)
        String lastName,

        @NotBlank @Size(max = 30)
        String phone,

        @Email @Size(max = 150)
        String email,

        // ── Auth ──────────────────────────────────────────────────────────────
        @NotBlank @Size(min = 8, max = 100, message = "Password must be 8–100 characters")
        String initialPassword,

        // ── Vehicle details ───────────────────────────────────────────────────
        @NotNull
        VehicleType vehicleType,

        @Size(max = 100) String vehicleMake,
        @Size(max = 100) String vehicleModel,
        @Min(1900) @Max(2100) Integer vehicleYear,
        @Size(max = 50)  String vehicleColor,
        @Size(max = 50)  String licensePlate,

        // ── Driving licence (required for SCOOTER / CAR) ─────────────────────
        @Size(max = 100) String drivingLicenseNumber,
        LocalDate drivingLicenseExpiry
) {}
