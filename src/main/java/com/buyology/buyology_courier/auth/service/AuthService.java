package com.buyology.buyology_courier.auth.service;

import com.buyology.buyology_courier.auth.dto.request.CourierLoginRequest;
import com.buyology.buyology_courier.auth.dto.request.CourierSignupRequest;
import com.buyology.buyology_courier.auth.dto.request.RefreshTokenRequest;
import com.buyology.buyology_courier.auth.dto.response.AuthResponse;
import com.buyology.buyology_courier.auth.dto.response.CourierSignupResponse;

import java.util.UUID;

public interface AuthService {

    /**
     * Admin-only: create a courier with credentials and vehicle details in one transaction.
     *
     * @param request    full signup payload
     * @param adminId    UUID of the admin performing the action (from JWT sub claim)
     * @return summary of the created courier
     */
    CourierSignupResponse signup(CourierSignupRequest request, UUID adminId);

    /**
     * Courier login with phone number and password.
     *
     * @param request    login credentials
     * @param deviceInfo optional device/app info (from User-Agent header)
     * @param ipAddress  optional client IP address
     * @return access JWT + refresh token
     */
    AuthResponse login(CourierLoginRequest request, String deviceInfo, String ipAddress);

    /**
     * Issue a new access JWT from a valid refresh token.
     *
     * @param request refresh token payload
     * @return new access JWT (refresh token is NOT rotated)
     */
    AuthResponse refresh(RefreshTokenRequest request);

    /**
     * Revoke a refresh token (logout).
     *
     * @param refreshToken raw refresh token
     */
    void logout(String refreshToken);
}
