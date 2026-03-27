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
    public static final String DELIVERY_STATUS_CHANGED_KEY = "delivery.status.changed";
    public static final String DELIVERY_COMPLETED_KEY      = "delivery.completed";
    public static final String DELIVERY_CANCELLED_KEY      = "delivery.cancelled";

    // ── Queue names ───────────────────────────────────────────────────────────
    public static final String DELIVERY_ORDER_RECEIVED_QUEUE = "delivery.order.received.queue";

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
        return QueueBuilder.durable(DELIVERY_ORDER_RECEIVED_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    Binding deliveryOrderReceivedBinding() {
        return BindingBuilder
                .bind(deliveryOrderReceivedQueue())
                .to(ecommerceExchange())
                .with(ORDER_DELIVERY_REQUESTED_KEY);
    }
}
