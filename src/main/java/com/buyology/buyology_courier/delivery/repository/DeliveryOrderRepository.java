package com.buyology.buyology_courier.delivery.repository;

import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;
import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryOrderRepository extends JpaRepository<DeliveryOrder, UUID> {

    boolean existsByEcommerceOrderId(UUID ecommerceOrderId);

    Optional<DeliveryOrder> findByEcommerceOrderId(UUID ecommerceOrderId);

    Page<DeliveryOrder> findByStatus(DeliveryStatus status, Pageable pageable);

    /** All active (non-terminal) deliveries assigned to a specific courier. */
    Page<DeliveryOrder> findByAssignedCourierIdAndStatusNotIn(
            UUID courierId, Iterable<DeliveryStatus> excludedStatuses, Pageable pageable);
}
