package com.buyology.buyology_courier.auth.exception;

public class AccountNotActiveException extends RuntimeException {

    public AccountNotActiveException() {
        super("Account is not active. Please contact your administrator.");
    }
}
