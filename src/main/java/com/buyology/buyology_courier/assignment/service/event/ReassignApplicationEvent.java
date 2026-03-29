package com.buyology.buyology_courier.assignment.service.event;

import java.util.Set;
import java.util.UUID;

/**
 * Spring application event published after a courier rejects an assignment
 * (when attempt count is still below the max-retry threshold).
 *
 * <p>Consumed by {@code CourierAssignmentServiceImpl} via
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} to trigger a
 * reassignment attempt while excluding couriers who already rejected.
 */
public record ReassignApplicationEvent(
        UUID deliveryId,
        int nextAttemptNumber,
        Set<UUID> excludedCourierIds
) {}
