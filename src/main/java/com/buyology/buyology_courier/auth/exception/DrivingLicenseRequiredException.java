package com.buyology.buyology_courier.auth.exception;

import com.buyology.buyology_courier.courier.domain.enums.VehicleType;

public class DrivingLicenseRequiredException extends RuntimeException {

    public DrivingLicenseRequiredException(VehicleType vehicleType) {
        super("Driving licence details are required for vehicle type: " + vehicleType
                + ". Please provide drivingLicenseNumber and drivingLicenseExpiry.");
    }
}
