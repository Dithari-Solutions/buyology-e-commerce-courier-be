package com.buyology.buyology_courier.dev;

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
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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
 * <h3>Quick test (curl)</h3>
 * <pre>{@code
 * curl -X POST http://localhost:8081/api/dev/test-orders \
 *   -H "Authorization: Bearer <admin-or-courier-jwt>" \
 *   -H "Content-Type: application/json" \
 *   -d '{
 *     "pickupAddress":  "1 Amir Temur Ave, Tashkent",
 *     "pickupLat":      41.2995,
 *     "pickupLng":      69.2401,
 *     "dropoffAddress": "45 Navoi St, Tashkent",
 *     "dropoffLat":     41.3111,
 *     "dropoffLng":     69.2550,
 *     "priority":       "EXPRESS"
 *   }'
 * }</pre>
 */
@RestController
@RequestMapping("/api/dev/test-orders")
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Dev — Test orders", description = "DEV ONLY. Inject delivery orders without ecommerce/payment.")
public class TestDeliveryController {

    private final DeliveryService deliveryService;

    /**
     * Inject a synthetic delivery order.
     *
     * <p>All optional fields default to sensible values when omitted.
     * {@code ecommerceOrderId} is auto-generated (UUID v4) if not supplied —
     * each call creates a distinct order. Pass the same UUID twice to verify
     * idempotency (second call returns the existing order unchanged).
     */
    @PostMapping
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

    // ── Request DTO ───────────────────────────────────────────────────────────

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

            PackageSize  packageSize,
            BigDecimal   packageWeight,
            BigDecimal   deliveryFee,
            DeliveryPriority priority
    ) {}
}
