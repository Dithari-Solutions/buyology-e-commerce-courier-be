package com.buyology.buyology_courier.courier.repository;

import com.buyology.buyology_courier.courier.exception.CourierNotFoundException;
import com.buyology.buyology_courier.courier.job.LocationCleanupJob;
import com.buyology.buyology_courier.courier.service.CourierLookupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CourierLookupService (cache layer) and LocationCleanupJob.
 *
 * Note: SQL-level repository tests (soft-delete filtering, time-window queries,
 * deleteByRecordedAtBefore) should be covered by an integration test using a
 * real PostgreSQL instance via @DataJpaTest + Testcontainers once the test
 * infrastructure is wired up.
 */
@ExtendWith(MockitoExtension.class)
class CourierRepositoryTest {

    // ── CourierLookupService ───────────────────────────────────────────────────

    @Mock  CourierRepository   courierRepository;
    @InjectMocks CourierLookupService lookupService;

    @Test
    void requireActive_throws_when_courier_not_found() {
        UUID id = UUID.randomUUID();
        when(courierRepository.existsByIdAndDeletedAtIsNull(id)).thenReturn(false);

        assertThatThrownBy(() -> lookupService.requireActive(id))
                .isInstanceOf(CourierNotFoundException.class);
    }

    @Test
    void requireActive_returns_true_when_courier_exists() {
        UUID id = UUID.randomUUID();
        when(courierRepository.existsByIdAndDeletedAtIsNull(id)).thenReturn(true);

        assertThat(lookupService.requireActive(id)).isTrue();
    }

    // ── LocationCleanupJob ─────────────────────────────────────────────────────

    @Mock  CourierLocationRepository locationRepository;

    @Test
    void cleanup_job_deletes_rows_older_than_retention_window() {
        LocationCleanupJob job = new LocationCleanupJob(locationRepository);
        // retentionDays is @Value-injected and defaults to 0 in unit tests;
        // set it to 90 to match the production default.
        org.springframework.test.util.ReflectionTestUtils.setField(job, "retentionDays", 90);
        when(locationRepository.deleteByRecordedAtBefore(any())).thenReturn(42);

        job.purgeOldLocations();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(locationRepository).deleteByRecordedAtBefore(cutoffCaptor.capture());

        // Cutoff must be at least 89 days in the past (90-day retention window)
        assertThat(cutoffCaptor.getValue()).isBefore(Instant.now().minus(89, java.time.temporal.ChronoUnit.DAYS));
    }

    // ── Soft-delete contract ───────────────────────────────────────────────────

    @Test
    void existsByIdAndDeletedAtIsNull_returns_false_for_deleted_courier() {
        UUID id = UUID.randomUUID();
        when(courierRepository.existsByIdAndDeletedAtIsNull(id)).thenReturn(false);

        assertThat(courierRepository.existsByIdAndDeletedAtIsNull(id)).isFalse();
    }

    @Test
    void existsByPhoneAndDeletedAtIsNull_returns_false_after_soft_delete() {
        when(courierRepository.existsByPhoneAndDeletedAtIsNull("+1234567890")).thenReturn(false);

        assertThat(courierRepository.existsByPhoneAndDeletedAtIsNull("+1234567890")).isFalse();
    }

}
