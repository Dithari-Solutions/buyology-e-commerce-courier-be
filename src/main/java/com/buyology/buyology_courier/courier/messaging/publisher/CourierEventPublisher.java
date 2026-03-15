package com.buyology.buyology_courier.courier.messaging.publisher;

import com.buyology.buyology_courier.courier.messaging.config.RabbitMQConfig;
import com.buyology.buyology_courier.courier.messaging.event.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Translates internal Spring application events into RabbitMQ messages.
 *
 * Two key design choices:
 *
 * 1. @TransactionalEventListener(phase = AFTER_COMMIT)
 *    Listeners fire ONLY after the originating DB transaction commits successfully.
 *    This eliminates "phantom events" — messages for data that was never actually
 *    persisted because the transaction rolled back.
 *
 * 2. @Async("eventPublisherExecutor")
 *    Listeners run in a dedicated thread pool (see AsyncConfig), not in the HTTP
 *    request thread. The HTTP response is returned to the client immediately after
 *    commit; RabbitMQ publishing happens in the background without adding latency.
 *
 * Failure behaviour:
 *    If the broker is unreachable, the error is logged and the request still succeeds.
 *    The DB commit has already happened. For guaranteed at-least-once delivery, the
 *    next evolution is the Transactional Outbox pattern (write event to DB in same
 *    transaction, poll and publish separately).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CourierEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Async("eventPublisherExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(CourierRegisteredEvent event) {
        publish(RabbitMQConfig.COURIER_REGISTERED_KEY, event);
    }

    @Async("eventPublisherExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(CourierStatusChangedEvent event) {
        publish(RabbitMQConfig.COURIER_STATUS_CHANGED_KEY, event);
    }

    @Async("eventPublisherExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(CourierAvailabilityChangedEvent event) {
        publish(RabbitMQConfig.COURIER_AVAILABILITY_CHANGED_KEY, event);
    }

    @Async("eventPublisherExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(CourierLocationUpdatedEvent event) {
        publish(RabbitMQConfig.COURIER_LOCATION_UPDATED_KEY, event);
    }

    @Async("eventPublisherExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(CourierDeletedEvent event) {
        publish(RabbitMQConfig.COURIER_DELETED_KEY, event);
    }

    private void publish(String routingKey, Object event) {
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.COURIER_EXCHANGE, routingKey, event);
            log.debug("Published [{}]: {}", routingKey, event);
        } catch (Exception ex) {
            log.error("Failed to publish [{}] — event lost. Consider Transactional Outbox for guaranteed delivery. Error: {}",
                    routingKey, ex.getMessage(), ex);
        }
    }
}
