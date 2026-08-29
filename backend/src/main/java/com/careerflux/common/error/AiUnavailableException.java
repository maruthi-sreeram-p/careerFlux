package com.careerflux.common.error;

import org.springframework.http.HttpStatus;

/**
 * Raised when an AI-backed capability is requested but no model is configured or
 * the provider call failed. Callers are expected to fall back to deterministic
 * behaviour rather than surface a broken screen.
 */
public class AiUnavailableException extends AppException {

    public AiUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", message);
    }

    public AiUnavailableException(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", message, cause);
    }
}
