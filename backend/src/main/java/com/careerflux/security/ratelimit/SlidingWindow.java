package com.careerflux.security.ratelimit;

/**
 * One caller's recent history against one limit.
 *
 * <p>A ring of the last {@code limit} accepted timestamps. The slot about to be
 * overwritten holds the oldest of them, so deciding whether the caller has
 * already spent their allowance is a single comparison: if that oldest accept is
 * still inside the window, then {@code limit} accepts are inside the window and
 * the next one has to wait.
 *
 * <p>This is a true sliding window rather than a fixed one. A fixed window
 * resets on a clock boundary, which lets a caller spend a full allowance at the
 * end of one window and another immediately at the start of the next — twice the
 * intended rate, precisely at the moment somebody is trying. Here the window
 * always ends now.
 *
 * <p><b>Thread safety.</b> Every method is {@code synchronized} on the instance,
 * so the read of the oldest accept and the write of the new one are one atomic
 * step. That matters more than it looks: the obvious implementation — read a
 * count, compare, write it back — lets two simultaneous requests both read
 * {@code limit - 1} and both proceed. Locking is per key, and the critical
 * section is a comparison and an array store, so simultaneous requests from
 * different callers never touch the same lock.
 */
final class SlidingWindow {

    /** No accept recorded in this slot yet. Not a real time, so it can never be "inside the window". */
    private static final long NEVER = Long.MIN_VALUE;

    private final long[] acceptedAt;
    private int next;
    private long lastActivityAt;

    SlidingWindow(int limit, long now) {
        this.acceptedAt = new long[limit];
        java.util.Arrays.fill(this.acceptedAt, NEVER);
        this.lastActivityAt = now;
    }

    /**
     * Spends one unit of the allowance if there is one.
     *
     * @return zero when the request may proceed, otherwise the number of
     *         milliseconds until the oldest accept falls out of the window
     */
    synchronized long acquire(long now, long windowMillis) {
        // A refused attempt is still activity. Recording it stops a caller who
        // is being limited from letting their own entry expire and reappearing
        // with a fresh allowance.
        lastActivityAt = now;

        long oldest = acceptedAt[next];
        if (oldest != NEVER && now - oldest < windowMillis) {
            long waitFor = windowMillis - (now - oldest);
            return waitFor > 0 ? waitFor : 1;
        }

        acceptedAt[next] = now;
        next = (next + 1) % acceptedAt.length;
        return 0;
    }

    /**
     * Whether this entry can be dropped without changing any future decision.
     *
     * <p>True once nothing has touched it for a full window, at which point
     * every timestamp it holds is outside the window and a fresh entry would
     * behave identically.
     */
    synchronized boolean idleSince(long now, long windowMillis) {
        return now - lastActivityAt >= windowMillis;
    }

    synchronized long lastActivityAt() {
        return lastActivityAt;
    }
}
