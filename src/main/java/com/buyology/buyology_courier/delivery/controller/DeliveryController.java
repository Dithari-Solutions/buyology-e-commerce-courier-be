package com.buyology.buyology_courier.delivery.controller;

import com.buyology.buyology_courier.common.storage.FileStorageService;
import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;
import com.buyology.buyology_courier.delivery.dto.request.CancelDeliveryRequest;
import com.buyology.buyology_courier.delivery.dto.request.FailDeliveryRequest;
import com.buyology.buyology_courier.delivery.dto.request.UpdateDeliveryStatusRequest;
import com.buyology.buyology_courier.delivery.dto.response.DeliveryOrderResponse;
import com.buyology.buyology_courier.delivery.dto.response.DeliveryProofResponse;
import com.buyology.buyology_courier.delivery.dto.response.DeliveryStatusHistoryResponse;
import com.buyology.buyology_courier.delivery.service.DeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST API for the delivery module.
 *
 * <h3>Auth model</h3>
 * <ul>
 *   <li><b>COURIER</b> — authenticated via courier-issued HMAC-SHA256 JWT
 *       (issued by this service after login).</li>
 *   <li><b>ECOMMERCE_SERVICE</b> — authenticated via RSA-256 JWT issued by the
 *       ecommerce backend, validated with {@code ecommerce-public.pem}.</li>
 *   <li><b>ADMIN / COURIER_ADMIN</b> — authenticated via Keycloak RSA JWT.</li>
 * </ul>
 *
 * <p>Most delivery creation happens asynchronously via RabbitMQ
 * ({@link com.buyology.buyology_courier.delivery.messaging.consumer.DeliveryOrderConsumer}).
 * The REST endpoints here cover status queries and state transitions.</p>
 */
@RestController
@RequestMapping("/api/v1/deliveries")
@RequiredArgsConstructor
@Tag(name = "Deliveries", description = "Delivery order lifecycle management")
public class DeliveryController {

    private final DeliveryService    deliveryService;
    private final FileStorageService fileStorageService;

    // ── Admin / ecommerce reads ───────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN', 'ECOMMERCE_SERVICE')")
    @Operation(summary = "List delivery orders, optionally filtered by status")
    public Page<DeliveryOrderResponse> findAll(
            @RequestParam(required = false) DeliveryStatus status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        return deliveryService.findByStatus(status, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN', 'ECOMMERCE_SERVICE') " +
                  "or (hasRole('COURIER') and @deliverySecurity.isAssignedCourier(#id, authentication))")
    @Operation(summary = "Get delivery order by ID")
    public DeliveryOrderResponse findById(@PathVariable UUID id, Authentication authentication) {
        return deliveryService.findById(id);
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN', 'ECOMMERCE_SERVICE') " +
                  "or (hasRole('COURIER') and @deliverySecurity.isAssignedCourier(#id, authentication))")
    @Operation(summary = "Get full status history for a delivery order")
    public List<DeliveryStatusHistoryResponse> getStatusHistory(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        return deliveryService.getStatusHistory(id);
    }

    // ── Courier reads ─────────────────────────────────────────────────────────

    @GetMapping("/my-deliveries")
    @PreAuthorize("hasRole('COURIER')")
    @Operation(summary = "Get all active (non-terminal) deliveries assigned to the authenticated courier")
    public Page<DeliveryOrderResponse> getMyDeliveries(
            Authentication authentication,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        UUID courierId = UUID.fromString(authentication.getName());
        return deliveryService.findAssignedToCourier(courierId, pageable);
    }

    @GetMapping("/my-history")
    @PreAuthorize("hasRole('COURIER')")
    @Operation(summary = "Full delivery history for the courier (all statuses). "
            + "Pass ?status=DELIVERED to filter by a specific status.")
    public Page<DeliveryOrderResponse> getMyHistory(
            @RequestParam(required = false) DeliveryStatus status,
            Authentication authentication,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        UUID courierId = UUID.fromString(authentication.getName());
        return deliveryService.findAllByCourier(courierId, status, pageable);
    }

    @GetMapping("/{id}/proof")
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN', 'ECOMMERCE_SERVICE') "
            + "or (hasRole('COURIER') and @deliverySecurity.isAssignedCourier(#id, authentication))")
    @Operation(summary = "Get the proof record (pickup + delivery photos) for a delivery")
    public DeliveryProofResponse getProof(@PathVariable UUID id, Authentication authentication) {
        return deliveryService.getProof(id);
    }

