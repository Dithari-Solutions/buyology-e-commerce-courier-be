package com.buyology.buyology_courier.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisConfig {

    // active-couriers: Boolean flag cached per courier UUID.
    // Answers "does this non-deleted courier exist?" without loading the full entity.
    // Used exclusively by the high-frequency recordLocation path.
    private static final Duration ACTIVE_COURIER_TTL = Duration.ofMinutes(5);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(Object.class)));

        RedisCacheConfiguration activeCourierConfig = base.entryTtl(ACTIVE_COURIER_TTL);

        return RedisCacheManager.builder(connectionFactory)
                .withCacheConfiguration("active-couriers", activeCourierConfig)
                .build();
    }
}
