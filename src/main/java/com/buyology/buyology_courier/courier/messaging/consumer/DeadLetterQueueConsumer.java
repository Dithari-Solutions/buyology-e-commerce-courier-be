package com.buyology.buyology_courier.courier.messaging.consumer;

import com.buyology.buyology_courier.courier.messaging.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consumes messages that land in the dead-letter queue after exhausting retries
 * on the main queues (nack + no requeue, or TTL expiry).
 *
 * Without a consumer, failed messages accumulate silently and courier state
 * can become inconsistent downstream with no alert. This listener ensures every
 * dead-lettered message is logged at ERROR level, making it visible in your
 * observability stack.
 *
 * Operator runbook:
 *   1. Investigate the root cause from the logged payload.
 *   2. Fix the downstream consumer or data issue.
 *   3. If the event must be replayed, insert a new row into outbox_events with
 *      the corrected payload and status = 'PENDING'.
 */
@Component
@Slf4j
public class DeadLetterQueueConsumer {

    @RabbitListener(queues = RabbitMQConfig.DEAD_LETTER_QUEUE)
    public void handle(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        String body       = new String(message.getBody(), StandardCharsets.UTF_8);

        log.error(
                "ALERT: Dead-letter message received — manual inspection required. " +
                "routingKey={}, exchange={}, redelivered={}, headers={}, body={}",
                routingKey,
                message.getMessageProperties().getReceivedExchange(),
                message.getMessageProperties().isRedelivered(),
                message.getMessageProperties().getHeaders(),
                body
        );
        // Acknowledge so the message does not re-loop back into the DLQ.
        // If you need durability, persist the raw body to a dead_letter_log table here.
    }
}
