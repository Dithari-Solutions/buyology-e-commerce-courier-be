package com.buyology.buyology_courier.auth.repository;

import com.buyology.buyology_courier.auth.domain.CourierRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CourierRefreshTokenRepository extends JpaRepository<CourierRefreshToken, UUID> {

    Optional<CourierRefreshToken> findByTokenHash(String tokenHash);

    // Revoke all active tokens for a courier (e.g. on password change or account suspension)
    @Modifying
    @Query("UPDATE CourierRefreshToken t SET t.revokedAt = :now WHERE t.courierId = :courierId AND t.revokedAt IS NULL")
    void revokeAllByCourierId(@Param("courierId") UUID courierId, @Param("now") Instant now);

    // Maintenance: delete expired tokens older than the given cutoff
    @Modifying
    @Query("DELETE FROM CourierRefreshToken t WHERE t.expiresAt < :cutoff")
    void deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
