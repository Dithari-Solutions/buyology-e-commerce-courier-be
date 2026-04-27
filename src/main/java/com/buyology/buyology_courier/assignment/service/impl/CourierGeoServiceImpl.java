package com.buyology.buyology_courier.assignment.service.impl;

import com.buyology.buyology_courier.assignment.service.CourierGeoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.Metrics;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CourierGeoServiceImpl implements CourierGeoService {

    /** Redis sorted-set key holding active courier positions (GEO index). */
    static final String GEO_KEY = "geo:couriers:active";

    private static final int MAX_GEO_RESULTS = 50;

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void addOrUpdate(UUID courierId, double lat, double lng) {
        try {
            // Redis GEO convention: Point(longitude, latitude)
            stringRedisTemplate.opsForGeo()
                    .add(GEO_KEY, new Point(lng, lat), courierId.toString());
        } catch (Exception ex) {
            // Non-critical: location data will be stale until next ping
            log.error("[GEO] Failed to update position for courierId={}: {}", courierId, ex.getMessage());
        }
    }

    @Override
    public void remove(UUID courierId) {
        try {
            // GEO index is backed by a sorted set — ZREM removes the member
            stringRedisTemplate.opsForZSet().remove(GEO_KEY, courierId.toString());
        } catch (Exception ex) {
            log.error("[GEO] Failed to remove courierId={} from index: {}", courierId, ex.getMessage());
        }
    }

    @Override
    public List<NearbyCourier> findNearby(double lat, double lng, double radiusKm) {
        try {
            GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                    stringRedisTemplate.opsForGeo().radius(
                            GEO_KEY,
                            new Circle(new Point(lng, lat), new Distance(radiusKm, Metrics.KILOMETERS)),
                            RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                    .includeDistance()
                                    .sortAscending()
                                    .limit(MAX_GEO_RESULTS)
                    );

            if (results == null) return Collections.emptyList();

            return results.getContent().stream()
                    .map(r -> new NearbyCourier(
                            UUID.fromString(r.getContent().getName()),
                            r.getDistance().getValue()))
                    .toList();

        } catch (Exception ex) {
            // Fail-open: assignment will fall back to "no candidates" and retry later
            log.error("[GEO] GEORADIUS query failed for lat={} lng={} radius={}km: {}",
                    lat, lng, radiusKm, ex.getMessage());
            return Collections.emptyList();
        }
    }
}
