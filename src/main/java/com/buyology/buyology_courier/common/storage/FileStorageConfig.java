package com.buyology.buyology_courier.common.storage;

import org.springframework.context.annotation.Configuration;

/**
 * Placeholder retained so the package remains consistent.
 * File serving is now handled by Contabo object storage — presigned URLs
 * are generated on demand by {@link FileStorageService#getPresignedUrl(String)}.
 */
@Configuration
public class FileStorageConfig {
}
