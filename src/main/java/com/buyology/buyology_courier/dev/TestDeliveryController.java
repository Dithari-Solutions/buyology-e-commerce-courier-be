package com.buyology.buyology_courier.dev;

import com.buyology.buyology_courier.assignment.service.CourierGeoService;
import com.buyology.buyology_courier.courier.domain.Courier;
import com.buyology.buyology_courier.courier.domain.enums.CourierStatus;
import com.buyology.buyology_courier.courier.repository.CourierRepository;
import com.buyology.buyology_courier.delivery.domain.enums.DeliveryPriority;
import com.buyology.buyology_courier.delivery.domain.enums.PackageSize;
import com.buyology.buyology_courier.delivery.dto.response.DeliveryOrderResponse;
import com.buyology.buyology_courier.delivery.messaging.event.DeliveryOrderReceivedEvent;
import com.buyology.buyology_courier.delivery.service.DeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DEV-ONLY — bypasses the ecommerce backend and RabbitMQ.
 *
 * <p>Injects a delivery order directly into the courier service so you can
 * test the full assignment → WebSocket push → accept/reject → status flow
 * without triggering a real payment in the ecommerce backend.
 *
 * <p><strong>This controller is NEVER loaded in the {@code prod} profile.</strong>
 * It is annotated {@code @Profile("dev")} and will not be registered as a bean
 * when {@code SPRING_PROFILES_ACTIVE=prod}.
 *
 * <h3>Test flow (in order)</h3>
 * <ol>
 *   <li>Ensure a courier exists with {@code status=ACTIVE} and {@code isAvailable=true}.</li>
 *   <li>Seed the courier's location into the Redis GEO index:
 *       {@code POST /api/dev/seed-courier-location}</li>
 *   <li>Inject a test delivery order: {@code POST /api/dev/test-orders}</li>
 *   <li>The assignment service finds the courier in the GEO index and creates a PENDING assignment.</li>
 * </ol>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Dev — Test orders", description = "DEV ONLY. Inject delivery orders without ecommerce/payment.")
public class TestDeliveryController {

    private final DeliveryService   deliveryService;
    private final CourierGeoService courierGeoService;
    private final CourierRepository courierRepository;

    // ── Create test delivery order ────────────────────────────────────────────

    /**
     * Inject a synthetic delivery order.
     *
     * <p>All optional fields default to sensible values when omitted.
     * {@code ecommerceOrderId} is auto-generated (UUID v4) if not supplied —
     * each call creates a distinct order. Pass the same UUID twice to verify
     * idempotency (second call returns the existing order unchanged).
     *
     * <p><strong>Prerequisite:</strong> the courier must be in the Redis GEO index
     * (call {@code POST /api/dev/seed-courier-location} first).
     */
    @PostMapping("/api/dev/test-orders")
    @Operation(summary = "Inject a test delivery order (dev only)")
    public ResponseEntity<DeliveryOrderResponse> createTestOrder(
            @Valid @RequestBody TestOrderRequest request
    ) {
        UUID ecommerceOrderId = request.ecommerceOrderId() != null
                ? request.ecommerceOrderId()
                : UUID.randomUUID();

        UUID ecommerceStoreId = request.ecommerceStoreId() != null
                ? request.ecommerceStoreId()
                : UUID.fromString("00000000-0000-0000-0000-000000000001");

        log.info("[TestOrder] Injecting test order ecommerceOrderId={} priority={}",
                ecommerceOrderId, request.priority());

        DeliveryOrderReceivedEvent event = new DeliveryOrderReceivedEvent(
                ecommerceOrderId,
                ecommerceStoreId,
                request.customerName()  != null ? request.customerName()  : "Test Customer",
                request.customerPhone() != null ? request.customerPhone() : "+998000000000",
                request.customerEmail(),
                request.pickupAddress(),
                request.pickupLat(),
                request.pickupLng(),
                request.dropoffAddress(),
                request.dropoffLat(),
                request.dropoffLng(),
                request.packageSize()   != null ? request.packageSize()   : PackageSize.SMALL,
                request.packageWeight() != null ? request.packageWeight() : BigDecimal.ONE,
                request.deliveryFee()   != null ? request.deliveryFee()   : new BigDecimal("15000"),
                request.priority()      != null ? request.priority()      : DeliveryPriority.STANDARD,
                Instant.now()
        );

        DeliveryOrderResponse response = deliveryService.ingest(event);
        return ResponseEntity.ok(response);
    }

    // ── Seed courier location ─────────────────────────────────────────────────

    /**
     * Seeds a courier's position directly into the Redis GEO index.
     *
     * <p>This is the missing prerequisite for the assignment flow during local
     * testing. In production the courier's location enters the GEO index via
     * {@code POST /api/v1/couriers/{id}/locations} (which enforces rate limits,
     * active-status checks, and persists to DB). This endpoint bypasses all of
     * that so you can test assignment without running a courier app.
     *
     * <p>Also sets the courier's {@code status=ACTIVE} and {@code isAvailable=true}
     * in the DB if they are not already, so the DB-level filter in the assignment
     * service passes.
     *
     * <h3>Example</h3>
     * <pre>{@code
     * POST /api/dev/seed-courier-location
     * {
     *   "courierId": "3175d349-776a-45a8-9bac-b3468e76732a",
     *   "lat": 41.2995,
     *   "lng": 69.2401
     * }
     * }</pre>
     */
    @PostMapping("/api/dev/seed-courier-location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    @Operation(summary = "Seed a courier's position into the Redis GEO index (dev only). " +
                         "Also activates the courier in the DB if needed.")
    public void seedCourierLocation(@Valid @RequestBody SeedLocationRequest request) {
        Courier courier = courierRepository.findByIdAndDeletedAtIsNull(request.courierId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Courier not found: " + request.courierId()));

        // Ensure the courier passes the DB-level filter inside attemptAssignment
        boolean dirty = false;
        if (courier.getStatus() != CourierStatus.ACTIVE) {
            courier.setStatus(CourierStatus.ACTIVE);
            dirty = true;
        }
        if (!courier.isAvailable()) {
            courier.setAvailable(true);
            dirty = true;
        }
        if (dirty) {
            courierRepository.save(courier);
            log.info("[SeedLocation] Set courierId={} to ACTIVE+available", request.courierId());
        }

        // Add / update position in Redis GEO index
        courierGeoService.addOrUpdate(request.courierId(),
                request.lat().doubleValue(), request.lng().doubleValue());

        log.info("[SeedLocation] Seeded courierId={} at lat={} lng={} into GEO index",
                request.courierId(), request.lat(), request.lng());
    }

    // ── Request DTOs ──────────────────────────────────────────────────────────

    /**
     * Only {@code pickupLat/Lng}, {@code dropoffLat/Lng}, and the two address
     * strings are required. Everything else has a default.
     */
    public record TestOrderRequest(

            // Optional — auto-generated if null; supply the same UUID to test idempotency
            UUID ecommerceOrderId,
            UUID ecommerceStoreId,

            String customerName,
            String customerPhone,
            String customerEmail,

            @NotNull String     pickupAddress,
            @NotNull BigDecimal pickupLat,
            @NotNull BigDecimal pickupLng,

            @NotNull String     dropoffAddress,
            @NotNull BigDecimal dropoffLat,
            @NotNull BigDecimal dropoffLng,

            PackageSize      packageSize,
            BigDecimal       packageWeight,
            BigDecimal       deliveryFee,
            DeliveryPriority priority
    ) {}

    public record SeedLocationRequest(
            @NotNull UUID       courierId,
            @NotNull BigDecimal lat,
            @NotNull BigDecimal lng
    ) {}
}
