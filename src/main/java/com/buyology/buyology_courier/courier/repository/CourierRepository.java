package com.buyology.buyology_courier.courier.repository;

import com.buyology.buyology_courier.courier.domain.Courier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface CourierRepository extends JpaRepository<Courier, UUID>, JpaSpecificationExecutor<Courier> {

    // Always scope to non-deleted records
    Optional<Courier> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByPhoneAndDeletedAtIsNull(String phone);

    // Used by CourierLookupService for cache-backed existence checks (recordLocation hot path)
    boolean existsByIdAndDeletedAtIsNull(UUID id);
}
