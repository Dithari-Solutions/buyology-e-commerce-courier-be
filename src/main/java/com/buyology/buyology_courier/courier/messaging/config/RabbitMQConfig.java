package com.buyology.buyology_courier.courier.messaging.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class RabbitMQConfig {

    // ── Exchange names ────────────────────────────────────────────────────────
    public static final String COURIER_EXCHANGE     = "buyology.courier.exchange";
    public static final String DEAD_LETTER_EXCHANGE = "buyology.dlx";

    // ── Routing keys ──────────────────────────────────────────────────────────
    public static final String COURIER_REGISTERED_KEY         = "courier.registered";
    public static final String COURIER_STATUS_CHANGED_KEY     = "courier.status.changed";
    public static final String COURIER_AVAILABILITY_CHANGED_KEY = "courier.availability.changed";
    public static final String COURIER_LOCATION_UPDATED_KEY   = "courier.location.updated";
    public static final String COURIER_DELETED_KEY            = "courier.deleted";

    // ── Queue names ───────────────────────────────────────────────────────────
    public static final String COURIER_REGISTERED_QUEUE           = "courier.registered.queue";
    public static final String COURIER_STATUS_CHANGED_QUEUE       = "courier.status.changed.queue";
    public static final String COURIER_AVAILABILITY_CHANGED_QUEUE = "courier.availability.changed.queue";
    public static final String COURIER_LOCATION_UPDATED_QUEUE     = "courier.location.updated.queue";
    public static final String COURIER_DELETED_QUEUE              = "courier.deleted.queue";
    public static final String DEAD_LETTER_QUEUE                  = "buyology.dlq";

    // ── Exchanges ─────────────────────────────────────────────────────────────

    @Bean
    TopicExchange courierExchange() {
        return ExchangeBuilder.topicExchange(COURIER_EXCHANGE).durable(true).build();
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    // ── Dead letter queue ─────────────────────────────────────────────────────

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(DEAD_LETTER_QUEUE);
    }

    // ── Courier queues — each routes failed messages to the DLX ──────────────

    @Bean
    Queue courierRegisteredQueue() {
        return withDlx(COURIER_REGISTERED_QUEUE);
    }

    @Bean
    Queue courierStatusChangedQueue() {
        return withDlx(COURIER_STATUS_CHANGED_QUEUE);
    }

    @Bean
    Queue courierAvailabilityChangedQueue() {
        return withDlx(COURIER_AVAILABILITY_CHANGED_QUEUE);
    }

    @Bean
    Queue courierLocationUpdatedQueue() {
        return withDlx(COURIER_LOCATION_UPDATED_QUEUE);
    }

    @Bean
    Queue courierDeletedQueue() {
        return withDlx(COURIER_DELETED_QUEUE);
    }

    // ── Bindings ──────────────────────────────────────────────────────────────

    @Bean
    Binding courierRegisteredBinding() {
        return BindingBuilder.bind(courierRegisteredQueue()).to(courierExchange()).with(COURIER_REGISTERED_KEY);
    }

    @Bean
    Binding courierStatusChangedBinding() {
        return BindingBuilder.bind(courierStatusChangedQueue()).to(courierExchange()).with(COURIER_STATUS_CHANGED_KEY);
    }

    @Bean
    Binding courierAvailabilityChangedBinding() {
        return BindingBuilder.bind(courierAvailabilityChangedQueue()).to(courierExchange()).with(COURIER_AVAILABILITY_CHANGED_KEY);
    }

    @Bean
    Binding courierLocationUpdatedBinding() {
        return BindingBuilder.bind(courierLocationUpdatedQueue()).to(courierExchange()).with(COURIER_LOCATION_UPDATED_KEY);
    }

    @Bean
    Binding courierDeletedBinding() {
        return BindingBuilder.bind(courierDeletedQueue()).to(courierExchange()).with(COURIER_DELETED_KEY);
    }

    // ── JSON serialization for all messages ───────────────────────────────────

    @Bean
    MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());

        // Mandatory=true causes unroutable messages to be returned to the sender.
        // Without a ReturnsCallback they would be logged at WARN and silently dropped.
        template.setMandatory(true);
        template.setReturnsCallback(returned ->
                log.error("Message returned unroutable — no queue bound for this routing key. " +
                                "exchange={}, routingKey={}, replyCode={}, replyText={}",
                        returned.getExchange(), returned.getRoutingKey(),
                        returned.getReplyCode(), returned.getReplyText())
        );

        // publisher-confirm-type=correlated is set in application.properties.
        // Without a ConfirmCallback, broker-side nacks (message rejected by broker)
        // are silently ignored — we would never know a message was not accepted.
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("Message nacked by broker — delivery not confirmed. correlationData={}, cause={}",
                        correlationData, cause);
            }
        });

        return template;
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Queue withDlx(String queueName) {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_LETTER_QUEUE)
                .build();
    }
}
