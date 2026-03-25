package com.buyology.buyology_courier.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code ecommerce.service.jwt.*} properties from application.properties.
 * Registering as @ConfigurationProperties suppresses IDE "unknown property" warnings
 * and enables IDE auto-completion.
 */
@ConfigurationProperties(prefix = "ecommerce.service.jwt")
public record EcommerceJwtProperties(

        /**
         * Classpath or file-system location of the ecommerce backend's RSA public key (PEM format).
         * Default: {@code classpath:ecommerce-public.pem}
         */
        String publicKeyLocation,

        /**
         * Expected {@code iss} claim in tokens signed by the ecommerce backend.
         * Default: {@code buyology-ecommerce-service}
         */
        String issuer
) {
    public EcommerceJwtProperties {
        if (publicKeyLocation == null || publicKeyLocation.isBlank())
            publicKeyLocation = "classpath:ecommerce-public.pem";
        if (issuer == null || issuer.isBlank())
            issuer = "buyology-ecommerce-service";
    }
}
