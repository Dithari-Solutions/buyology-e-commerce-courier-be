package com.buyology.buyology_courier.assignment.domain;

import com.buyology.buyology_courier.assignment.domain.enums.AssignmentStatus;
import com.buyology.buyology_courier.courier.domain.Courier;
import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "courier_assignments",
        indexes = {
                @Index(name = "idx_courier_assignments_delivery_id", columnList = "delivery_id"),
                @Index(name = "idx_courier_assignments_courier_id", columnList = "courier_id"),
                @Index(name = "idx_courier_assignments_status", columnList = "status"),
                // Composite: find all pending assignments for a courier
                @Index(name = "idx_courier_assignments_courier_status", columnList = "courier_id, status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourierAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false, foreignKey = @ForeignKey(name = "fk_courier_assignments_order"))
    private DeliveryOrder delivery;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "courier_id", nullable = false, foreignKey = @ForeignKey(name = "fk_courier_assignments_courier"))
    private Courier courier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private AssignmentStatus status;

    // Incremented on each reassignment attempt for the same delivery
    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    // Reason provided by courier or set by system on rejection/cancellation
    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
