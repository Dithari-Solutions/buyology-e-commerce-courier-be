package com.buyology.buyology_courier.courier.messaging.event;

import com.buyology.buyology_courier.courier.domain.enums.VehicleType;

import java.time.Instant;
import java.util.UUID;

public record CourierRegisteredEvent(
        int eventVersion,
        UUID courierId,
        String firstName,
        String lastName,
        String phone,
        String email,
        VehicleType vehicleType,
        Instant occurredAt
) {
    public static CourierRegisteredEvent of(UUID courierId, String firstName, String lastName,
                                            String phone, String email, VehicleType vehicleType) {
        return new CourierRegisteredEvent(1, courierId, firstName, lastName, phone, email, vehicleType, Instant.now());
    }
}
