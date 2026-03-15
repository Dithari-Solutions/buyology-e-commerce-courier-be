package com.buyology.buyology_courier.courier.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Resource-level ownership checks for @PreAuthorize expressions.
 *
 * Named "courierSecurity" so SpEL can reference it as:
 *   @PreAuthorize("... and @courierSecurity.isOwner(#id, authentication)")
 *
 * Assumption: the JWT 'sub' claim holds the courier's UUID.
 * This is set during registration when the OAuth2 identity is linked to the courier record.
 * If your identity provider uses a different claim, adjust authentication.getName() accordingly.
 */
@Service("courierSecurity")
public class CourierSecurityService {

    /**
     * Returns true only when the authenticated principal's subject matches the given courier ID.
     * Used to prevent a ROLE_COURIER from modifying another courier's data.
     */
    public boolean isOwner(UUID courierId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        try {
            return courierId.equals(UUID.fromString(authentication.getName()));
        } catch (IllegalArgumentException e) {
            // JWT sub is not a UUID — identity provider misconfigured
            return false;
        }
    }
}
