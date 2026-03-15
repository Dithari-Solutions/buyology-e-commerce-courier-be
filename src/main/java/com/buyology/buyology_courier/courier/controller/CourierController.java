package com.buyology.buyology_courier.courier.controller;

import com.buyology.buyology_courier.courier.domain.enums.CourierStatus;
import com.buyology.buyology_courier.courier.domain.enums.VehicleType;
import com.buyology.buyology_courier.courier.dto.request.*;
import com.buyology.buyology_courier.courier.dto.response.CourierLocationResponse;
import com.buyology.buyology_courier.courier.dto.response.CourierResponse;
import com.buyology.buyology_courier.courier.service.CourierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/couriers")
@RequiredArgsConstructor
@Tag(name = "Couriers", description = "Courier management and location tracking")
public class CourierController {

    private final CourierService courierService;

    // ── Courier CRUD ──────────────────────────────────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Register a new courier")
    public CourierResponse create(@Valid @RequestBody CreateCourierRequest request) {
        return courierService.create(request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('COURIER') and @courierSecurity.isOwner(#id, authentication))")
    @Operation(summary = "Get courier by ID")
    public CourierResponse findById(@PathVariable UUID id, Authentication authentication) {
        return courierService.findById(id);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List couriers with optional filters and pagination")
    public Page<CourierResponse> findAll(
            @RequestParam(required = false) CourierStatus status,
            @RequestParam(required = false) VehicleType vehicleType,
            @RequestParam(required = false) Boolean isAvailable,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        return courierService.findAll(status, vehicleType, isAvailable, pageable);
    }

    // PATCH — partial update of profile fields (firstName, lastName, email, etc.)
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Partially update courier profile fields")
    public CourierResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCourierRequest request
    ) {
        return courierService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update courier operational status (ACTIVE / OFFLINE / SUSPENDED)")
    public CourierResponse updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCourierStatusRequest request
    ) {
        return courierService.updateStatus(id, request);
    }

    @PatchMapping("/{id}/availability")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('COURIER') and @courierSecurity.isOwner(#id, authentication))")
    @Operation(summary = "Toggle courier availability for new deliveries")
    public CourierResponse updateAvailability(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAvailabilityRequest request,
            Authentication authentication
    ) {
        return courierService.updateAvailability(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Soft-delete a courier (preserves historical data)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        courierService.delete(id);
        return ResponseEntity.noContent().build();
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
    @PreAuthorize("hasRole('ADMIN') or (hasRole('COURIER') and @courierSecurity.isOwner(#id, authentication))")
    @Operation(summary = "Get the most recent location of a courier")
    public CourierLocationResponse getLatestLocation(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        return courierService.getLatestLocation(id);
    }

    @GetMapping("/{id}/locations")
    @PreAuthorize("hasRole('ADMIN')")
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
