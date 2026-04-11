package com.buyology.buyology_courier.assignment.service;

import com.buyology.buyology_courier.assignment.dto.request.AssignmentRespondRequest;
import com.buyology.buyology_courier.assignment.dto.response.AssignmentResponse;
import com.buyology.buyology_courier.assignment.service.event.DeliveryCreatedApplicationEvent;
import com.buyology.buyology_courier.assignment.service.event.ReassignApplicationEvent;
import com.buyology.buyology_courier.delivery.domain.DeliveryOrder;

import java.util.Set;
import java.util.UUID;

public interface CourierAssignmentService {

    /**
     * Triggered after a delivery order transaction commits (AFTER_COMMIT listener).
     * Finds the nearest available courier and creates a PENDING assignment.
     */
    void onDeliveryCreated(DeliveryCreatedApplicationEvent event);

    /**
     * Triggered after a rejection transaction commits to attempt reassignment.
     * Excludes couriers who already rejected for this delivery.
     */
    void onReassignRequested(ReassignApplicationEvent event);

    /**
     * Courier accepts or rejects their PENDING assignment.
     *
     * @param assignmentId the assignment UUID
     * @param courierId    the authenticated courier's UUID (from JWT)
     * @param request      ACCEPT or REJECT with optional reason
     */
    AssignmentResponse respondToAssignment(UUID assignmentId, UUID courierId,
                                           AssignmentRespondRequest request);

    /**
     * Returns the assignment details for the authenticated courier.
     */
    AssignmentResponse getAssignment(UUID assignmentId, UUID courierId);

    /**
     * Searches for the nearest eligible courier and creates a PENDING assignment.
     * Called directly by {@code StaleOrderRetryJob} to re-attempt assignment for
     * orders that found no courier on first try.
     */
    void attemptAssignment(DeliveryOrder order, int attemptNumber, Set<UUID> excludedCourierIds);
}
