package com.buyology.buyology_courier.common.storage;

import com.buyology.buyology_courier.config.ContaboProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.time.Duration;

@Service
@Slf4j
public class ContaboObjectService {

    private final S3Client       s3Client;
    private final S3Presigner    s3Presigner;
    private final ContaboProperties properties;

    public ContaboObjectService(S3Client s3Client, S3Presigner s3Presigner, ContaboProperties properties) {
        this.s3Client    = s3Client;
        this.s3Presigner = s3Presigner;
        this.properties  = properties;
    }

    /**
     * Uploads a multipart file to Contabo S3 and returns the S3 object key.
     */
    public String uploadFile(String key, MultipartFile file) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(properties.bucketName())
                    .key(key)
                    .contentType(file.getContentType())
                    .build();
            s3Client.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            log.info("[S3] Uploaded key={}", key);
            return key;
        } catch (IOException | RuntimeException ex) {
            log.error("[S3] Upload failed key={} error={}", key, ex.getMessage(), ex);
            throw new FileStorageException("Failed to upload file to object storage: " + key, ex);
        }
    }

    /**
     * Generates a presigned GET URL valid for 2 hours.
     * Returns {@code null} if the key is null or blank.
     */
    public String getPresignedUrl(String key) {
        if (key == null || key.isBlank()) return null;

        String cleanKey = key;
        if (key.startsWith("http")) {
            String base = properties.publicUrl();
            if (key.contains(base)) {
                cleanKey = key.substring(key.indexOf(base) + base.length());
                if (cleanKey.startsWith("/")) cleanKey = cleanKey.substring(1);
            } else if (key.contains(properties.bucketName())) {
                cleanKey = key.substring(key.indexOf(properties.bucketName()) + properties.bucketName().length());
                if (cleanKey.startsWith("/")) cleanKey = cleanKey.substring(1);
            } else {
                return key;
            }
        }

        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(properties.bucketName())
                .key(cleanKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(2))
                .getObjectRequest(getRequest)
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    /**
     * Deletes a single object by key.
     */
    public void deleteFile(String key) {
        if (key == null || key.isBlank()) return;
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucketName())
                .key(key)
                .build());
        log.info("[S3] Deleted key={}", key);
    }
}
