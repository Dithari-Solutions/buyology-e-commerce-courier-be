package com.buyology.buyology_courier.courier.service.impl;

import com.buyology.buyology_courier.common.outbox.OutboxEvent;
import com.buyology.buyology_courier.common.outbox.OutboxEventRepository;
import com.buyology.buyology_courier.common.outbox.OutboxStatus;
import com.buyology.buyology_courier.courier.domain.Courier;
import com.buyology.buyology_courier.courier.domain.CourierLocation;
import com.buyology.buyology_courier.courier.domain.enums.CourierStatus;
import com.buyology.buyology_courier.courier.domain.enums.VehicleType;
import com.buyology.buyology_courier.courier.dto.request.*;
import com.buyology.buyology_courier.courier.dto.response.CourierLocationResponse;
import com.buyology.buyology_courier.courier.dto.response.CourierResponse;
import com.buyology.buyology_courier.courier.exception.CourierLocationNotFoundException;
import com.buyology.buyology_courier.courier.exception.CourierNotActiveException;
import com.buyology.buyology_courier.courier.exception.CourierNotFoundException;
import com.buyology.buyology_courier.courier.exception.DuplicatePhoneException;
import com.buyology.buyology_courier.courier.exception.RateLimitExceededException;
import com.buyology.buyology_courier.courier.mapper.CourierMapper;
import com.buyology.buyology_courier.courier.messaging.config.RabbitMQConfig;
import com.buyology.buyology_courier.courier.messaging.event.*;
import com.buyology.buyology_courier.courier.repository.CourierLocationRepository;
import com.buyology.buyology_courier.courier.repository.CourierRepository;
import com.buyology.buyology_courier.courier.repository.spec.CourierSpecification;
import com.buyology.buyology_courier.courier.service.CourierLookupService;
import com.buyology.buyology_courier.courier.service.CourierService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class CourierServiceImpl implements CourierService {

    private static final Duration MAX_LOCATION_HISTORY_RANGE = Duration.ofDays(7);
    private static final int MAX_LOCATION_PINGS_PER_MINUTE   = 60;

    private final CourierRepository         courierRepository;
    private final CourierLocationRepository locationRepository;
    private final CourierLookupService      courierLookupService;
    private final OutboxEventRepository     outboxEventRepository;
    // Still used for high-frequency location events (fire-and-forget is acceptable)
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper              objectMapper;
    private final MeterRegistry             meterRegistry;
    private final StringRedisTemplate       stringRedisTemplate;

    // ── Courier CRUD ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CourierResponse create(CreateCourierRequest request,
                                  String profileImageUrl,
                                  String drivingLicenceImageUrl) {
        if (courierRepository.existsByPhoneAndDeletedAtIsNull(request.phone())) {
            throw new DuplicatePhoneException(request.phone());
        }

        Courier courier = Courier.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .phone(request.phone())
                .email(request.email())
                .vehicleType(request.vehicleType())
                .profileImageUrl(profileImageUrl)
                .drivingLicenceImageUrl(drivingLicenceImageUrl)
                .status(CourierStatus.OFFLINE)
                .isAvailable(false)
                .build();

        log.info("Registering courier: phone={}", request.phone());
        Courier saved = courierRepository.save(courier);
        log.info("Courier registered: id={}", saved.getId());

        // Write to outbox in the SAME transaction — survives broker outages
        saveOutboxEvent(
                RabbitMQConfig.COURIER_REGISTERED_KEY,
                CourierRegisteredEvent.of(
                        saved.getId(), saved.getFirstName(), saved.getLastName(),
                        saved.getPhone(), saved.getEmail(), saved.getVehicleType()
                )
        );

        meterRegistry.counter("courier.registrations.total").increment();
        return CourierMapper.toResponse(saved);
    }

    @Override
    public CourierResponse findById(UUID id) {
        return CourierMapper.toResponse(getOrThrow(id));
    }

    @Override
    public Page<CourierResponse> findAll(
            CourierStatus status, VehicleType vehicleType, Boolean isAvailable, Pageable pageable) {
        return courierRepository
                .findAll(CourierSpecification.filter(status, vehicleType, isAvailable), pageable)
                .map(CourierMapper::toResponse);
    }

    @Override
    @Transactional
    public CourierResponse update(UUID id, UpdateCourierRequest request,
                                  String profileImageUrl,
                                  String drivingLicenceImageUrl) {
        // Always fetch a fresh managed entity for mutations — never use the cache.
        Courier courier = getOrThrow(id);

        if (request.firstName() != null)    courier.setFirstName(request.firstName());
        if (request.lastName() != null)     courier.setLastName(request.lastName());
        if (request.email() != null)        courier.setEmail(request.email());
        if (request.vehicleType() != null)  courier.setVehicleType(request.vehicleType());
        if (profileImageUrl != null)        courier.setProfileImageUrl(profileImageUrl);
        if (drivingLicenceImageUrl != null) courier.setDrivingLicenceImageUrl(drivingLicenceImageUrl);

        Courier saved = courierRepository.save(courier);
        courierLookupService.evict(id);
        return CourierMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public CourierResponse updateStatus(UUID id, UpdateCourierStatusRequest request) {
        Courier courier = getOrThrow(id);

        CourierStatus previousStatus = courier.getStatus();
        courier.setStatus(request.status());

        if (request.status() != CourierStatus.ACTIVE) {
            courier.setAvailable(false);
        }

        Courier saved = courierRepository.save(courier);
        log.info("Courier status updated: {} -> {}", previousStatus, request.status());
        courierLookupService.evict(id);

        saveOutboxEvent(
                RabbitMQConfig.COURIER_STATUS_CHANGED_KEY,
                CourierStatusChangedEvent.of(id, previousStatus, request.status())
        );

        meterRegistry.counter("courier.status.changes.total").increment();
        return CourierMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public CourierResponse updateAvailability(UUID id, UpdateAvailabilityRequest request) {
        Courier courier = getOrThrow(id);

        if (request.available() && courier.getStatus() != CourierStatus.ACTIVE) {
            throw new CourierNotActiveException(courier.getStatus());
        }

        courier.setAvailable(request.available());
        Courier saved = courierRepository.save(courier);
        courierLookupService.evict(id);

        saveOutboxEvent(
                RabbitMQConfig.COURIER_AVAILABILITY_CHANGED_KEY,
                CourierAvailabilityChangedEvent.of(id, request.available())
        );

        return CourierMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        log.info("Soft-deleting courier: id={}", id);
        Courier courier = getOrThrow(id);

        Instant deletedAt = Instant.now();
        courier.setDeletedAt(deletedAt);
        courier.setAvailable(false);
        courierRepository.save(courier);

        courierLookupService.evict(id);

        saveOutboxEvent(
                RabbitMQConfig.COURIER_DELETED_KEY,
                CourierDeletedEvent.of(id, deletedAt)
        );
    }

    // ── Location ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CourierLocationResponse recordLocation(UUID courierId, RecordLocationRequest request) {
        log.debug("Location ping: courierId={}, lat={}, lng={}", courierId, request.latitude(), request.longitude());

        // Rate limit: max pings per courier per minute using a Redis counter with 1-min TTL
        enforceLocationRateLimit(courierId);

        // Fast cache check (Redis) — avoids a full entity load on every GPS ping
        courierLookupService.requireActive(courierId);

        // Secondary DB check within THIS transaction to guard against the race where
        // a courier is soft-deleted between the cache hit above and the save below.
        // Without this, a deleted courier could accumulate new location rows.
        if (!courierRepository.existsByIdAndDeletedAtIsNull(courierId)) {
            courierLookupService.evict(courierId);
            throw new CourierNotFoundException();
        }

        // getReferenceById() returns a JPA proxy — sets the FK without loading the entity
        Courier courierRef = courierRepository.getReferenceById(courierId);

        CourierLocation location = CourierLocation.builder()
                .courier(courierRef)
                .latitude(request.latitude())
                .longitude(request.longitude())
                .heading(request.heading())
                .speed(request.speed())
                .accuracyMeters(request.accuracyMeters())
                .recordedAt(request.recordedAt() != null ? request.recordedAt() : Instant.now())
                .build();

        CourierLocation saved = locationRepository.save(location);

        // Location events are high-frequency — fire-and-forget is acceptable
        // (a missed ping is not a critical business event unlike registration/deletion).
        eventPublisher.publishEvent(
                CourierLocationUpdatedEvent.of(
                        courierId,
                        saved.getLatitude(), saved.getLongitude(),
                        saved.getHeading(), saved.getSpeed(),
                        saved.getRecordedAt()
                )
        );

        meterRegistry.counter("courier.location.pings.total").increment();
        return CourierMapper.toLocationResponse(saved);
    }

    @Override
    public CourierLocationResponse getLatestLocation(UUID courierId) {
        getOrThrow(courierId);

        return locationRepository
                .findFirstByCourierIdOrderByRecordedAtDesc(courierId)
                .map(CourierMapper::toLocationResponse)
                .orElseThrow(CourierLocationNotFoundException::new);
    }

    @Override
    public Page<CourierLocationResponse> getLocationHistory(
            UUID courierId, Instant from, Instant to, Pageable pageable) {

        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must be before 'to'.");
        }
        if (Duration.between(from, to).compareTo(MAX_LOCATION_HISTORY_RANGE) > 0) {
            throw new IllegalArgumentException("Time range cannot exceed 7 days.");
        }

        getOrThrow(courierId);

        return locationRepository
                .findAllByCourierIdAndRecordedAtBetween(courierId, from, to, pageable)
                .map(CourierMapper::toLocationResponse);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Courier getOrThrow(UUID id) {
        return courierRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(CourierNotFoundException::new);
    }

    /**
     * Redis-backed rate limiter. Increments a per-courier counter with a 1-minute TTL.
     * On first increment sets the TTL so the window resets automatically.
     */
    private void enforceLocationRateLimit(UUID courierId) {
        String key = "rate_limit:location:" + courierId;
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            stringRedisTemplate.expire(key, Duration.ofMinutes(1));
        }
        if (count != null && count > MAX_LOCATION_PINGS_PER_MINUTE) {
            throw new RateLimitExceededException(
                    "Location ping rate limit exceeded. Maximum " + MAX_LOCATION_PINGS_PER_MINUTE + " pings per minute per courier.");
        }
    }

    /**
     * Serialises the event to JSON and persists it to the outbox table within the
     * current transaction. {@link com.buyology.buyology_courier.common.outbox.OutboxPublisherJob}
     * picks this up and publishes to RabbitMQ, retrying on broker failure.
     */
    private void saveOutboxEvent(String routingKey, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            outboxEventRepository.save(OutboxEvent.builder()
                    .routingKey(routingKey)
                    .payload(payload)
                    .eventVersion(1)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build());
        } catch (JsonProcessingException ex) {
            meterRegistry.counter("courier.events.publish_failures.total").increment();
            throw new RuntimeException("Failed to serialise outbox event for routing key: " + routingKey, ex);
        }
    }
}
