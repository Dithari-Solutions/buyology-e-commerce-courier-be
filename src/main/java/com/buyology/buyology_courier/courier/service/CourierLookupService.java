package com.buyology.buyology_courier.courier.service;

import com.buyology.buyology_courier.courier.exception.CourierNotFoundException;
import com.buyology.buyology_courier.courier.repository.CourierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Cache layer for the high-frequency recordLocation path.
 *
 * Caches a Boolean existence flag — NOT the JPA entity. This sidesteps two problems:
 *   1. JPA entities don't serialise cleanly to Redis (Hibernate proxies, lazy fields).
 *   2. Cached entities used as input to save() cause Hibernate to merge a potentially
 *      stale detached object, silently overwriting concurrent changes.
 *
 * All mutation paths (update, delete, status change) BYPASS this service entirely and
 * fetch a fresh managed entity directly from CourierRepository.
 *
 * Cache: "active-couriers" (Redis, 5 min TTL — see RedisConfig)
 * Eviction happens immediately after any mutation that changes the courier's active state.
 *
 * Why a separate bean?
 *   Spring AOP proxies only intercept calls that cross bean boundaries.
 *   @Cacheable / @CacheEvict on methods in the SAME bean as the caller are ignored.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourierLookupService {

    private final CourierRepository courierRepository;

    /**
     * Returns {@code true} if the courier exists and is not deleted.
     * The return value is stored in Redis so subsequent calls skip the DB entirely.
     * Throws {@link CourierNotFoundException} (→ 404) if not found.
     */
    @Cacheable(value = "active-couriers", key = "#courierId")
    public boolean requireActive(UUID courierId) {
        if (!courierRepository.existsByIdAndDeletedAtIsNull(courierId)) {
            throw new CourierNotFoundException();
        }
        return true;
    }

    /**
     * Must be called after any operation that deletes, suspends, or mutates a courier.
     * Ensures the next recordLocation call re-validates against the DB.
     */
    @CacheEvict(value = "active-couriers", key = "#courierId")
    public void evict(UUID courierId) {
        // eviction only — no body needed
    }
}
