package com.buyology.buyology_courier.courier.mapper;

import com.buyology.buyology_courier.courier.domain.Courier;
import com.buyology.buyology_courier.courier.domain.CourierLocation;
import com.buyology.buyology_courier.courier.dto.response.CourierLocationResponse;
import com.buyology.buyology_courier.courier.dto.response.CourierResponse;

public final class CourierMapper {

    private CourierMapper() {}

    public static CourierResponse toResponse(Courier courier) {
        return new CourierResponse(
                courier.getId(),
                courier.getFirstName(),
                courier.getLastName(),
                courier.getPhone(),
                courier.getEmail(),
                courier.getVehicleType(),
                courier.getStatus(),
                courier.isAvailable(),
                courier.getRating(),
                courier.getProfileImageUrl(),
                courier.getDrivingLicenceImageUrl(),
                courier.getCreatedAt(),
                courier.getUpdatedAt()
        );
    }

    public static CourierLocationResponse toLocationResponse(CourierLocation location) {
        return new CourierLocationResponse(
                location.getId(),
                location.getCourier().getId(),
                location.getLatitude(),
                location.getLongitude(),
                location.getHeading(),
                location.getSpeed(),
                location.getAccuracyMeters(),
                location.getRecordedAt()
        );
    }
}
