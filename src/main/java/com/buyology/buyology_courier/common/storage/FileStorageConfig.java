package com.buyology.buyology_courier.common.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Registers {@link FileStorageProperties} for binding and serves uploaded files
 * from the local filesystem at the public URL prefix {@code /uploads/**}.
 *
 * Example: a file stored at {@code ./uploads/couriers/profile/abc.jpg} is
 * accessible at {@code GET /uploads/couriers/profile/abc.jpg}.
 */
@Configuration
@EnableConfigurationProperties(FileStorageProperties.class)
public class FileStorageConfig implements WebMvcConfigurer {

    private final FileStorageProperties props;

    public FileStorageConfig(FileStorageProperties props) {
        this.props = props;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String absolutePath = Paths.get(props.uploadDir()).toAbsolutePath().normalize().toString();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + absolutePath + "/");
    }
}
