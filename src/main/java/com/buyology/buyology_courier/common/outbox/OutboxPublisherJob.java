package com.buyology.buyology_courier.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Polls the outbox_events table and publishes PENDING events to RabbitMQ.
 *
 * Uses SKIP LOCKED so multiple pods each process a distinct, non-overlapping
 * batch — no duplicate publishes, no contention.
 *
 * Retry policy: up to MAX_RETRIES attempts, then status = FAILED. A FAILED
 * event is never retried automatically — requires operator intervention (inspect
 * the row, fix root cause, reset status to PENDING to replay).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisherJob {

    private static final int BATCH_SIZE  = 50;
    private static final int MAX_RETRIES = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:5000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findAndLockPendingBatch(BATCH_SIZE);
        if (pending.isEmpty()) return;

        log.debug("Outbox: processing {} pending event(s)", pending.size());

        for (OutboxEvent event : pending) {
            try {
                // Send raw JSON bytes so the payload is not double-serialised
                // by JacksonJsonMessageConverter — what was written to the DB
                // is exactly what lands on the wire.
                Message message = MessageBuilder
                        .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json")
                        .setHeader("eventVersion", event.getEventVersion())
                        .build();

                rabbitTemplate.send(event.getExchange(), event.getRoutingKey(), message);

                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
                log.debug("Outbox: published [{}] id={}", event.getRoutingKey(), event.getId());

            } catch (Exception ex) {
                int newRetryCount = event.getRetryCount() + 1;
                event.setRetryCount(newRetryCount);

                if (newRetryCount >= MAX_RETRIES) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error("ALERT: Outbox event {} [{}] failed permanently after {} retries — manual replay required. Error: {}",
                            event.getId(), event.getRoutingKey(), newRetryCount, ex.getMessage());
                } else {
                    log.warn("Outbox: publish failed for event {} [{}], retry {}/{}. Error: {}",
                            event.getId(), event.getRoutingKey(), newRetryCount, MAX_RETRIES, ex.getMessage());
                }
            }
        }

        outboxEventRepository.saveAll(pending);
    }
}
