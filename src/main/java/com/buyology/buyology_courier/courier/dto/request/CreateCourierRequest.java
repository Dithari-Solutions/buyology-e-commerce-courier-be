package com.buyology.buyology_courier.courier.dto.request;

import com.buyology.buyology_courier.courier.domain.enums.VehicleType;
import jakarta.validation.constraints.*;
import lombok.Builder;

/**
 * Legacy profile-only courier creation.
 * Images are uploaded as multipart file parts — not as URL strings.
 * See {@code POST /api/v1/couriers} in CourierController.
 */
@Builder
public record CreateCourierRequest(

        @NotBlank @Size(max = 100)
        String firstName,

        @NotBlank @Size(max = 100)
        String lastName,

        @NotBlank @Size(max = 30)
        String phone,

        @Email @Size(max = 150)
        String email,

        @NotNull
        VehicleType vehicleType
) {}
