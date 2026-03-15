package com.buyology.buyology_courier.courier.dto.request;

import com.buyology.buyology_courier.courier.domain.enums.VehicleType;
import jakarta.validation.constraints.*;
import lombok.Builder;
import org.hibernate.validator.constraints.URL;

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
        VehicleType vehicleType,

        @URL(message = "Must be a valid HTTP/HTTPS URL")
        @Size(max = 2048)
        String profileImageUrl
) {}
