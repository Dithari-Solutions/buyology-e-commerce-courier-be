package com.buyology.buyology_courier.delivery.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CancelDeliveryRequest(

        @NotBlank
        String reason
) {}
