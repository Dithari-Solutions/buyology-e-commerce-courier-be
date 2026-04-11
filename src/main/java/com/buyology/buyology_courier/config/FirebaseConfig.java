package com.buyology.buyology_courier.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Initialises the Firebase Admin SDK and exposes a {@link FirebaseMessaging} bean.
 *
 * <p>Only active when {@code firebase.enabled=true} (set via {@code FIREBASE_ENABLED}
 * env var). When disabled the bean is absent and
 * {@code CourierNotificationServiceImpl} skips FCM pushes entirely — no startup
 * failure, no NPE.
 *
 * <h3>Service account JSON</h3>
 * Set the env var {@code FIREBASE_SERVICE_ACCOUNT_JSON} to the full JSON string
 * downloaded from Firebase Console → Project Settings → Service Accounts →
 * Generate new private key.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "true")
public class FirebaseConfig {

    @Value("${firebase.service-account-json}")
    private String serviceAccountJson;

    @Bean
    public FirebaseMessaging firebaseMessaging() throws IOException {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            throw new IllegalStateException(
                    "firebase.enabled=true but FIREBASE_SERVICE_ACCOUNT_JSON is empty. " +
                    "Set the env var to the base64-encoded Firebase service account JSON.");
        }

        // The private_key field inside the JSON contains real newlines which break
        // .env file parsing. Store the secret base64-encoded; detect and decode here.
        byte[] jsonBytes;
        String trimmed = serviceAccountJson.trim();
        if (trimmed.startsWith("{")) {
            // Plain JSON (local dev with docker-compose inline value)
            jsonBytes = trimmed.getBytes(StandardCharsets.UTF_8);
        } else {
            // Base64-encoded JSON (GitHub secret → .env → container)
            jsonBytes = Base64.getDecoder().decode(trimmed);
        }

        GoogleCredentials credentials = GoogleCredentials.fromStream(
                new ByteArrayInputStream(jsonBytes)
        );

        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .build();

        FirebaseApp app;
        if (FirebaseApp.getApps().isEmpty()) {
            app = FirebaseApp.initializeApp(options);
        } else {
            app = FirebaseApp.getInstance();
        }

        log.info("[Firebase] FirebaseApp initialised — FCM push notifications enabled");
        return FirebaseMessaging.getInstance(app);
    }
}
