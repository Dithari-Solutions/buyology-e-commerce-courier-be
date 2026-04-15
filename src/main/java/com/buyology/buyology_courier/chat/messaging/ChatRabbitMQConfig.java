package com.buyology.buyology_courier.chat.messaging;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the courier-side queue that receives chat messages relayed from the
 * ecommerce backend (routing key {@code chat.message.customer}).
 *
 * <p>The exchange ({@code buyology.delivery.exchange}) and DLX are already
 * declared by the delivery RabbitMQ config; re-declaring them here is idempotent.
 */
@Configuration
public class ChatRabbitMQConfig {

    public static final String DELIVERY_EXCHANGE       = "buyology.delivery.exchange";
    public static final String DELIVERY_DLX            = "buyology.delivery.dlx";
    public static final String DELIVERY_DLQ            = "buyology.delivery.ecommerce.dlq";

    /** Routing key the ecommerce backend publishes customer-originated messages to. */
    public static final String CHAT_FROM_CUSTOMER_KEY  = "chat.message.customer";

    /** Queue on which the courier backend consumes messages sent by customers. */
    public static final String CHAT_FROM_CUSTOMER_QUEUE = "chat.from.customer.queue";

    /** Routing key courier backend publishes to when courier sends a message. */
    public static final String CHAT_FROM_COURIER_KEY   = "chat.message.courier";

    @Bean
    Queue chatFromCustomerQueue() {
        return QueueBuilder.durable(CHAT_FROM_CUSTOMER_QUEUE)
                .withArgument("x-dead-letter-exchange", DELIVERY_DLX)
                .withArgument("x-dead-letter-routing-key", DELIVERY_DLQ)
                .build();
    }

    @Bean
    Binding chatFromCustomerBinding(Queue chatFromCustomerQueue) {
        TopicExchange exchange = new TopicExchange(DELIVERY_EXCHANGE, true, false);
        return BindingBuilder
                .bind(chatFromCustomerQueue)
                .to(exchange)
                .with(CHAT_FROM_CUSTOMER_KEY);
    }
}
