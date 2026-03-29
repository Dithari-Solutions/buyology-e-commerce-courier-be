package com.buyology.buyology_courier.assignment.service.event;

import java.util.UUID;

/**
 * Spring application event (in-process, not RabbitMQ) published by
 * {@code DeliveryServiceImpl.ingest()} after the delivery order transaction commits.
 *
 * <p>Consumed by {@code CourierAssignmentServiceImpl} via
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} to trigger the
 * nearest-courier assignment flow without blocking the ingest transaction.
 */
public record DeliveryCreatedApplicationEvent(UUID deliveryId) {}
