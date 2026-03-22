package com.buyology.buyology_courier.auth.exception;

public class AccountSuspendedException extends RuntimeException {

    public AccountSuspendedException() {
        super("Account has been suspended. Please contact support.");
    }
}
