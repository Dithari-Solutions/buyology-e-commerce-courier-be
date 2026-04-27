package com.buyology.buyology_courier.assignment.service;

import java.util.List;
import java.util.UUID;

/**
 * Manages a Redis GEO index of currently active+available couriers.
 *
 * <p>The index is updated on every location ping and cleaned up when a courier
 * goes offline, is suspended, or is deleted. It is used by the assignment service
 * to find the nearest candidates for a delivery order.
 */
public interface CourierGeoService {

    /**
     * Represents a courier and their actual distance from a target point.
     */
    record NearbyCourier(UUID courierId, double distanceKm) {}

    /**
     * Adds or updates the courier's position in the GEO index.
     * Called from {@code CourierServiceImpl.recordLocation()} on every GPS ping.
     */
    void addOrUpdate(UUID courierId, double lat, double lng);

    /**
     * Removes the courier from the GEO index.
     * Called when the courier goes offline, is suspended, or is soft-deleted.
     */
    void remove(UUID courierId);

    /**
     * Returns a list of nearby couriers sorted by ascending distance from the given point,
     * within {@code radiusKm} kilometres. Returns an empty list if Redis is unavailable.
     *
     * @param lat      latitude of the reference point (pickup location)
     * @param lng      longitude of the reference point
     * @param radiusKm search radius in kilometres
     */
    List<NearbyCourier> findNearby(double lat, double lng, double radiusKm);
}
