package com.buyology.buyology_courier.delivery.domain;

import com.buyology.buyology_courier.courier.domain.Courier;
import com.buyology.buyology_courier.delivery.domain.enums.DeliveryPriority;
import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;
import com.buyology.buyology_courier.delivery.domain.enums.PackageSize;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "delivery_orders",
        indexes = {
                @Index(name = "idx_delivery_orders_status", columnList = "status"),
                @Index(name = "idx_delivery_orders_ecommerce_order_id", columnList = "ecommerce_order_id"),
                @Index(name = "idx_delivery_orders_ecommerce_store_id", columnList = "ecommerce_store_id"),
                // Denormalized FK — avoids joining courier_assignments on every order lookup
                @Index(name = "idx_delivery_orders_assigned_courier", columnList = "assigned_courier_id"),
                @Index(name = "idx_delivery_orders_created_at", columnList = "created_at"),
                @Index(name = "idx_delivery_orders_status_created", columnList = "status, created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // Optimistic locking — prevents lost updates from concurrent courier app + ops dashboard writes
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // External references — no FK constraint; e-commerce service owns these
    @Column(name = "ecommerce_order_id", nullable = false)
    private UUID ecommerceOrderId;

    @Column(name = "ecommerce_store_id", nullable = false)
    private UUID ecommerceStoreId;

    // Customer info snapshotted at order creation — not a FK (customer may change)
    @Column(name = "customer_name", length = 255, nullable = false)
    private String customerName;

    @Column(name = "customer_phone", length = 50, nullable = false)
    private String customerPhone;

    @Column(name = "pickup_address", nullable = false, columnDefinition = "TEXT")
    private String pickupAddress;

    @Column(name = "pickup_lat", nullable = false, precision = 10, scale = 7)
    private BigDecimal pickupLat;

    @Column(name = "pickup_lng", nullable = false, precision = 10, scale = 7)
    private BigDecimal pickupLng;

    @Column(name = "dropoff_address", nullable = false, columnDefinition = "TEXT")
    private String dropoffAddress;

    @Column(name = "dropoff_lat", nullable = false, precision = 10, scale = 7)
    private BigDecimal dropoffLat;

    @Column(name = "dropoff_lng", nullable = false, precision = 10, scale = 7)
    private BigDecimal dropoffLng;

    @Enumerated(EnumType.STRING)
    @Column(name = "package_size", length = 30)
    private PackageSize packageSize;

    @Column(name = "package_weight", precision = 10, scale = 2)
    private BigDecimal packageWeight;

    @Column(name = "delivery_fee", precision = 10, scale = 2)
    private BigDecimal deliveryFee;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 30, nullable = false)
    private DeliveryPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50, nullable = false)
    private DeliveryStatus status;

    // Denormalized: mirrors active courier_assignment — avoids join on every order read
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_courier_id", foreignKey = @ForeignKey(name = "fk_delivery_orders_courier"))
    private Courier assignedCourier;

    @Column(name = "estimated_delivery_time")
    private Instant estimatedDeliveryTime;

    @Column(name = "actual_delivery_time")
    private Instant actualDeliveryTime;

    @Column(name = "cancelled_reason", columnDefinition = "TEXT")
    private String cancelledReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
