package com.buyology.buyology_courier.delivery.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record FailDeliveryRequest(

        @NotBlank
        @Size(max = 500)
        String reason,

        /** Courier's current latitude at the time of failure — recorded in status history. */
        BigDecimal latitude,

        /** Courier's current longitude at the time of failure — recorded in status history. */
        BigDecimal longitude
) {}
