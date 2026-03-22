package com.buyology.buyology_courier.auth.dto.response;

import com.buyology.buyology_courier.auth.domain.enums.AccountStatus;
import com.buyology.buyology_courier.courier.domain.enums.VehicleType;

import java.util.UUID;

public record CourierSignupResponse(
        UUID          courierId,
        String        firstName,
        String        lastName,
        String        phone,
        AccountStatus accountStatus,
        VehicleType   vehicleType,
        boolean       requiresDrivingLicense
) {}
