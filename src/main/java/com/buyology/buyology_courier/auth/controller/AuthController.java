package com.buyology.buyology_courier.auth.controller;

import com.buyology.buyology_courier.auth.dto.request.CourierLoginRequest;
import com.buyology.buyology_courier.auth.dto.request.CourierSignupRequest;
import com.buyology.buyology_courier.auth.dto.request.RefreshTokenRequest;
import com.buyology.buyology_courier.auth.dto.response.AuthResponse;
import com.buyology.buyology_courier.auth.dto.response.CourierSignupResponse;
import com.buyology.buyology_courier.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Courier authentication — login, token refresh, logout. Admin signup.")
public class AuthController {

    private final AuthService authService;

    // ── Admin: create courier with credentials ─────────────────────────────────

    @PostMapping("/admin/couriers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Register a new courier (admin only)",
            description = "Creates the courier profile, credentials, and vehicle details in one transaction. "
                    + "Driving licence fields are required when vehicleType is SCOOTER or CAR."
    )
    public CourierSignupResponse signup(
            @Valid @RequestBody CourierSignupRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID adminId = UUID.fromString(jwt.getSubject());
        return authService.signup(request, adminId);
    }

    // ── Courier: login ─────────────────────────────────────────────────────────

    @PostMapping("/courier/login")
    @Operation(
            summary = "Courier login",
            description = "Authenticate with phone number and password. Returns a short-lived access JWT "
                    + "and a long-lived refresh token."
    )
    public AuthResponse login(
            @Valid @RequestBody CourierLoginRequest request,
            HttpServletRequest httpRequest
    ) {
        String deviceInfo = httpRequest.getHeader("User-Agent");
        String ipAddress  = resolveClientIp(httpRequest);
        return authService.login(request, deviceInfo, ipAddress);
    }

    // ── Courier: refresh ───────────────────────────────────────────────────────

    @PostMapping("/courier/refresh")
    @Operation(
            summary = "Refresh access token",
            description = "Exchange a valid refresh token for a new access JWT. "
                    + "The refresh token itself is not rotated."
    )
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    // ── Courier: logout ────────────────────────────────────────────────────────

    @PostMapping("/courier/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Logout",
            description = "Revoke the supplied refresh token. The access JWT remains valid until expiry "
                    + "— keep its TTL short (≤ 15 min)."
    )
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.refreshToken());
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For may contain a comma-separated list; take the first (client)
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
