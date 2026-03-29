package com.buyology.buyology_courier.assignment.repository;

import com.buyology.buyology_courier.assignment.domain.CourierAssignment;
import com.buyology.buyology_courier.assignment.domain.enums.AssignmentStatus;
import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CourierAssignmentRepository extends JpaRepository<CourierAssignment, UUID> {

    /** Find the current assignment for a delivery in a given status (e.g. PENDING during accept/reject). */
    Optional<CourierAssignment> findByDeliveryAndStatus(DeliveryOrder delivery, AssignmentStatus status);

    /** Full assignment history for a delivery, newest attempt first. */
    List<CourierAssignment> findByDeliveryOrderByAttemptNumberDesc(DeliveryOrder delivery);

    /** Total number of assignment attempts made for a delivery (used for max-retry check). */
    int countByDelivery(DeliveryOrder delivery);
}
