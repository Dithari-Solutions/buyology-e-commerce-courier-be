package com.buyology.buyology_courier.auth.exception;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        // Deliberately vague — do not reveal whether the phone or password was wrong
        super("Invalid phone number or password.");
    }
}
