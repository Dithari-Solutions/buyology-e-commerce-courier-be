package com.buyology.buyology_courier.delivery.domain.enums;

public enum DeliveryStatus {
    CREATED,
    COURIER_ASSIGNED,
    COURIER_ACCEPTED,
    ARRIVED_AT_PICKUP,
    PICKED_UP,
    ON_THE_WAY,
    ARRIVED_AT_DESTINATION,
    DELIVERED,
    FAILED,
    CANCELLED
}
