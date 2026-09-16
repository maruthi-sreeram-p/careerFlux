package com.careerflux.privacy.erasure;

/**
 * Where an erasure request is. See V24 for the lifecycle.
 *
 * <p>Open means the account still holds its data and a request is in flight:
 * waiting out the grace period, being carried out, or waiting to be retried.
 */
public enum ErasureStatus {

    GRACE_PERIOD,
    PROCESSING,
    COMPLETED,
    CANCELLED,
    FAILED;

    public boolean isOpen() {
        return this == GRACE_PERIOD || this == PROCESSING || this == FAILED;
    }
}
