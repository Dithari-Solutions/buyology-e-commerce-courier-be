package com.buyology.buyology_courier.courier.dto.request;

import com.buyology.buyology_courier.courier.domain.enums.VehicleType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import org.hibernate.validator.constraints.URL;

// All fields optional — only non-null fields are applied (PATCH semantics)
@Builder
public record UpdateCourierRequest(

        @Size(max = 100)
        String firstName,

        @Size(max = 100)
        String lastName,

        @Email @Size(max = 150)
        String email,

        VehicleType vehicleType,

        @URL(message = "Must be a valid HTTP/HTTPS URL")
        @Size(max = 2048)
        String profileImageUrl
) {}
