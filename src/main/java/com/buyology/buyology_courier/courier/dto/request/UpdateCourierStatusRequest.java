package com.buyology.buyology_courier.courier.dto.request;

import com.buyology.buyology_courier.courier.domain.enums.CourierStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record UpdateCourierStatusRequest(

        @NotNull
        CourierStatus status
) {}
