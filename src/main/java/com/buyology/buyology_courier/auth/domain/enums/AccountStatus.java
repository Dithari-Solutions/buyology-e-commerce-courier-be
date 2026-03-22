package com.buyology.buyology_courier.auth.domain.enums;

public enum AccountStatus {
    /** Courier registered by admin; initial password not yet used to log in. */
    PENDING_ACTIVATION,
    /** Normal operational state — courier can log in. */
    ACTIVE,
    /** Temporarily locked after too many failed login attempts. */
    LOCKED,
    /** Admin-suspended; cannot log in until admin reinstates. */
    SUSPENDED
}
