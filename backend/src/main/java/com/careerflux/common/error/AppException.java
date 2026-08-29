package com.careerflux.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base type for every deliberately-signalled application error. Anything that
 * does not extend this is treated as an unexpected fault and reported as a 500
 * with no internal detail leaked to the client.
 */
public abstract class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected AppException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    protected AppException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
