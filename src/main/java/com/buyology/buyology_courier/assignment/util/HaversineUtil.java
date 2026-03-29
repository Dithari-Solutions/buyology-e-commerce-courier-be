package com.buyology.buyology_courier.assignment.util;

/**
 * Stateless utility for great-circle distance and travel-time estimates.
 * Uses the Haversine formula — accurate to ~0.5% for distances under 500 km.
 */
public final class HaversineUtil {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private HaversineUtil() {}

    /**
     * Returns the straight-line distance in kilometres between two geographic points.
     *
     * @param lat1 latitude of point 1 in decimal degrees
     * @param lng1 longitude of point 1 in decimal degrees
     * @param lat2 latitude of point 2 in decimal degrees
     * @param lng2 longitude of point 2 in decimal degrees
     */
    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Returns estimated travel time in minutes for the given distance and speed.
     *
     * @param distanceKm distance in kilometres
     * @param speedKmh   average speed in km/h
     */
    public static double travelTimeMinutes(double distanceKm, double speedKmh) {
        if (speedKmh <= 0) return Double.MAX_VALUE;
        return (distanceKm / speedKmh) * 60.0;
    }
}
