package com.buyology.buyology_courier.courier.exception;

public class DuplicatePhoneException extends RuntimeException {

    public DuplicatePhoneException(String phone) {
        super("Phone number is already registered: " + phone);
    }
}
