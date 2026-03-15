package com.buyology.buyology_courier.courier.job;

import com.buyology.buyology_courier.courier.repository.CourierLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Nightly job that purges courier_locations rows older than the configured
 * retention window (default 90 days).
 *
 * Without this job, 1000 couriers pinging every minute generates ~1.4M rows/day
 * (~130M rows/quarter). The job keeps the table bounded and query performance stable.
 *
 * For very high volumes, consider RANGE-partitioning courier_locations by
 * recorded_at in PostgreSQL and dropping old partitions instead of running DELETE.
 * That approach is zero-lock-contention and instant — suitable for 10M+ rows/day.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LocationCleanupJob {

    private final CourierLocationRepository locationRepository;

    @Value("${location.retention-days:90}")
    private int retentionDays;

    // Runs at 02:00 UTC every night — low-traffic window
    @Scheduled(cron = "${location.cleanup-cron:0 0 2 * * *}")
    @Transactional
    public void purgeOldLocations() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        log.info("Location cleanup started: purging rows older than {} (retention={} days)", cutoff, retentionDays);

        int deleted = locationRepository.deleteByRecordedAtBefore(cutoff);

        log.info("Location cleanup finished: deleted {} rows", deleted);
    }
}
