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

    /** Average travel speed used for delivery-time estimation during courier assignment. */
    public double speedKmh() {
        return switch (this) {
            case FOOT     -> 5.0;
            case BICYCLE  -> 15.0;
            case SCOOTER  -> 35.0;
            case CAR      -> 40.0;
        };
    }
}
