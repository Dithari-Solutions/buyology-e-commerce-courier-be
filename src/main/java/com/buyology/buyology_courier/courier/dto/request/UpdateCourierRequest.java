package com.buyology.buyology_courier.courier.dto.request;

import com.buyology.buyology_courier.courier.domain.enums.VehicleType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Builder;

// All fields optional — only non-null fields are applied (PATCH semantics).
// Images are uploaded as multipart file parts — not as URL strings.
@Builder
public record UpdateCourierRequest(

        @Size(max = 100)
        String firstName,

        @Size(max = 100)
        String lastName,

        @Email @Size(max = 150)
        String email,

        VehicleType vehicleType
) {}
