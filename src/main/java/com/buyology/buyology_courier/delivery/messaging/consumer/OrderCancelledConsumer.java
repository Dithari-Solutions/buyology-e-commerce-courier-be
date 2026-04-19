package com.buyology.buyology_courier.delivery.messaging.consumer;

import com.buyology.buyology_courier.delivery.messaging.config.DeliveryRabbitMQConfig;
import com.buyology.buyology_courier.delivery.messaging.event.OrderCancelledEvent;
import com.buyology.buyology_courier.delivery.service.DeliveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consumes {@code order.delivery.cancelled} events from the ecommerce backend and
 * cancels the corresponding in-flight delivery, notifying the assigned courier.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCancelledConsumer {

    private final DeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = DeliveryRabbitMQConfig.ORDER_DELIVERY_CANCELLED_QUEUE)
    public void onOrderCancelled(Message amqpMessage) {
        String payload = new String(amqpMessage.getBody(), StandardCharsets.UTF_8);
        log.info("[OrderCancelledConsumer] Received cancellation event: {}", payload);
        try {
            OrderCancelledEvent event = objectMapper.readValue(payload, OrderCancelledEvent.class);
            deliveryService.cancelByEcommerceOrderId(event.ecommerceOrderId(), event.reason());
        } catch (Exception e) {
            log.error("[OrderCancelledConsumer] Failed to process cancellation: {} — {}",
                    payload, e.getMessage(), e);
            throw new RuntimeException("Failed to process order cancellation event", e);
        }
    }
}
