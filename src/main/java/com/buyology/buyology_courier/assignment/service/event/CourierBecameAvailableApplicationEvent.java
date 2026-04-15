package com.buyology.buyology_courier.assignment.service.event;

import java.util.UUID;

/**
 * Published (within the updateAvailability transaction) when a courier flips
 * their availability to {@code true}. The assignment service listens and
 * immediately scans all CREATED orders so they get picked up without waiting
 * for the next StaleOrderRetryJob tick.
 */
public record CourierBecameAvailableApplicationEvent(UUID courierId) {}
