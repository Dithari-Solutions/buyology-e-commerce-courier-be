package com.buyology.buyology_courier.courier.service;

import com.buyology.buyology_courier.courier.domain.enums.CourierStatus;
import com.buyology.buyology_courier.courier.domain.enums.VehicleType;
import com.buyology.buyology_courier.courier.dto.request.*;
import com.buyology.buyology_courier.courier.dto.response.CourierLocationResponse;
import com.buyology.buyology_courier.courier.dto.response.CourierResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

public interface CourierService {

    CourierResponse create(CreateCourierRequest request);

    CourierResponse findById(UUID id);

    Page<CourierResponse> findAll(CourierStatus status, VehicleType vehicleType, Boolean isAvailable, Pageable pageable);

    CourierResponse update(UUID id, UpdateCourierRequest request);

    CourierResponse updateStatus(UUID id, UpdateCourierStatusRequest request);

    CourierResponse updateAvailability(UUID id, UpdateAvailabilityRequest request);

    void delete(UUID id);

    CourierLocationResponse recordLocation(UUID courierId, RecordLocationRequest request);

    CourierLocationResponse getLatestLocation(UUID courierId);

    Page<CourierLocationResponse> getLocationHistory(UUID courierId, Instant from, Instant to, Pageable pageable);
}
