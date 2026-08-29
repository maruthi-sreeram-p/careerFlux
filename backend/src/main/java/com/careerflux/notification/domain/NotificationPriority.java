package com.careerflux.notification.domain;

/**
 * How loudly a candidate is told. Mapped from the match score bands in
 * {@code careerflux.matching.*}: 95+ is immediate, 85-94 is high priority,
 * 70-84 waits for the daily digest, and anything below 70 is never sent at all.
 */
public enum NotificationPriority {
    IMMEDIATE,
    HIGH,
    DIGEST,
    LOW
}
