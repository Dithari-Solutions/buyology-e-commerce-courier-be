package com.buyology.buyology_courier.courier.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterPushTokenRequest(
        @NotBlank(message = "fcmToken must not be blank")
        @Size(max = 512, message = "fcmToken must not exceed 512 characters")
        String fcmToken
) {}
