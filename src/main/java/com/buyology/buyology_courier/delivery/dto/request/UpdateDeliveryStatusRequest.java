package com.buyology.buyology_courier.delivery.dto.request;

import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateDeliveryStatusRequest(

        @NotNull
        DeliveryStatus status,

        /** Current courier latitude — recorded in status history for audit trail. */
        BigDecimal latitude,

        /** Current courier longitude — recorded in status history for audit trail. */
        BigDecimal longitude,

        /** Optional note (e.g. "Customer not home", "Left at door"). */
        String notes
) {}
