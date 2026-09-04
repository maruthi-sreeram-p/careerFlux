package com.careerflux.common.error;

import org.springframework.http.HttpStatus;

/**
 * A URL CareerFlux refuses to fetch.
 *
 * <p>A 400 rather than a 403, because the request itself is malformed for our
 * purposes: the caller asked us to contact somewhere we do not contact. The
 * message states the category — loopback, private range, wrong scheme — but
 * never the resolved address, so an operator can fix a genuine mistake without
 * the endpoint becoming a way to map the network CareerFlux runs in.
 */
public class UnsafeUrlException extends AppException {

    public UnsafeUrlException(String message) {
        super(HttpStatus.BAD_REQUEST, "UNSAFE_URL", message);
    }
}
