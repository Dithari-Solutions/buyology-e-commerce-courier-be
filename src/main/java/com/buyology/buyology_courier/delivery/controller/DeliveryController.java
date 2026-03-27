package com.buyology.buyology_courier.delivery.controller;

import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;
import com.buyology.buyology_courier.delivery.dto.request.CancelDeliveryRequest;
import com.buyology.buyology_courier.delivery.dto.request.UpdateDeliveryStatusRequest;
import com.buyology.buyology_courier.delivery.dto.response.DeliveryOrderResponse;
import com.buyology.buyology_courier.delivery.dto.response.DeliveryStatusHistoryResponse;
import com.buyology.buyology_courier.delivery.service.DeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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

    private final DeliveryService deliveryService;

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
    @Operation(summary = "Get all active deliveries assigned to the authenticated courier")
    public Page<DeliveryOrderResponse> getMyDeliveries(
            Authentication authentication,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        UUID courierId = UUID.fromString(authentication.getName());
        return deliveryService.findAssignedToCourier(courierId, pageable);
    }

    // ── Courier mutations ─────────────────────────────────────────────────────

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('COURIER')")
    @Operation(summary = "Update the delivery status (courier only — must be the assigned courier)")
    public DeliveryOrderResponse updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDeliveryStatusRequest request,
            Authentication authentication
    ) {
        UUID courierId = UUID.fromString(authentication.getName());
        return deliveryService.updateStatus(id, courierId, request);
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
