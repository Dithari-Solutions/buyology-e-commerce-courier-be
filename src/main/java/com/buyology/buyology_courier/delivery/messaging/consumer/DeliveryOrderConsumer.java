package com.buyology.buyology_courier.delivery.messaging.consumer;

import com.buyology.buyology_courier.delivery.messaging.config.DeliveryRabbitMQConfig;
import com.buyology.buyology_courier.delivery.messaging.event.DeliveryOrderReceivedEvent;
import com.buyology.buyology_courier.delivery.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes delivery-order events from the ecommerce backend.
 *
 * <p>The listener acks the message in all cases:
 * <ul>
 *   <li>Success — order is persisted and outbox event written.</li>
 *   <li>Duplicate — {@link DeliveryService#ingest} is idempotent; ack prevents re-delivery loop.</li>
 *   <li>Unexpected error — Spring AMQP nacks and routes to the DLQ for operator inspection.</li>
 * </ul>
 *
 * <p>JWT authentication: messages arrive over AMQP (not HTTP), so no JWT is
 * required here. The ecommerce exchange is only accessible to services that share
 * the RabbitMQ vhost credentials — broker-level security covers this channel.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryOrderConsumer {

    private final DeliveryService deliveryService;

    @RabbitListener(queues = DeliveryRabbitMQConfig.DELIVERY_ORDER_RECEIVED_QUEUE)
    public void onDeliveryOrderReceived(DeliveryOrderReceivedEvent event) {
        log.info("[DeliveryConsumer] Received message from queue — ecommerceOrderId={} storeId={} priority={} " +
                        "pickupLat={} pickupLng={} dropoffLat={} dropoffLng={} packageSize={} deliveryFee={}",
                event.ecommerceOrderId(), event.ecommerceStoreId(), event.priority(),
                event.pickupLat(), event.pickupLng(), event.dropoffLat(), event.dropoffLng(),
                event.packageSize(), event.deliveryFee());
        try {
            deliveryService.ingest(event);
            log.info("[DeliveryConsumer] Successfully ingested order ecommerceOrderId={}", event.ecommerceOrderId());
        } catch (Exception e) {
            log.error("[DeliveryConsumer] Failed to ingest order ecommerceOrderId={} — {}: {}",
                    event.ecommerceOrderId(), e.getClass().getSimpleName(), e.getMessage(), e);
            throw e; // re-throw so Spring AMQP nacks and routes to DLQ
        }
    }
}