    // ── Courier mutations ─────────────────────────────────────────────────────

    @RequestMapping(value = "/{id}/status", method = {RequestMethod.PATCH, RequestMethod.POST})
    @PreAuthorize("hasRole('COURIER')")
    @Operation(summary = "Update delivery status — use for transitions that don't require photo evidence "
            + "(e.g. COURIER_ACCEPTED → ARRIVED_AT_PICKUP, PICKED_UP → ON_THE_WAY, ON_THE_WAY → ARRIVED_AT_DESTINATION). "
            + "For PICKED_UP use POST /{id}/actions/pickup-proof; for DELIVERED use POST /{id}/actions/deliver-proof.")
    public DeliveryOrderResponse updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDeliveryStatusRequest request,
            Authentication authentication
    ) {
        UUID courierId = UUID.fromString(authentication.getName());
        return deliveryService.updateStatus(id, courierId, request);
    }

    /**
     * Courier taps "I have the package" in the mobile app.
     * Accepts a photo of the package at pickup → transitions ARRIVED_AT_PICKUP → PICKED_UP.
     *
     * <p>Request: multipart/form-data
     * <ul>
     *   <li>{@code photo} — JPEG/PNG image (required)</li>
     *   <li>{@code photoTakenAt} — ISO-8601 timestamp (optional, defaults to server time)</li>
     * </ul>
     */
    @PostMapping(value = "/{id}/actions/pickup-proof", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('COURIER')")
    @Operation(summary = "Submit pickup proof photo — transitions ARRIVED_AT_PICKUP → PICKED_UP")
    public DeliveryProofResponse submitPickupProof(
            @PathVariable UUID id,
            @RequestPart("photo") MultipartFile photo,
            @RequestParam(required = false) Instant photoTakenAt,
            Authentication authentication
    ) {
        UUID courierId = UUID.fromString(authentication.getName());
        String imageUrl = fileStorageService.store(photo, "pickup-proof");
        return deliveryService.submitPickupProof(id, courierId, imageUrl, photoTakenAt);
    }

    /**
     * Courier taps "Package delivered" in the mobile app.
     * Accepts a photo of the delivered package → transitions ARRIVED_AT_DESTINATION → DELIVERED.
     *
     * <p>Request: multipart/form-data
     * <ul>
     *   <li>{@code photo} — JPEG/PNG image (required)</li>
     *   <li>{@code deliveredTo} — name of person who received the package (optional)</li>
     *   <li>{@code photoTakenAt} — ISO-8601 timestamp (optional, defaults to server time)</li>
     * </ul>
     */
    @PostMapping(value = "/{id}/actions/deliver-proof", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('COURIER')")
    @Operation(summary = "Submit delivery proof photo — transitions ARRIVED_AT_DESTINATION → DELIVERED")
    public DeliveryProofResponse submitDeliveryProof(
            @PathVariable UUID id,
            @RequestPart("photo") MultipartFile photo,
            @RequestParam(required = false) String deliveredTo,
            @RequestParam(required = false) Instant photoTakenAt,
            Authentication authentication
    ) {
        UUID courierId = UUID.fromString(authentication.getName());
        String imageUrl = fileStorageService.store(photo, "delivery-proof");
        return deliveryService.submitDeliveryProof(id, courierId, imageUrl, deliveredTo, photoTakenAt);
    }

    /**
     * Courier reports that a delivery could not be completed.
     * Transitions any in-progress status → FAILED.
     */
    @PostMapping("/{id}/actions/fail")
    @PreAuthorize("hasRole('COURIER')")
    @Operation(summary = "Report a failed delivery — transitions any in-progress status → FAILED")
    public DeliveryOrderResponse failDelivery(
            @PathVariable UUID id,
            @Valid @RequestBody FailDeliveryRequest request,
            Authentication authentication
    ) {
        UUID courierId = UUID.fromString(authentication.getName());
        return deliveryService.failDelivery(id, courierId, request);
    }

    // ── Admin / ecommerce mutations ───────────────────────────────────────────

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN', 'ECOMMERCE_SERVICE')")
    @Operation(summary = "Cancel a delivery order — terminal action, cannot be undone")
    public ResponseEntity<DeliveryOrderResponse> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelDeliveryRequest request
    ) {
        return ResponseEntity.ok(deliveryService.cancel(id, request));
    }
}
