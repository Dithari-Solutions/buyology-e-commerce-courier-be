package com.buyology.buyology_courier.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.SesV2ClientBuilder;

@Configuration
@ConditionalOnProperty(name = "notification.email.enabled", havingValue = "true")
public class AwsSesConfig {

    private final AwsSesProperties properties;

    public AwsSesConfig(AwsSesProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SesV2Client sesV2Client() {
        SesV2ClientBuilder builder = SesV2Client.builder()
                .region(Region.of(properties.getRegion()));

        if (properties.getAccessKey() != null && !properties.getAccessKey().isBlank()
                && properties.getSecretKey() != null && !properties.getSecretKey().isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }
}
