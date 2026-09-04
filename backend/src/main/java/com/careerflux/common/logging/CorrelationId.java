package com.careerflux.common.logging;

import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.MDC;

/**
 * Puts an identifier on every log line produced while some piece of work runs.
 *
 * <p>{@code ingestion_runs.correlation_id} has always existed and has never
 * reached a log line, which meant the one identifier that could tie a run
 * together was visible in the database and nowhere in the logs. An operator
 * reading "Failed to ingest posting X" had no way to tell which of the day's
 * runs it belonged to, or what else happened during it.
 *
 * <p><b>Restores rather than clears.</b> Every scope puts back whatever value it
 * found, so a nested scope — an ingestion run started from inside a request that
 * already has an id — leaves the outer one intact when it finishes. Clearing
 * unconditionally would blank the rest of the outer request's logs, which is a
 * subtler bug than having no id at all.
 *
 * <p><b>The identifier is opaque.</b> It is a random token, never a user id, an
 * email or anything else about the person making the request: log lines travel
 * further than the database does.
 */
public final class CorrelationId {

    /** Referenced by name in the logging pattern in {@code application.yml}. */
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    /** A fresh identifier, short enough to read in a log line and stay unique in practice. */
    public static String generate() {
        return UUID.randomUUID().toString().substring(0, 16);
    }

    /**
     * Runs the supplier with {@code id} attached to every log line it produces.
     *
     * <p>The previous value is restored in a finally block, so a pooled thread
     * never carries one request's identifier into the next piece of work that
     * lands on it — the failure mode that makes correlation ids worse than
     * useless, because the lines are then confidently wrong.
     */
    public static <T> T with(String id, Supplier<T> work) {
        String previous = MDC.get(MDC_KEY);
        if (id != null && !id.isBlank()) {
            MDC.put(MDC_KEY, id);
        }
        try {
            return work.get();
        } finally {
            restore(previous);
        }
    }

    /** The same, for work that returns nothing. */
    public static void with(String id, Runnable work) {
        with(id, () -> {
            work.run();
            return null;
        });
    }

    /** The identifier attached to the current thread, or null when there is none. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    private static void restore(String previous) {
        if (previous == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, previous);
        }
    }
}
