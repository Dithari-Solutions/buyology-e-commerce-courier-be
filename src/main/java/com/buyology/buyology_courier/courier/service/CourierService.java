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

    /**
     * @param profileImageUrl      public URL of the stored profile photo, or null
     * @param drivingLicenceImageUrl public URL of the stored licence image, or null
     */
    CourierResponse create(CreateCourierRequest request,
                           String profileImageUrl,
                           String drivingLicenceImageUrl);

    CourierResponse findById(UUID id);

    Page<CourierResponse> findAll(CourierStatus status, VehicleType vehicleType, Boolean isAvailable, Pageable pageable);

    /**
     * @param profileImageUrl      new profile photo URL, or null to leave unchanged
     * @param drivingLicenceImageUrl new licence image URL, or null to leave unchanged
     */
    CourierResponse update(UUID id, UpdateCourierRequest request,
                           String profileImageUrl,
                           String drivingLicenceImageUrl);

    CourierResponse updateStatus(UUID id, UpdateCourierStatusRequest request);

    CourierResponse updateAvailability(UUID id, UpdateAvailabilityRequest request);

    void delete(UUID id);

    /**
     * Registers or replaces the courier's FCM device token.
     * Called by the mobile app immediately after login so push notifications
     * can be delivered to the current device.
     */
    void registerPushToken(UUID courierId, RegisterPushTokenRequest request);

    CourierLocationResponse recordLocation(UUID courierId, RecordLocationRequest request);

    CourierLocationResponse getLatestLocation(UUID courierId);

    Page<CourierLocationResponse> getLocationHistory(UUID courierId, Instant from, Instant to, Pageable pageable);
}
