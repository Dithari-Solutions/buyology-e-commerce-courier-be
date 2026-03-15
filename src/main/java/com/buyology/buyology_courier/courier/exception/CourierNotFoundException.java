package com.buyology.buyology_courier.courier.exception;

public class CourierNotFoundException extends RuntimeException {

    public CourierNotFoundException() {
        super("The requested courier was not found.");
    }
}
