package com.buyology.buyology_courier.courier.controller;

import com.buyology.buyology_courier.auth.domain.enums.AdminAction;
import com.buyology.buyology_courier.auth.service.AdminAuditService;
import com.buyology.buyology_courier.common.storage.FileStorageService;
import com.buyology.buyology_courier.courier.domain.enums.CourierStatus;
import com.buyology.buyology_courier.courier.domain.enums.VehicleType;
import com.buyology.buyology_courier.courier.dto.request.*;
import com.buyology.buyology_courier.courier.dto.response.CourierLocationResponse;
import com.buyology.buyology_courier.courier.dto.response.CourierResponse;
import com.buyology.buyology_courier.courier.service.CourierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/couriers")
@RequiredArgsConstructor
@Tag(name = "Couriers", description = "Courier management and location tracking")
public class CourierController {

    private final CourierService     courierService;
    private final AdminAuditService  adminAuditService;
    private final FileStorageService fileStorageService;

    // ── Courier CRUD ──────────────────────────────────────────────────────────

    /**
     * Legacy profile-only creation (no credentials or vehicle details).
     * Prefer POST /api/auth/admin/couriers for full onboarding with auth.
     *
     * Request format: multipart/form-data
     *   - Part "data"               — JSON-encoded CreateCourierRequest
     *   - Part "profileImage"       — profile photo (JPEG/PNG/WebP, max 10 MB, optional)
     *   - Part "drivingLicenceImage" — driving licence front photo (optional; provide for SCOOTER/CAR)
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(summary = "Register a new courier profile — multipart form (no credentials; use /api/auth/admin/couriers for full onboarding)")
    public CourierResponse create(
            @RequestPart("data") @Valid CreateCourierRequest request,
            @RequestPart(value = "profileImage", required = false) MultipartFile profileImage,
            @RequestPart(value = "drivingLicenceImage", required = false) MultipartFile drivingLicenceImage,
            HttpServletRequest httpRequest
    ) {
        String profileImageUrl       = profileImage       != null && !profileImage.isEmpty()
                ? fileStorageService.store(profileImage, "profile") : null;
        String drivingLicenceImageUrl = drivingLicenceImage != null && !drivingLicenceImage.isEmpty()
                ? fileStorageService.store(drivingLicenceImage, "licence") : null;

        CourierResponse response = courierService.create(request, profileImageUrl, drivingLicenceImageUrl);
        adminAuditService.log(AdminAction.COURIER_CREATED, response.id(),
                "{\"source\":\"profile-only\"}", httpRequest);
        return response;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN') or (hasRole('COURIER') and @courierSecurity.isOwner(#id, authentication))")
    @Operation(summary = "Get courier by ID — includes profileImageUrl and drivingLicenceImageUrl")
    public CourierResponse findById(@PathVariable UUID id, Authentication authentication) {
        return courierService.findById(id);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(summary = "List couriers with optional filters and pagination")
    public Page<CourierResponse> findAll(
            @RequestParam(required = false) CourierStatus status,
            @RequestParam(required = false) VehicleType vehicleType,
            @RequestParam(required = false) Boolean isAvailable,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        return courierService.findAll(status, vehicleType, isAvailable, pageable);
    }

    /**
     * Partially update courier profile fields.
     *
     * Request format: multipart/form-data
     *   - Part "data"               — JSON-encoded UpdateCourierRequest (all fields optional)
     *   - Part "profileImage"       — new profile photo (optional; replaces existing)
     *   - Part "drivingLicenceImage" — new licence photo (optional; replaces existing)
     */
    @PatchMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(summary = "Partially update courier profile — multipart form")
    public CourierResponse update(
            @PathVariable UUID id,
            @RequestPart("data") @Valid UpdateCourierRequest request,
            @RequestPart(value = "profileImage", required = false) MultipartFile profileImage,
            @RequestPart(value = "drivingLicenceImage", required = false) MultipartFile drivingLicenceImage,
            HttpServletRequest httpRequest
    ) {
        String profileImageUrl       = profileImage       != null && !profileImage.isEmpty()
                ? fileStorageService.store(profileImage, "profile") : null;
        String drivingLicenceImageUrl = drivingLicenceImage != null && !drivingLicenceImage.isEmpty()
                ? fileStorageService.store(drivingLicenceImage, "licence") : null;

        CourierResponse response = courierService.update(id, request, profileImageUrl, drivingLicenceImageUrl);
        adminAuditService.log(AdminAction.COURIER_UPDATED, id, null, httpRequest);
        return response;
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(summary = "Update courier operational status (ACTIVE / OFFLINE / SUSPENDED)")
    public CourierResponse updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCourierStatusRequest request,
            HttpServletRequest httpRequest
    ) {
        CourierResponse response = courierService.updateStatus(id, request);
        adminAuditService.log(AdminAction.COURIER_STATUS_CHANGED, id,
                "{\"newStatus\":\"" + request.status() + "\"}", httpRequest);
        return response;
    }

    @PatchMapping("/{id}/availability")
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN') or (hasRole('COURIER') and @courierSecurity.isOwner(#id, authentication))")
    @Operation(summary = "Toggle courier availability for new deliveries")
    public CourierResponse updateAvailability(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAvailabilityRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        CourierResponse response = courierService.updateAvailability(id, request);
        // Only log when an admin changes availability (not when courier toggles their own)
        if (authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")
                        || a.getAuthority().equals("ROLE_COURIER_ADMIN"))) {
            adminAuditService.log(AdminAction.COURIER_AVAILABILITY_CHANGED, id,
                    "{\"available\":" + request.available() + "}", httpRequest);
        }
        return response;
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(summary = "Soft-delete a courier (preserves historical data)")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            HttpServletRequest httpRequest
    ) {
        courierService.delete(id);
        adminAuditService.log(AdminAction.COURIER_DELETED, id, null, httpRequest);
        return ResponseEntity.noContent().build();
    }

    // ── Push token ────────────────────────────────────────────────────────────

    /**
     * Registers or replaces the courier's FCM device token.
     * Called by the mobile app immediately after login.
     * The courier ID is extracted from the JWT — no path param needed.
     */
    @PostMapping("/push-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('COURIER')")
    @Operation(summary = "Register or update the courier's FCM push-notification token")
    public void registerPushToken(
            @Valid @RequestBody RegisterPushTokenRequest request,
            Authentication authentication
    ) {
        UUID courierId = UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
        courierService.registerPushToken(courierId, request);
    }

    // ── Location ──────────────────────────────────────────────────────────────

    @PostMapping("/{id}/locations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('COURIER') and @courierSecurity.isOwner(#id, authentication)")
    @Operation(summary = "Record a GPS location ping for a courier")
    public CourierLocationResponse recordLocation(
            @PathVariable UUID id,
            @Valid @RequestBody RecordLocationRequest request,
            Authentication authentication
    ) {
        return courierService.recordLocation(id, request);
    }

    @GetMapping("/{id}/locations/latest")
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN') or (hasRole('COURIER') and @courierSecurity.isOwner(#id, authentication))")
    @Operation(summary = "Get the most recent location of a courier")
    public CourierLocationResponse getLatestLocation(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        return courierService.getLatestLocation(id);
    }

    @GetMapping("/{id}/locations")
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(summary = "Get paginated location history within a time window (max 7 days)")
    public Page<CourierLocationResponse> getLocationHistory(
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 50, sort = "recordedAt") Pageable pageable
    ) {
        return courierService.getLocationHistory(id, from, to, pageable);
    }
}
