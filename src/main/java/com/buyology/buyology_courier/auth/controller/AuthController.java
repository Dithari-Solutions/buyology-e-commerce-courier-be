package com.buyology.buyology_courier.auth.controller;

import com.buyology.buyology_courier.auth.domain.enums.AdminAction;
import com.buyology.buyology_courier.auth.dto.request.CourierLoginRequest;
import com.buyology.buyology_courier.auth.dto.request.CourierSignupRequest;
import com.buyology.buyology_courier.auth.dto.request.RefreshTokenRequest;
import com.buyology.buyology_courier.auth.dto.response.AuthResponse;
import com.buyology.buyology_courier.auth.dto.response.CourierSignupResponse;
import com.buyology.buyology_courier.auth.service.AdminAuditService;
import com.buyology.buyology_courier.auth.service.AuthService;
import com.buyology.buyology_courier.common.storage.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Courier authentication — login, token refresh, logout. Admin signup.")
public class AuthController {

    private final AuthService        authService;
    private final AdminAuditService  adminAuditService;
    private final FileStorageService fileStorageService;

    // ── Admin: create courier with credentials ─────────────────────────────────

    /**
     * Creates courier profile, credentials, and vehicle details in one transaction.
     *
     * Request format: multipart/form-data
     *   - Part "data"                — JSON-encoded CourierSignupRequest
     *   - Part "profileImage"        — profile photo (JPEG/PNG/WebP, max 10 MB, optional)
     *   - Part "vehicleRegistration" — vehicle registration doc photo (optional)
     *   - Part "drivingLicenceFront" — driving licence front (required for SCOOTER/CAR)
     *   - Part "drivingLicenceBack"  — driving licence back  (required for SCOOTER/CAR)
     *
     * ROLE_COURIER_ADMIN is the least-privilege role; ROLE_ADMIN is also accepted.
     */
    @PostMapping(value = "/admin/couriers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(
            summary = "Register a new courier (admin only) — multipart form",
            description = "Creates the courier profile, credentials, and vehicle details in one transaction. "
                    + "Driving licence images are required when vehicleType is SCOOTER or CAR. "
                    + "Requires ROLE_COURIER_ADMIN or ROLE_ADMIN."
    )
    public CourierSignupResponse signup(
            @RequestPart("data") @Valid CourierSignupRequest request,
            @RequestPart(value = "profileImage",        required = false) MultipartFile profileImage,
            @RequestPart(value = "vehicleRegistration", required = false) MultipartFile vehicleRegistration,
            @RequestPart(value = "drivingLicenceFront", required = false) MultipartFile drivingLicenceFront,
            @RequestPart(value = "drivingLicenceBack",  required = false) MultipartFile drivingLicenceBack,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest
    ) {
        String profileImageUrl       = storeIfPresent(profileImage,        "profile");
        String vehicleRegistrationUrl = storeIfPresent(vehicleRegistration, "vehicle-registration");
        String drivingLicenceFrontUrl = storeIfPresent(drivingLicenceFront, "licence");
        String drivingLicenceBackUrl  = storeIfPresent(drivingLicenceBack,  "licence");

        UUID adminId = UUID.fromString(jwt.getSubject());
        CourierSignupResponse response = authService.signup(
                request, adminId,
                profileImageUrl, vehicleRegistrationUrl,
                drivingLicenceFrontUrl, drivingLicenceBackUrl
        );

        adminAuditService.log(
                AdminAction.COURIER_CREATED,
                response.courierId(),
                "{\"vehicleType\":\"" + request.vehicleType() + "\","
                        + "\"requiresDrivingLicense\":" + response.requiresDrivingLicense() + "}",
                httpRequest
        );

        return response;
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
            description = "Exchange a valid refresh token for a new access JWT."
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

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String storeIfPresent(MultipartFile file, String subDir) {
        return (file != null && !file.isEmpty())
                ? fileStorageService.store(file, subDir)
                : null;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
