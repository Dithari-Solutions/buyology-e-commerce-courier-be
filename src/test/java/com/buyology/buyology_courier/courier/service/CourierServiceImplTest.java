package com.buyology.buyology_courier.courier.service;

import com.buyology.buyology_courier.assignment.service.CourierGeoService;
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
import com.buyology.buyology_courier.courier.messaging.config.RabbitMQConfig;
import com.buyology.buyology_courier.courier.repository.CourierLocationRepository;
import com.buyology.buyology_courier.courier.repository.CourierRepository;
import com.buyology.buyology_courier.courier.service.impl.CourierServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CourierServiceImplTest {

    @Mock CourierRepository courierRepository;
    @Mock CourierLocationRepository locationRepository;
    @Mock CourierLookupService courierLookupService;
    @Mock CourierGeoService courierGeoService;
    @Mock OutboxEventRepository outboxEventRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock StringRedisTemplate stringRedisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private MeterRegistry meterRegistry;
    private CourierServiceImpl service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new CourierServiceImpl(
                courierRepository,
                locationRepository,
                courierLookupService,
                courierGeoService,
                outboxEventRepository,
                eventPublisher,
                new ObjectMapper().findAndRegisterModules(),
                meterRegistry,
                stringRedisTemplate
        );

    }

    @Nested
    class Create {
        @Test
        void succeeds_and_saves_outbox_event() {
            var request = CreateCourierRequest.builder()
                    .firstName("Ada").lastName("Lovelace")
                    .phone("+1234567890").email("ada@test.com")
                    .vehicleType(VehicleType.BICYCLE)
                    .build();

            var saved = courierFixture();
            when(courierRepository.existsByPhoneAndDeletedAtIsNull(request.phone())).thenReturn(false);
            when(courierRepository.save(any())).thenReturn(saved);
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CourierResponse result = service.create(request, null, null);

            assertThat(result.id()).isEqualTo(saved.getId());

            ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(outboxCaptor.capture());
            OutboxEvent outbox = outboxCaptor.getValue();
            assertThat(outbox.getRoutingKey()).isEqualTo(RabbitMQConfig.COURIER_REGISTERED_KEY);
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(outbox.getPayload()).contains("courierId");
        }

        @Test
        void throws_DuplicatePhoneException_when_phone_taken() {
            var request = CreateCourierRequest.builder()
                    .firstName("Ada").lastName("Lovelace")
                    .phone("+1234567890").vehicleType(VehicleType.BICYCLE).build();

            when(courierRepository.existsByPhoneAndDeletedAtIsNull(request.phone())).thenReturn(true);

            assertThatThrownBy(() -> service.create(request, null, null))
                    .isInstanceOf(DuplicatePhoneException.class);
            verify(courierRepository, never()).save(any());
        }
    }

    @Nested
    class UpdateStatus {
        @Test
        void saves_outbox_event_with_status_changed_routing_key() {
            var courier = courierFixture();
            courier.setStatus(CourierStatus.OFFLINE);

            when(courierRepository.findByIdAndDeletedAtIsNull(courier.getId()))
                    .thenReturn(Optional.of(courier));
            when(courierRepository.save(any())).thenReturn(courier);
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.updateStatus(courier.getId(), new UpdateCourierStatusRequest(CourierStatus.ACTIVE));

            ArgumentCaptor<OutboxEvent> cap = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(cap.capture());
            assertThat(cap.getValue().getRoutingKey()).isEqualTo(RabbitMQConfig.COURIER_STATUS_CHANGED_KEY);
        }

        @Test
        void sets_available_false_when_status_not_active() {
            var courier = courierFixture();
            courier.setStatus(CourierStatus.ACTIVE);
            courier.setAvailable(true);

            when(courierRepository.findByIdAndDeletedAtIsNull(courier.getId()))
                    .thenReturn(Optional.of(courier));
            when(courierRepository.save(any())).thenReturn(courier);
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.updateStatus(courier.getId(), new UpdateCourierStatusRequest(CourierStatus.SUSPENDED));

            assertThat(courier.isAvailable()).isFalse();
        }
    }

    @Nested
    class UpdateAvailability {
        @Test
        void throws_when_setting_available_on_non_active_courier() {
            var courier = courierFixture();
            courier.setStatus(CourierStatus.OFFLINE);

            when(courierRepository.findByIdAndDeletedAtIsNull(courier.getId()))
                    .thenReturn(Optional.of(courier));

            assertThatThrownBy(() ->
                    service.updateAvailability(courier.getId(), new UpdateAvailabilityRequest(true)))
                    .isInstanceOf(CourierNotActiveException.class)
                    .hasMessageContaining("ACTIVE");
        }
    }

    @Nested
    class Delete {
        @Test
        void soft_deletes_and_evicts_cache() {
            var courier = courierFixture();
            when(courierRepository.findByIdAndDeletedAtIsNull(courier.getId()))
                    .thenReturn(Optional.of(courier));
            when(courierRepository.save(any())).thenReturn(courier);
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.delete(courier.getId());

            assertThat(courier.getDeletedAt()).isNotNull();
            assertThat(courier.isAvailable()).isFalse();
            verify(courierLookupService).evict(courier.getId());
            verify(courierGeoService).remove(courier.getId());

            ArgumentCaptor<OutboxEvent> cap = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(cap.capture());
            assertThat(cap.getValue().getRoutingKey()).isEqualTo(RabbitMQConfig.COURIER_DELETED_KEY);
        }
    }

    @Nested
    class RecordLocation {
        @BeforeEach
        void stubRedis() {
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenReturn(1L);
        }

        @Test
        void throws_CourierNotFoundException_when_courier_deleted_between_cache_and_save() {
            UUID id = UUID.randomUUID();
            when(courierLookupService.requireActive(id)).thenReturn(true);
            when(courierRepository.existsByIdAndDeletedAtIsNull(id)).thenReturn(false);

            assertThatThrownBy(() ->
                    service.recordLocation(id, locationRequest()))
                    .isInstanceOf(CourierNotFoundException.class);

            verify(locationRepository, never()).save(any());
            verify(courierLookupService).evict(id);
        }

        @Test
        void throws_RateLimitExceededException_when_limit_exceeded() {
            UUID id = UUID.randomUUID();
            when(valueOps.increment(anyString())).thenReturn(61L);

            assertThatThrownBy(() -> service.recordLocation(id, locationRequest()))
                    .isInstanceOf(RateLimitExceededException.class);
        }

        @Test
        void saves_location_and_publishes_event_on_success() {
            var courier = courierFixture();
            var location = locationFixture(courier);

            when(courierRepository.existsByIdAndDeletedAtIsNull(courier.getId())).thenReturn(true);
            when(courierRepository.getReferenceById(courier.getId())).thenReturn(courier);
            when(locationRepository.save(any())).thenReturn(location);

            CourierLocationResponse result = service.recordLocation(courier.getId(), locationRequest());

            assertThat(result.courierId()).isEqualTo(courier.getId());
            verify(eventPublisher).publishEvent(any(Object.class));
            verify(courierGeoService).addOrUpdate(eq(courier.getId()), anyDouble(), anyDouble());
        }

        private RecordLocationRequest locationRequest() {
            return RecordLocationRequest.builder()
                    .latitude(new BigDecimal("37.7749"))
                    .longitude(new BigDecimal("-122.4194"))
                    .build();
        }
    }

    @Nested
    class GetLocationHistory {
        @Test
        void throws_when_from_is_after_to() {
            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            assertThatThrownBy(() ->
                    service.getLocationHistory(id, now, now.minusSeconds(1), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'from' must be before 'to'");
        }

        @Test
        void throws_when_range_exceeds_7_days() {
            UUID id = UUID.randomUUID();
            Instant from = Instant.now().minusSeconds(8 * 24 * 3600);
            Instant to = Instant.now();
            assertThatThrownBy(() ->
                    service.getLocationHistory(id, from, to, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("7 days");
        }
    }

    private Courier courierFixture() {
        return Courier.builder()
                .id(UUID.randomUUID())
                .firstName("Ada").lastName("Lovelace")
                .phone("+1234567890").email("ada@test.com")
                .vehicleType(VehicleType.BICYCLE)
                .status(CourierStatus.ACTIVE)
                .isAvailable(true)
                .build();
    }

    private CourierLocation locationFixture(Courier courier) {
        return CourierLocation.builder()
                .id(UUID.randomUUID())
                .courier(courier)
                .latitude(new BigDecimal("37.7749"))
                .longitude(new BigDecimal("-122.4194"))
                .recordedAt(Instant.now())
                .build();
    }
}