package com.careerflux.common.error;

import org.springframework.http.HttpStatus;

/**
 * The caller has spent their allowance for this endpoint.
 *
 * <p>Carries how long they should wait so the handler can set {@code Retry-After}.
 * The message says nothing about which limit was hit, what the ceiling is, or
 * how much of it is left: those describe the defence to whoever is testing it.
 */
public class TooManyRequestsException extends AppException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Too many requests. Please wait a moment and try again.");
        this.retryAfterSeconds = Math.max(retryAfterSeconds, 1);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
