package com.buyology.buyology_courier.delivery.messaging.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.buyology.buyology_courier.courier.messaging.config.RabbitMQConfig.DEAD_LETTER_EXCHANGE;
import static com.buyology.buyology_courier.courier.messaging.config.RabbitMQConfig.DEAD_LETTER_QUEUE;

/**
 * RabbitMQ topology for the delivery module.
 *
 * Inbound  — ecommerce backend publishes to {@code buyology.ecommerce.exchange}.
 *            This service consumes from {@code delivery.order.received.queue}.
 *
 * Outbound — this service publishes delivery status events to
 *            {@code buyology.delivery.exchange} via the transactional outbox.
 */
@Configuration
public class DeliveryRabbitMQConfig {

    // ── Exchange names ────────────────────────────────────────────────────────
    /** Topic exchange the ecommerce backend publishes order events to. */
    public static final String ECOMMERCE_EXCHANGE = "buyology.ecommerce.exchange";

    /** Topic exchange this service publishes delivery status events to. */
    public static final String DELIVERY_EXCHANGE  = "buyology.delivery.exchange";

    // ── Inbound routing keys (ecommerce → courier) ───────────────────────────
    public static final String ORDER_DELIVERY_REQUESTED_KEY = "order.delivery.requested";

    // ── Outbound routing keys (courier → ecommerce / other consumers) ────────
    public static final String DELIVERY_STATUS_CHANGED_KEY  = "delivery.status.changed";
    public static final String DELIVERY_COMPLETED_KEY       = "delivery.completed";
    public static final String DELIVERY_CANCELLED_KEY       = "delivery.cancelled";

    // ── Assignment routing keys ───────────────────────────────────────────────
    public static final String COURIER_ASSIGNED_KEY         = "delivery.courier.assigned";
    public static final String ASSIGNMENT_ACCEPTED_KEY      = "delivery.courier.assignment.accepted";
    public static final String ASSIGNMENT_REJECTED_KEY      = "delivery.courier.assignment.rejected";
    public static final String ASSIGNMENT_EXHAUSTED_KEY     = "delivery.assignment.exhausted";

    /**
     * Published on every GPS ping while a delivery is in-progress.
     * The ecommerce backend subscribes to this key and forwards coordinates
     * to the customer for real-time courier tracking.
     */
    public static final String LOCATION_UPDATED_KEY         = "delivery.location.updated";

    // ── Queue names ───────────────────────────────────────────────────────────
    public static final String DELIVERY_ORDER_RECEIVED_QUEUE   = "delivery.order.received.queue";

    // Outbound queues — consumed by ecommerce backend (or any subscriber).
    // Declared here so messages are never returned as unroutable even when
    // the ecommerce consumer is not running (e.g. local dev).
    public static final String DELIVERY_STATUS_CHANGED_QUEUE   = "delivery.status.changed.queue";
    public static final String DELIVERY_COMPLETED_QUEUE        = "delivery.completed.queue";
    public static final String DELIVERY_CANCELLED_QUEUE        = "delivery.cancelled.queue";
    public static final String COURIER_ASSIGNED_QUEUE          = "delivery.courier.assigned.queue";
    public static final String ASSIGNMENT_ACCEPTED_QUEUE       = "delivery.courier.assignment.accepted.queue";
    public static final String ASSIGNMENT_REJECTED_QUEUE       = "delivery.courier.assignment.rejected.queue";
    public static final String ASSIGNMENT_EXHAUSTED_QUEUE      = "delivery.assignment.exhausted.queue";
    public static final String LOCATION_UPDATED_QUEUE          = "delivery.location.updated.queue";

    // ── Exchanges ─────────────────────────────────────────────────────────────

    @Bean
    TopicExchange ecommerceExchange() {
        return ExchangeBuilder.topicExchange(ECOMMERCE_EXCHANGE).durable(true).build();
    }

    @Bean
    TopicExchange deliveryExchange() {
        return ExchangeBuilder.topicExchange(DELIVERY_EXCHANGE).durable(true).build();
    }

    // ── Inbound queue ─────────────────────────────────────────────────────────

    @Bean
    Queue deliveryOrderReceivedQueue() {
        return withDlx(DELIVERY_ORDER_RECEIVED_QUEUE);
    }

    @Bean
    Binding deliveryOrderReceivedBinding() {
        return BindingBuilder
                .bind(deliveryOrderReceivedQueue())
                .to(ecommerceExchange())
                .with(ORDER_DELIVERY_REQUESTED_KEY);
    }

    // ── Outbound queues & bindings ────────────────────────────────────────────

    @Bean Queue deliveryStatusChangedQueue()       { return withDlx(DELIVERY_STATUS_CHANGED_QUEUE); }
    @Bean Queue deliveryCompletedQueue()           { return withDlx(DELIVERY_COMPLETED_QUEUE); }
    @Bean Queue deliveryCancelledQueue()           { return withDlx(DELIVERY_CANCELLED_QUEUE); }
    @Bean Queue courierAssignedQueue()             { return withDlx(COURIER_ASSIGNED_QUEUE); }
    @Bean Queue assignmentAcceptedQueue()          { return withDlx(ASSIGNMENT_ACCEPTED_QUEUE); }
    @Bean Queue assignmentRejectedQueue()          { return withDlx(ASSIGNMENT_REJECTED_QUEUE); }
    @Bean Queue assignmentExhaustedQueue()         { return withDlx(ASSIGNMENT_EXHAUSTED_QUEUE); }
    @Bean Queue locationUpdatedQueue()             { return withDlx(LOCATION_UPDATED_QUEUE); }

    @Bean Binding deliveryStatusChangedBinding() {
        return BindingBuilder.bind(deliveryStatusChangedQueue()).to(deliveryExchange()).with(DELIVERY_STATUS_CHANGED_KEY);
    }
    @Bean Binding deliveryCompletedBinding() {
        return BindingBuilder.bind(deliveryCompletedQueue()).to(deliveryExchange()).with(DELIVERY_COMPLETED_KEY);
    }
    @Bean Binding deliveryCancelledBinding() {
        return BindingBuilder.bind(deliveryCancelledQueue()).to(deliveryExchange()).with(DELIVERY_CANCELLED_KEY);
    }
    @Bean Binding courierAssignedBinding() {
        return BindingBuilder.bind(courierAssignedQueue()).to(deliveryExchange()).with(COURIER_ASSIGNED_KEY);
    }
    @Bean Binding assignmentAcceptedBinding() {
        return BindingBuilder.bind(assignmentAcceptedQueue()).to(deliveryExchange()).with(ASSIGNMENT_ACCEPTED_KEY);
    }
    @Bean Binding assignmentRejectedBinding() {
        return BindingBuilder.bind(assignmentRejectedQueue()).to(deliveryExchange()).with(ASSIGNMENT_REJECTED_KEY);
    }
    @Bean Binding assignmentExhaustedBinding() {
        return BindingBuilder.bind(assignmentExhaustedQueue()).to(deliveryExchange()).with(ASSIGNMENT_EXHAUSTED_KEY);
    }
    @Bean Binding locationUpdatedBinding() {
        return BindingBuilder.bind(locationUpdatedQueue()).to(deliveryExchange()).with(LOCATION_UPDATED_KEY);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Queue withDlx(String queueName) {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_LETTER_QUEUE)
                .build();
    }
}
