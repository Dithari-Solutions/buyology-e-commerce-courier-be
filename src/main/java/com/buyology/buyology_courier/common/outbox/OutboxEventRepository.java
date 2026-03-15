package com.buyology.buyology_courier.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Fetch a bounded batch of PENDING events using SKIP LOCKED so that concurrent
     * scheduler instances (multiple pods) each grab a distinct non-overlapping set
     * of rows. Any row already locked by another transaction is skipped rather than
     * blocked, preventing pile-ups under high retry load.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findAndLockPendingBatch(@Param("limit") int limit);
}
