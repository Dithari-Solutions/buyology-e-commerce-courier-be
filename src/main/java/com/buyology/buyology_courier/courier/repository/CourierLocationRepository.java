package com.buyology.buyology_courier.courier.repository;

import com.buyology.buyology_courier.courier.domain.CourierLocation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CourierLocationRepository extends JpaRepository<CourierLocation, UUID> {

    // Uses idx_courier_locations_courier_recorded — single latest ping per courier
    Optional<CourierLocation> findFirstByCourierIdOrderByRecordedAtDesc(UUID courierId);

    // Uses same composite index — time-windowed history for replay / audit
    Page<CourierLocation> findAllByCourierIdAndRecordedAtBetween(
            UUID courierId,
            Instant from,
            Instant to,
            Pageable pageable
    );

    // Bulk delete for the nightly TTL job — uses idx_courier_locations_recorded_at
    @Modifying
    @Query("DELETE FROM CourierLocation cl WHERE cl.recordedAt < :cutoff")
    int deleteByRecordedAtBefore(@Param("cutoff") Instant cutoff);
}
