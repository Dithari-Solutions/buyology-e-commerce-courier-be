package com.buyology.buyology_courier.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "aws.ses")
public class AwsSesProperties {

    private String region;
    private String accessKey;
    private String secretKey;
    private String fromEmail;
    private String fromName;
    private String configurationSet;

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getFromEmail() { return fromEmail; }
    public void setFromEmail(String fromEmail) { this.fromEmail = fromEmail; }

    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }

    public String getConfigurationSet() { return configurationSet; }
    public void setConfigurationSet(String configurationSet) { this.configurationSet = configurationSet; }
}
