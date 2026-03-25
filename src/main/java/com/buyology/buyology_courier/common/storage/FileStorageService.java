package com.buyology.buyology_courier.common.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

/**
 * Stores uploaded image files on the local filesystem.
 *
 * Files are written to {@code <uploadDir>/couriers/<subDir>/<uuid>.<ext>}
 * and exposed as {@code <baseUrl>/couriers/<subDir>/<uuid>.<ext>}.
 *
 * Allowed types: JPEG, PNG, WebP — no executables, no PDFs.
 */
@Service
@Slf4j
public class FileStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp"
    );
    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

    private final Path   uploadRoot;
    private final String baseUrl;

    public FileStorageService(FileStorageProperties props) {
        this.uploadRoot = Paths.get(props.uploadDir()).toAbsolutePath().normalize();
        this.baseUrl    = props.baseUrl();
        try {
            Files.createDirectories(this.uploadRoot);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create upload directory: " + uploadRoot, ex);
        }
    }

    /**
     * Store a multipart file under {@code couriers/<subDir>/} and return its public URL.
     *
     * @param file   the uploaded file — must be a non-empty image
     * @param subDir sub-directory name, e.g. {@code "profile"} or {@code "licence"}
     * @return public URL path to the stored file, e.g. {@code /uploads/couriers/profile/abc.jpg}
     */
    public String store(MultipartFile file, String subDir) {
        validate(file);

        String extension = resolveExtension(file.getContentType());
        String filename  = UUID.randomUUID() + "." + extension;
        Path   dir       = uploadRoot.resolve("couriers").resolve(subDir);
        Path   target    = dir.resolve(filename);

        try {
            Files.createDirectories(dir);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new FileStorageException("Failed to store file: " + filename, ex);
        }

        String url = baseUrl + "/couriers/" + subDir + "/" + filename;
        log.info("Stored file: path={}, url={}", target, url);
        return url;
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
