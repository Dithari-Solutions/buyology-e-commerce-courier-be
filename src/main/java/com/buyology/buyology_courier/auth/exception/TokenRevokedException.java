package com.buyology.buyology_courier.auth.exception;

public class TokenRevokedException extends RuntimeException {

    public TokenRevokedException() {
        super("Refresh token has been revoked. Please log in again.");
    }
}
