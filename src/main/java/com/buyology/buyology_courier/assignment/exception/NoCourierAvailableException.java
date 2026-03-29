package com.buyology.buyology_courier.assignment.exception;

import java.util.UUID;

public class NoCourierAvailableException extends RuntimeException {

    public NoCourierAvailableException(UUID deliveryId) {
        super("No available courier found for delivery: " + deliveryId);
    }
}
