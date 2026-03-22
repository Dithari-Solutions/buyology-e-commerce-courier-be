package com.buyology.buyology_courier.auth.exception;

import java.time.Instant;

public class AccountLockedException extends RuntimeException {

    public AccountLockedException(Instant lockedUntil) {
        super("Account is temporarily locked until " + lockedUntil + ". Please try again later.");
    }
}
