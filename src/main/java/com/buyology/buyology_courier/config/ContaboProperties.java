package com.buyology.buyology_courier.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contabo.s3")
public record ContaboProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucketName,
        String publicUrl
) {}
