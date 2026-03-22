package com.buyology.buyology_courier.auth.exception;

public class TokenExpiredException extends RuntimeException {

    public TokenExpiredException() {
        super("Refresh token has expired. Please log in again.");
    }
}
