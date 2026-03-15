package com.buyology.buyology_courier.courier.exception;

public class CourierLocationNotFoundException extends RuntimeException {

    public CourierLocationNotFoundException() {
        super("No location recorded yet for this courier.");
    }
}
