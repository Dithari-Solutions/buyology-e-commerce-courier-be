package com.buyology.buyology_courier.courier.exception;

import com.buyology.buyology_courier.courier.domain.enums.CourierStatus;

public class CourierNotActiveException extends RuntimeException {

    public CourierNotActiveException(CourierStatus currentStatus) {
        super("Courier must be ACTIVE for this operation. Current status: " + currentStatus);
    }
}
