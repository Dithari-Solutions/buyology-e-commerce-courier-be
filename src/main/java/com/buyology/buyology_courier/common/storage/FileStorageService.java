package com.buyology.buyology_courier.common.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;
import java.util.UUID;

/**
 * Facade over Contabo object storage.
 * Validates incoming files, builds S3 keys, and delegates to {@link ContaboObjectService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp"
    );
    private static final long MAX_FILE_SIZE_BYTES = 25 * 1024 * 1024; // 25 MB

    private final ContaboObjectService contaboObjectService;

    /**
     * Validates and stores a file under {@code couriers/<subDir>/<uuid>.<ext>}.
     *
     * @return S3 object key (stored in DB — convert to presigned URL before returning to clients)
     */
    public String store(MultipartFile file, String subDir) {
        validate(file);
        String extension = resolveExtension(file.getContentType());
        String key = "couriers/" + subDir + "/" + UUID.randomUUID() + "." + extension;
        return contaboObjectService.uploadFile(key, file);
    }

    /**
     * Generates a presigned URL for the given S3 key (valid 2 hours).
     * Returns {@code null} if the key is null or blank.
     */
    public String getPresignedUrl(String key) {
        return contaboObjectService.getPresignedUrl(key);
    }

    /**
     * Deletes the object at the given S3 key. No-op if key is null.
     */
    public void delete(String key) {
        contaboObjectService.deleteFile(key);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileStorageException("File must not be empty.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new FileStorageException(
                    "File size " + file.getSize() + " bytes exceeds the 10 MB limit.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new FileStorageException(
                    "Unsupported file type: " + contentType + ". Allowed: JPEG, PNG, WebP.");
        }
    }

    private String resolveExtension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png"  -> "png";
            case "image/webp" -> "webp";
            default           -> "bin";
        };
    }
}
