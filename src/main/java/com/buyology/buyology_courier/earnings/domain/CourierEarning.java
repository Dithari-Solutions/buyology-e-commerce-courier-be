package com.buyology.buyology_courier.earnings.domain;

import com.buyology.buyology_courier.courier.domain.Courier;
import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;
import com.buyology.buyology_courier.earnings.domain.enums.EarningType;
import com.buyology.buyology_courier.earnings.domain.enums.PayoutStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "courier_earnings",
        indexes = {
                // Most common query: earnings history for a courier sorted by time
                @Index(name = "idx_courier_earnings_courier_created", columnList = "courier_id, created_at"),
                @Index(name = "idx_courier_earnings_payout_status", columnList = "payout_status"),
                // Payout processing: find all pending earnings per courier
                @Index(name = "idx_courier_earnings_courier_payout", columnList = "courier_id, payout_status"),
                @Index(name = "idx_courier_earnings_delivery_id", columnList = "delivery_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourierEarning {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "courier_id", nullable = false, foreignKey = @ForeignKey(name = "fk_courier_earnings_courier"))
    private Courier courier;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false, foreignKey = @ForeignKey(name = "fk_courier_earnings_order"))
    private DeliveryOrder delivery;

    @Enumerated(EnumType.STRING)
    @Column(name = "earning_type", length = 30, nullable = false)
    private EarningType earningType;

    @Column(name = "earning_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal earningAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_status", length = 30, nullable = false)
    private PayoutStatus payoutStatus;

    @Column(name = "payout_date")
    private Instant payoutDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
