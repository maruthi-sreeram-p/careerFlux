package com.careerflux.source.adapter;

/** Signals that an adapter could not read its source. Always translated into a health result. */
public class AdapterException extends RuntimeException {

    private final Integer httpStatus;

    public AdapterException(String message) {
        this(message, null, null);
    }

    public AdapterException(String message, Integer httpStatus) {
        this(message, httpStatus, null);
    }

    public AdapterException(String message, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }
}
