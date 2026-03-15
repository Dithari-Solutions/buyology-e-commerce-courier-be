package com.buyology.buyology_courier.courier.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record UpdateAvailabilityRequest(

        @NotNull
        Boolean available
) {}
