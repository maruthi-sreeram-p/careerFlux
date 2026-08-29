package com.careerflux.common.error;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The single error shape every CareerFlux endpoint returns. The frontend never
 * has to parse a stack trace or a raw Spring error page.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldViolation> violations,
        Map<String, Object> details) {

    public record FieldViolation(String field, String message) {
    }

    public static ApiError of(int status, String code, String message, String path) {
        return new ApiError(Instant.now(), status, code, message, path, null, null);
    }

    public static ApiError withViolations(int status, String code, String message, String path,
                                          List<FieldViolation> violations) {
        return new ApiError(Instant.now(), status, code, message, path, violations, null);
    }

    public ApiError withDetails(Map<String, Object> extra) {
        return new ApiError(timestamp, status, code, message, path, violations, extra);
    }
}
