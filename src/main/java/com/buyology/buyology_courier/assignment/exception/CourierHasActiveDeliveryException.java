package com.buyology.buyology_courier.assignment.exception;

import java.util.UUID;

public class CourierHasActiveDeliveryException extends RuntimeException {

    public CourierHasActiveDeliveryException(UUID courierId) {
        super("Courier " + courierId + " already has an active delivery in progress.");
    }
}
