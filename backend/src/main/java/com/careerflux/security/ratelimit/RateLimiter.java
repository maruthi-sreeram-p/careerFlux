package com.careerflux.security.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import com.careerflux.common.error.TooManyRequestsException;
import com.careerflux.config.CareerFluxProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Server-side request ceilings, counted in the memory of this JVM.
 *
 * <h2>Why in memory</h2>
 *
 * <p>The verified pilot topology is <b>one backend instance</b>: a single
 * {@code careerflux-backend} container, no replica count, no load balancer, no
 * orchestration. With one instance a per-instance counter <i>is</i> the global
 * counter, and a shared store would add a network hop, a new failure mode and an
 * operational dependency in exchange for nothing.
 *
 * <h2>What this is not</h2>
 *
 * <p><b>This is not distributed rate limiting and must not be described as
 * such.</b> The counters live in one heap. Run a second instance behind a load
 * balancer and every limit here silently becomes per-instance: two instances
 * mean twice the effective ceiling, and the sign-in limit in particular becomes
 * avoidable by landing on the other node. Horizontal scaling therefore requires
 * replacing this with shared state, Redis or a database-backed counter, and that
 * replacement is deliberately not built yet, because building distributed
 * infrastructure for a deployment that has one instance is guessing at a problem
 * nobody has.
 *
 * <p><b>Counters reset when the process restarts.</b> A deploy or a crash gives
 * everyone, including somebody midway through guessing a password, a fresh
 * allowance. That is accepted for the pilot: restarts are rare and operator
 * driven, the windows are minutes long, and the alternative is writing security
 * counters to a database on every request. It is a real limitation and is
 * recorded as one rather than glossed over.
 *
 * <h2>Memory</h2>
 *
 * <p>State is one {@link SlidingWindow} per active caller per action, each
 * holding {@code limit} timestamps, well under a kilobyte. Two things keep the
 * map from growing without bound:
 *
 * <ul>
 *   <li><b>Expiry.</b> An entry untouched for a full window can be dropped
 *       without changing any later decision, and is.
 *   <li><b>A hard ceiling.</b> Authenticated keys are bounded by the number of
 *       accounts, but the sign-in key contains an address the caller chooses, so
 *       it is attacker-controlled. At the ceiling the map is swept, and if that
 *       does not free space the least recently used entries are evicted.
 * </ul>
 *
 * <p>Sweeping is opportunistic rather than scheduled: it happens on the request
 * path, at most once a minute, and only once the map is large enough to be worth
 * walking. That keeps the limiter self-contained, so it does not depend on the
 * scheduler being enabled, which on this deployment it is not.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** How often the map is walked for dead entries, at most. */
    private static final long SWEEP_INTERVAL_MILLIS = 60_000L;

    /** Below this size the map is not worth walking. */
    private static final int SWEEP_FLOOR = 256;

    /** What fraction of the map is evicted when the ceiling is reached and sweeping did not help. */
    private static final int EVICTION_DIVISOR = 10;

    private final Map<Key, SlidingWindow> windows = new ConcurrentHashMap<>();
    private final Map<RateLimitedAction, Rule> rules;
    private final boolean enabled;
    private final int maxTrackedKeys;
    private final LongSupplier clockMillis;
    private final AtomicLong lastSweepAt = new AtomicLong(0);

    @Autowired
    public RateLimiter(CareerFluxProperties properties) {
        // Monotonic rather than wall clock. A window measured with
        // currentTimeMillis would be widened, or erased outright, by an NTP step
        // or a daylight saving change.
        this(properties, () -> System.nanoTime() / 1_000_000L);
    }

    RateLimiter(CareerFluxProperties properties, LongSupplier clockMillis) {
        CareerFluxProperties.RateLimit config = properties.rateLimit();
        this.clockMillis = clockMillis;
        this.enabled = config.enabled();
        this.maxTrackedKeys = Math.max(config.maxTrackedKeys(), 1);

        Map<RateLimitedAction, Rule> table = new EnumMap<>(RateLimitedAction.class);
        table.put(RateLimitedAction.LOGIN, rule(config.loginAttempts(), config.loginWindow()));
        table.put(RateLimitedAction.RESUME_UPLOAD, rule(config.resumeUploads(), config.resumeUploadWindow()));
        table.put(RateLimitedAction.CANDIDATE_DISCOVERY,
                rule(config.discoveryRequests(), config.discoveryWindow()));
        table.put(RateLimitedAction.REQUIREMENT_CREATE,
                rule(config.requirementCreations(), config.requirementWindow()));
        table.put(RateLimitedAction.SHORTLIST_MUTATION,
                rule(config.shortlistMutations(), config.shortlistWindow()));
        this.rules = Map.copyOf(table);

        if (!enabled) {
            log.warn("Request rate limiting is DISABLED. Sign-in, uploads and discovery are unthrottled.");
        }
    }

    private static Rule rule(int limit, Duration window) {
        // A limit of zero would refuse everybody, and a negative one would index
        // into an empty ring. Refuse to start on a nonsense ceiling rather than
        // serve with a limiter that cannot work.
        if (limit < 1) {
            throw new IllegalStateException(
                    "A rate limit must allow at least one request; check careerflux.rate-limit.*");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalStateException(
                    "A rate limit window must be positive; check careerflux.rate-limit.*");
        }
        return new Rule(limit, window.toMillis());
    }

    /**
     * Charges one request against an authenticated account.
     *
     * @throws TooManyRequestsException when the account has spent its allowance
     */
    public void check(RateLimitedAction action, UUID accountId) {
        check(action, "account:" + accountId);
    }

    /**
     * Charges one sign-in attempt.
     *
     * <p>Keyed on the client address <i>and</i> the identity being tried, both of
     * which are known before anything is authenticated. This runs ahead of the
     * password check and never looks at a token.
     *
     * <p>The identity is normalised exactly as sign-in normalises it. That is not
     * tidiness. The account lookup is case-insensitive, so {@code Priya@x.edu},
     * {@code priya@x.edu} and {@code " PRIYA@x.edu "} all reach the same account;
     * keying on the raw string would hand an attacker a fresh allowance for every
     * spelling of one address, which is a limiter that only stops people who are
     * not trying.
     *
     * <p>Nothing here reveals whether an address exists. The decision is made
     * before any lookup, so a throttled response is identical for a real account
     * and an invented one.
     */
    public void checkLogin(String clientAddress, String loginIdentity) {
        String identity = loginIdentity == null ? "" : loginIdentity.strip().toLowerCase(Locale.ROOT);
        String address = clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress;
        check(RateLimitedAction.LOGIN, "login:" + address + "|" + identity);
    }

    private void check(RateLimitedAction action, String subject) {
        if (!enabled) {
            return;
        }
        Rule rule = rules.get(action);
        long now = clockMillis.getAsLong();
        maybeSweep(now);

        Key key = new Key(action, subject);
        SlidingWindow window = windows.get(key);
        if (window == null) {
            makeRoomIfFull(now);
            window = windows.computeIfAbsent(key, ignored -> new SlidingWindow(rule.limit(), now));
        }

        long waitMillis = window.acquire(now, rule.windowMillis());
        if (waitMillis > 0) {
            // The action and the fact of refusal, never the identity or the
            // address being tried. Logging those would write attempted
            // usernames, and near-miss passwords typed into the username box,
            // into a file that outlives the request.
            log.info("Rate limit reached for {}; refused for {} ms", action, waitMillis);
            throw new TooManyRequestsException((waitMillis + 999) / 1000);
        }
    }

    /** Drops entries that can no longer affect a decision. Cheap, and at most once a minute. */
    private void maybeSweep(long now) {
        if (windows.size() < SWEEP_FLOOR) {
            return;
        }
        long previous = lastSweepAt.get();
        if (now - previous < SWEEP_INTERVAL_MILLIS) {
            return;
        }
        // Only one thread sweeps; the rest carry on serving.
        if (lastSweepAt.compareAndSet(previous, now)) {
            sweepExpired();
        }
    }

    /**
     * Removes every entry that has been idle for a full window.
     *
     * <p>Package-private so a test can trigger it deterministically rather than
     * waiting a minute of real time for the opportunistic path to fire.
     */
    int sweepExpired() {
        long now = clockMillis.getAsLong();
        int before = windows.size();
        windows.entrySet().removeIf(entry -> {
            Rule rule = rules.get(entry.getKey().action());
            return entry.getValue().idleSince(now, rule.windowMillis());
        });
        return before - windows.size();
    }

    /**
     * Keeps the map under its ceiling before a new key is added.
     *
     * <p>Sweeping normally frees plenty. If it does not, the oldest entries go.
     * Evicting resets those callers' allowances, which is a real if narrow
     * weakening; reaching this path at all means tens of thousands of distinct
     * callers inside one window, which on a single-college deployment is an
     * attack rather than a Monday. The alternatives are worse: refusing new keys
     * turns the limiter into an outage, and letting the map grow turns it into an
     * out-of-memory error.
     */
    private void makeRoomIfFull(long now) {
        if (windows.size() < maxTrackedKeys) {
            return;
        }
        lastSweepAt.set(now);
        int swept = sweepExpired();
        if (windows.size() < maxTrackedKeys) {
            log.info("Rate limiter at its key ceiling; sweeping freed {} entries", swept);
            return;
        }

        int target = Math.max(maxTrackedKeys / EVICTION_DIVISOR, 1);
        List<Map.Entry<Key, SlidingWindow>> byAge = new ArrayList<>(windows.entrySet());
        byAge.sort(Comparator.comparingLong(entry -> entry.getValue().lastActivityAt()));
        for (int i = 0; i < target && i < byAge.size(); i++) {
            windows.remove(byAge.get(i).getKey());
        }
        log.warn("Rate limiter reached its ceiling of {} tracked keys and evicted the {} least "
                + "recently used. Limits for those callers have been reset.", maxTrackedKeys, target);
    }

    /** How many callers are currently tracked. For tests and diagnostics. */
    int trackedKeys() {
        return windows.size();
    }

    boolean isEnabled() {
        return enabled;
    }

    /** The configured ceiling for an action, so tests cannot drift from configuration. */
    int limitFor(RateLimitedAction action) {
        return rules.get(action).limit();
    }

    private record Rule(int limit, long windowMillis) {
    }

    private record Key(RateLimitedAction action, String subject) {
    }
}
