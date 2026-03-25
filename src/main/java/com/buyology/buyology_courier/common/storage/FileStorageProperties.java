package com.buyology.buyology_courier.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code file.*} properties from application.properties.
 * Registering as @ConfigurationProperties suppresses IDE "unknown property" warnings
 * and enables IDE auto-completion.
 */
@ConfigurationProperties(prefix = "file")
public record FileStorageProperties(

        /**
         * Local filesystem path where uploaded courier images are stored.
         * Default: {@code ./uploads}
         * Override in production with a volume-mounted path: {@code FILE_UPLOAD_DIR=/mnt/data/uploads}
         */
        String uploadDir,

        /**
         * Public URL prefix prepended to stored filenames in API responses.
         * Default: {@code /uploads}
         * Override when behind a CDN or object storage proxy: {@code FILE_BASE_URL=https://cdn.example.com}
         */
        String baseUrl
) {
    public FileStorageProperties {
        if (uploadDir == null || uploadDir.isBlank()) uploadDir = "./uploads";
        if (baseUrl   == null || baseUrl.isBlank())   baseUrl   = "/uploads";
    }
}
