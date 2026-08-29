package com.careerflux.source.service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-source request pacing.
 *
 * <p>A token bucket keyed by source id, refilled at the source's configured rate.
 * Being a good citizen of somebody else's server is not optional, and the limit
 * is enforced here rather than trusted to each adapter.
 *
 * <p>This is in-memory, which is correct for the single node CareerFlux runs on
 * today. The moment ingestion runs on more than one instance this needs to move
 * behind a shared counter (Redis is the obvious choice) — that is the point at
 * which Redis earns its place in the stack, and not before.
 */
@Component
public class SourceRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SourceRateLimiter.class);
    private static final long MAX_WAIT_MILLIS = 30_000;

    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Blocks until this source may be called again, up to a 30 second ceiling.
     *
     * @return false when the caller waited the maximum and should give up rather
     *         than hammer the source.
     */
    public boolean acquire(UUID sourceId, int permitsPerMinute, Integer crawlDelaySeconds) {
        int effectiveRate = permitsPerMinute;
        if (crawlDelaySeconds != null && crawlDelaySeconds > 0) {
            // A declared crawl-delay always wins over our own configuration.
            effectiveRate = Math.min(effectiveRate, Math.max(1, 60 / crawlDelaySeconds));
        }
        if (effectiveRate <= 0) {
            return false;
        }

        Bucket bucket = buckets.computeIfAbsent(sourceId, id -> new Bucket());
        long intervalMillis = Duration.ofMinutes(1).toMillis() / effectiveRate;

        synchronized (bucket) {
            long now = System.currentTimeMillis();
            long earliest = bucket.lastCallAt + intervalMillis;
            if (now >= earliest) {
                bucket.lastCallAt = now;
                return true;
            }
            long waitFor = earliest - now;
            if (waitFor > MAX_WAIT_MILLIS) {
                log.debug("Source {} would need {} ms of pacing; skipping this attempt", sourceId, waitFor);
                return false;
            }
            try {
                bucket.wait(waitFor);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
            bucket.lastCallAt = System.currentTimeMillis();
            return true;
        }
    }

    /** Clears pacing state, used when a source is retired or its configuration changes. */
    public void forget(UUID sourceId) {
        buckets.remove(sourceId);
    }

    private static final class Bucket {
        private long lastCallAt;
    }
}
