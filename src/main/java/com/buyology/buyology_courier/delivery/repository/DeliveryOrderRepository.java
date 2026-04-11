package com.buyology.buyology_courier.delivery.repository;

import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;
import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryOrderRepository extends JpaRepository<DeliveryOrder, UUID> {

    boolean existsByEcommerceOrderId(UUID ecommerceOrderId);

    Optional<DeliveryOrder> findByEcommerceOrderId(UUID ecommerceOrderId);

    Page<DeliveryOrder> findByStatus(DeliveryStatus status, Pageable pageable);

    /** All active (non-terminal) deliveries assigned to a specific courier. */
    Page<DeliveryOrder> findByAssignedCourierIdAndStatusNotIn(
            UUID courierId, Iterable<DeliveryStatus> excludedStatuses, Pageable pageable);

    /** Full delivery history (all statuses) for a courier — used by the mobile history screen. */
    Page<DeliveryOrder> findByAssignedCourierId(UUID courierId, Pageable pageable);

    /** Full delivery history filtered by a specific status — used by the mobile history screen. */
    Page<DeliveryOrder> findByAssignedCourierIdAndStatus(
            UUID courierId, DeliveryStatus status, Pageable pageable);

    /**
     * Returns the first in-progress delivery for a courier.
     * Used when recording a location ping to decide whether to broadcast
     * the position to the ecommerce backend for customer tracking.
     */
    Optional<DeliveryOrder> findFirstByAssignedCourierIdAndStatusIn(
            UUID courierId, Collection<DeliveryStatus> statuses);

    /**
     * Finds orders stuck in CREATED status — i.e. initial assignment found no
     * courier. Used by the retry job to re-attempt assignment once couriers
     * come online.
     */
    List<DeliveryOrder> findByStatusAndCreatedAtBefore(DeliveryStatus status, Instant cutoff);
}
