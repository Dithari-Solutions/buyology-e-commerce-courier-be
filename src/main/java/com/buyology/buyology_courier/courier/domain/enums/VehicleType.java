package com.buyology.buyology_courier.courier.domain.enums;

public enum VehicleType {
    // Non-motorized — driving licence NOT required
    BICYCLE,
    FOOT,

    // Motorized — driving licence REQUIRED
    SCOOTER,
    CAR;

    public boolean requiresDrivingLicense() {
        return this == SCOOTER || this == CAR;
    }
}
