package com.buyology.buyology_courier.auth.job;

import com.buyology.buyology_courier.auth.repository.CourierRefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Nightly job that purges expired refresh tokens from the database.
 *
 * Expired tokens can never be used again (the refresh endpoint checks expiresAt),
 * so keeping them serves no purpose. Without this job the table grows without bound.
 *
 * Runs at 03:00 server time every night — off-peak and well after the location
 * cleanup job (02:00) to avoid competing for I/O.
 *
 * Revoked-but-not-expired tokens are intentionally kept until they expire naturally.
 * This preserves the audit trail for the token's remaining valid window.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenCleanupJob {

    private final CourierRefreshTokenRepository refreshTokenRepository;

    @Scheduled(cron = "${auth.refresh-token.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void purgeExpiredTokens() {
        Instant cutoff = Instant.now();
        log.info("RefreshTokenCleanupJob: deleting tokens expired before {}", cutoff);

        refreshTokenRepository.deleteExpiredBefore(cutoff);

        log.info("RefreshTokenCleanupJob: completed");
    }
}
