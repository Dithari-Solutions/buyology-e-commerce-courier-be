package com.buyology.buyology_courier.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CourierLoginRequest(

        @NotBlank @Size(max = 30)
        String phoneNumber,

        @NotBlank @Size(max = 100)
        String password
) {}
