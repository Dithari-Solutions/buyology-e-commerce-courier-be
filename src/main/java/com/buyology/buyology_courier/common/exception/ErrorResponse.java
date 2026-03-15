package com.buyology.buyology_courier.common.exception;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(
        int status,
        String error,
        String message,
        String path,
        Instant timestamp,
        Map<String, String> fieldErrors   // populated only for validation failures
) {
    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(status, error, message, path, Instant.now(), null);
    }

    public static ErrorResponse validation(String path, Map<String, String> fieldErrors) {
        return new ErrorResponse(400, "Validation Failed", "Request validation failed", path, Instant.now(), fieldErrors);
    }
}
