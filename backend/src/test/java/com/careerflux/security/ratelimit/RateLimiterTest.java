package com.careerflux.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.careerflux.common.error.TooManyRequestsException;
import com.careerflux.config.CareerFluxProperties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The limiter itself, on a clock the test controls.
 *
 * <p>Real time is unusable here. A window of five minutes cannot be waited out
 * in a test suite, and sleeping for even a second per case turns a fast suite
 * into a slow one. The limiter therefore takes its time from a
 * {@code LongSupplier}, which production wires to a monotonic clock and these
 * tests wire to a number they can move.
 */
class RateLimiterTest {

    /** A clock that only moves when a test moves it. */
    private static final class TestClock {
        private final AtomicLong millis = new AtomicLong(1_000_000L);

        long now() {
            return millis.get();
        }

        void advance(Duration by) {
            millis.addAndGet(by.toMillis());
        }
    }

    private static CareerFluxProperties limits(int login, Duration loginWindow,
                                               int discovery, Duration discoveryWindow,
                                               int maxKeys) {
        return new CareerFluxProperties(null, null, null, null, null, null,
                new CareerFluxProperties.RateLimit(true, maxKeys,
                        login, loginWindow,
                        10, Duration.ofHours(1),
                        discovery, discoveryWindow,
                        20, Duration.ofHours(1),
                        120, Duration.ofMinutes(1)),
                true);
    }

    private static CareerFluxProperties defaults() {
        return limits(10, Duration.ofMinutes(5), 30, Duration.ofMinutes(1), 50_000);
    }

    private static RateLimiter limiterOn(TestClock clock, CareerFluxProperties properties) {
        return new RateLimiter(properties, clock::now);
    }

    @Nested
    @DisplayName("the allowance")
    class Allowance {

        @Test
        @DisplayName("lets everything below the limit through")
        void belowTheLimitIsAllowed() {
            TestClock clock = new TestClock();
            RateLimiter limiter = limiterOn(clock, defaults());
            UUID account = UUID.randomUUID();

            for (int i = 0; i < 30; i++) {
                int attempt = i;
                assertThatCode(() -> limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, account))
                        .describedAs("request %d of 30", attempt + 1)
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("refuses the one request past it, and says how long to wait")
        void overTheLimitIsRefused() {
            TestClock clock = new TestClock();
            RateLimiter limiter = limiterOn(clock, defaults());
            UUID account = UUID.randomUUID();

            for (int i = 0; i < 30; i++) {
                limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, account);
            }

            assertThatThrownBy(() -> limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, account))
                    .isInstanceOf(TooManyRequestsException.class)
                    .satisfies(thrown -> {
                        TooManyRequestsException refused = (TooManyRequestsException) thrown;
                        assertThat(refused.getStatus().value()).isEqualTo(429);
                        assertThat(refused.getCode()).isEqualTo("RATE_LIMITED");
                        assertThat(refused.getRetryAfterSeconds()).isBetween(1L, 60L);
                    });
        }

        @Test
        @DisplayName("says nothing about the limit, the count or what is left")
        void theMessageDescribesNothing() {
            TestClock clock = new TestClock();
            RateLimiter limiter = limiterOn(clock, limits(1, Duration.ofMinutes(5), 30,
                    Duration.ofMinutes(1), 50_000));
            limiter.checkLogin("10.0.0.1", "someone@example.com");

            assertThatThrownBy(() -> limiter.checkLogin("10.0.0.1", "someone@example.com"))
                    .hasMessageNotContainingAny("1", "limit", "attempt", "remaining", "someone@example.com");
        }
    }

    @Nested
    @DisplayName("the window")
    class Window {

        @Test
        @DisplayName("lets the caller back in once it has passed")
        void expiryRestoresTheAllowance() {
            TestClock clock = new TestClock();
            RateLimiter limiter = limiterOn(clock, defaults());
            UUID account = UUID.randomUUID();

            for (int i = 0; i < 30; i++) {
                limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, account);
            }
            assertThatThrownBy(() -> limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, account))
                    .isInstanceOf(TooManyRequestsException.class);

            clock.advance(Duration.ofMinutes(1).plusMillis(1));

            assertThatCode(() -> limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, account))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("slides rather than resetting, so a boundary cannot be used to double the rate")
        void theWindowSlides() {
            // A fixed window would reset on a boundary and let the caller spend
            // a second full allowance the instant it ticked over. Here, having
            // spent everything at t=0, moving to just before the window ends
            // must still refuse.
            TestClock clock = new TestClock();
            RateLimiter limiter = limiterOn(clock, defaults());
            UUID account = UUID.randomUUID();

            for (int i = 0; i < 30; i++) {
                limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, account);
            }
            clock.advance(Duration.ofSeconds(59));

            assertThatThrownBy(() -> limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, account))
                    .isInstanceOf(TooManyRequestsException.class);
        }
    }

    @Nested
    @DisplayName("keys")
    class Keys {

        @Test
        @DisplayName("are independent per account, so one busy officer cannot throttle another")
        void accountsAreIndependent() {
            TestClock clock = new TestClock();
            RateLimiter limiter = limiterOn(clock, defaults());
            UUID busy = UUID.randomUUID();
            UUID other = UUID.randomUUID();

            for (int i = 0; i < 30; i++) {
                limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, busy);
            }
            assertThatThrownBy(() -> limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, busy))
                    .isInstanceOf(TooManyRequestsException.class);

            assertThatCode(() -> limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, other))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("are independent per action, so uploading does not spend discovery")
        void actionsAreIndependent() {
            TestClock clock = new TestClock();
            RateLimiter limiter = limiterOn(clock, defaults());
            UUID account = UUID.randomUUID();

            for (int i = 0; i < 10; i++) {
                limiter.check(RateLimitedAction.RESUME_UPLOAD, account);
            }
            assertThatThrownBy(() -> limiter.check(RateLimitedAction.RESUME_UPLOAD, account))
                    .isInstanceOf(TooManyRequestsException.class);

            assertThatCode(() -> limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, account))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("are independent per client address for sign-in")
        void loginAddressesAreIndependent() {
            TestClock clock = new TestClock();
            RateLimiter limiter = limiterOn(clock, defaults());

            for (int i = 0; i < 10; i++) {
                limiter.checkLogin("203.0.113.7", "priya@college.edu");
            }
            assertThatThrownBy(() -> limiter.checkLogin("203.0.113.7", "priya@college.edu"))
                    .isInstanceOf(TooManyRequestsException.class);

            // The same person on campus wifi rather than mobile data.
            assertThatCode(() -> limiter.checkLogin("203.0.113.8", "priya@college.edu"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("are independent per login identity from one address")
        void loginIdentitiesAreIndependent() {
            TestClock clock = new TestClock();
            RateLimiter limiter = limiterOn(clock, defaults());

            for (int i = 0; i < 10; i++) {
                limiter.checkLogin("203.0.113.7", "priya@college.edu");
            }
            assertThatThrownBy(() -> limiter.checkLogin("203.0.113.7", "priya@college.edu"))
                    .isInstanceOf(TooManyRequestsException.class);

            // A different student on the same shared connection. Locking out a
            // whole college because one person mistyped their password would be
            // a worse failure than the one being defended against.
            assertThatCode(() -> limiter.checkLogin("203.0.113.7", "arjun@college.edu"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("collapse spellings of one address, because sign-in itself does")
        void loginIdentityIsNormalised() {
            // The account lookup is case-insensitive and strips nothing, so all
            // of these reach one account. If each were its own key, an attacker
            // would get a fresh allowance per spelling and the limit would only
            // stop people who were not trying.
            TestClock clock = new TestClock();
            RateLimiter limiter = limiterOn(clock, defaults());

            String[] spellings = {
                    "priya@college.edu", "PRIYA@college.edu", "Priya@College.edu",
                    " priya@college.edu", "priya@college.edu  ", "\tPriya@COLLEGE.edu ",
                    "pRiYa@college.edu", "PRIYA@COLLEGE.EDU", "priya@College.EDU",
                    " PRIYA@college.EDU "
            };
            for (String spelling : spellings) {
                limiter.checkLogin("203.0.113.7", spelling);
            }

            assertThatThrownBy(() -> limiter.checkLogin("203.0.113.7", "priya@college.edu"))
                    .describedAs("ten spellings of one address must spend one allowance")
                    .isInstanceOf(TooManyRequestsException.class);
        }
    }

    @Nested
    @DisplayName("under concurrency")
    class Concurrency {

        /**
         * Releases {@code threads} callers at one key simultaneously and reports
         * how many were let through.
         *
         * <p>The entry is created first, deliberately. Going through
         * {@code computeIfAbsent} on a cold key serialises every thread behind
         * one bin lock in the map, which staggers them past the critical section
         * and hides the very race this is meant to expose: an earlier version of
         * this test passed with the lock removed for exactly that reason.
         */
        private int allowedInOneBurst(RateLimiter limiter, UUID account, int threads) throws Exception {
            limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, account);

            CyclicBarrier releaseTogether = new CyclicBarrier(threads);
            AtomicInteger allowed = new AtomicInteger(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            releaseTogether.await(10, TimeUnit.SECONDS);
                            limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, account);
                            allowed.incrementAndGet();
                        } catch (TooManyRequestsException expected) {
                            // Refused, which is the point.
                        } catch (Exception unexpected) {
                            throw new IllegalStateException(unexpected);
                        }
                    });
                }
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }
            return allowed.get();
        }

        @Test
        @DisplayName("simultaneous requests on one key cannot overspend the allowance")
        void concurrentRequestsCannotOverspend() throws Exception {
            // Repeated, because a race is a probability rather than an event.
            // One round can get lucky; thirty rounds of sixty-four threads
            // contending for thirty slots does not.
            TestClock clock = new TestClock();
            RateLimiter limiter = limiterOn(clock, defaults());
            int limit = limiter.limitFor(RateLimitedAction.CANDIDATE_DISCOVERY);

            for (int round = 1; round <= 30; round++) {
                int allowed = allowedInOneBurst(limiter, UUID.randomUUID(), 64);
                assertThat(allowed)
                        .describedAs("round %d: exactly the allowance, never more", round)
                        .isEqualTo(limit);
            }
        }

        @Test
        @DisplayName("simultaneous first requests create one window, not one per thread")
        void concurrentFirstRequestsShareOneWindow() throws Exception {
            // The cold-key path. get-then-put rather than computeIfAbsent would
            // let several threads each build a window and the last write win,
            // discarding everything the others had already counted.
            TestClock clock = new TestClock();
            RateLimiter limiter = limiterOn(clock, defaults());
            UUID account = UUID.randomUUID();

            int threads = 32;
            CyclicBarrier releaseTogether = new CyclicBarrier(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            AtomicInteger allowed = new AtomicInteger();
            try {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            releaseTogether.await(10, TimeUnit.SECONDS);
                            limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, account);
                            allowed.incrementAndGet();
                        } catch (TooManyRequestsException ignored) {
                            // counted by omission
                        } catch (Exception unexpected) {
                            throw new IllegalStateException(unexpected);
                        }
                    });
                }
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            assertThat(limiter.trackedKeys()).isEqualTo(1);
            assertThat(allowed.get()).isEqualTo(Math.min(threads, 30));
        }
    }

    @Nested
    @DisplayName("memory")
    class Memory {

        @Test
        @DisplayName("expired entries are removed")
        void expiredEntriesAreCleaned() {
            TestClock clock = new TestClock();
            RateLimiter limiter = limiterOn(clock, defaults());

            for (int i = 0; i < 500; i++) {
                limiter.checkLogin("198.51.100." + (i % 256), "attempt" + i + "@example.com");
            }
            assertThat(limiter.trackedKeys()).isEqualTo(500);

            clock.advance(Duration.ofMinutes(5).plusSeconds(1));
            int removed = limiter.sweepExpired();

            assertThat(removed).isEqualTo(500);
            assertThat(limiter.trackedKeys()).isZero();
        }

        @Test
        @DisplayName("expiry follows last activity, not last success")
        void refusalsKeepAnEntryAlive() {
            // A caller who is being refused is still a caller. If expiry keyed
            // off the last *accepted* request, an entry could be swept while its
            // owner was still hammering the endpoint -- and a swept entry is
            // recreated empty, handing back the entire allowance at once instead
            // of one slot at a time as the old timestamps age out.
            TestClock clock = new TestClock();
            RateLimiter limiter = limiterOn(clock, limits(3, Duration.ofMinutes(5), 30,
                    Duration.ofMinutes(1), 50_000));

            for (int i = 0; i < 3; i++) {
                limiter.checkLogin("198.51.100.9", "target@college.edu");
            }

            // Four minutes on: the allowance is spent and this is refused.
            clock.advance(Duration.ofMinutes(4));
            assertThatThrownBy(() -> limiter.checkLogin("198.51.100.9", "target@college.edu"))
                    .isInstanceOf(TooManyRequestsException.class);

            // Eight minutes on: the last accepted request is now older than a
            // whole window, so an expiry policy written against successes would
            // drop this entry. The refusal four minutes ago is what keeps it.
            clock.advance(Duration.ofMinutes(4));

            assertThat(limiter.sweepExpired())
                    .describedAs("an entry whose owner is still being refused must not be swept")
                    .isZero();
            assertThat(limiter.trackedKeys()).isEqualTo(1);
        }

        @Test
        @DisplayName("the map never grows past its ceiling")
        void theMapIsBounded() {
            TestClock clock = new TestClock();
            int ceiling = 400;
            RateLimiter limiter = limiterOn(clock, limits(10, Duration.ofMinutes(5), 30,
                    Duration.ofMinutes(1), ceiling));

            // Five times the ceiling in distinct keys, inside one window so
            // nothing can expire: the only thing keeping this bounded is the
            // ceiling itself.
            for (int i = 0; i < ceiling * 5; i++) {
                limiter.checkLogin("198.51.100." + (i % 256), "flood" + i + "@example.com");
            }

            assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(ceiling);
        }

        @Test
        @DisplayName("a restart starts everyone from zero, which is the accepted single-instance tradeoff")
        void restartResetsCounters() {
            // Documented rather than defended. The counters are in the heap, so
            // a deploy or a crash forgives an in-progress attacker. Accepted for
            // a pilot with rare, operator-driven restarts; it is the first thing
            // that would change if these counters ever had to survive one.
            TestClock clock = new TestClock();
            CareerFluxProperties properties = defaults();
            RateLimiter before = limiterOn(clock, properties);

            for (int i = 0; i < 10; i++) {
                before.checkLogin("203.0.113.7", "priya@college.edu");
            }
            assertThatThrownBy(() -> before.checkLogin("203.0.113.7", "priya@college.edu"))
                    .isInstanceOf(TooManyRequestsException.class);

            RateLimiter afterRestart = limiterOn(clock, properties);

            assertThat(afterRestart.trackedKeys()).isZero();
            assertThatCode(() -> afterRestart.checkLogin("203.0.113.7", "priya@college.edu"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("a limit below one is refused at startup rather than served")
        void nonsenseLimitsFailFast() {
            TestClock clock = new TestClock();
            assertThatThrownBy(() -> limiterOn(clock,
                    limits(0, Duration.ofMinutes(5), 30, Duration.ofMinutes(1), 50_000)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least one request");
        }

        @Test
        @DisplayName("a window of zero is refused at startup")
        void nonsenseWindowsFailFast() {
            TestClock clock = new TestClock();
            assertThatThrownBy(() -> limiterOn(clock,
                    limits(10, Duration.ZERO, 30, Duration.ofMinutes(1), 50_000)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("window must be positive");
        }

        @Test
        @DisplayName("turning it off turns it off, loudly")
        void disabledMeansDisabled() {
            TestClock clock = new TestClock();
            CareerFluxProperties off = new CareerFluxProperties(null, null, null, null, null, null,
                    new CareerFluxProperties.RateLimit(false, 50_000,
                            1, Duration.ofMinutes(5), 1, Duration.ofHours(1),
                            1, Duration.ofMinutes(1), 1, Duration.ofHours(1),
                            1, Duration.ofMinutes(1)),
                    true);
            RateLimiter limiter = limiterOn(clock, off);
            UUID account = UUID.randomUUID();

            for (int i = 0; i < 50; i++) {
                limiter.check(RateLimitedAction.CANDIDATE_DISCOVERY, account);
            }
            assertThat(limiter.isEnabled()).isFalse();
            assertThat(limiter.trackedKeys()).isZero();
        }

    }

    @Nested
    @DisplayName("the shipped configuration")
    class ShippedConfiguration {

        /**
         * Binds {@code application.yml} exactly as the application does, without
         * starting a context. Loosening a ceiling in configuration is precisely
         * the change that should not pass unnoticed, and asserting it against a
         * literal in a test file would only prove the test file.
         */
        private CareerFluxProperties.RateLimit shipped() throws Exception {
            var resource = new org.springframework.core.io.ClassPathResource("application.yml");
            var loaded = new org.springframework.boot.env.YamlPropertySourceLoader()
                    .load("application", resource);
            var sources = new org.springframework.core.env.MutablePropertySources();
            loaded.forEach(sources::addLast);
            // With a placeholder resolver, because `enabled` is written as
            // ${CAREERFLUX_RATE_LIMIT_ENABLED:true} and a bare Binder would try
            // to parse that string as a boolean.
            return new org.springframework.boot.context.properties.bind.Binder(
                    org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources),
                    new org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(sources))
                    .bind("careerflux.rate-limit", CareerFluxProperties.RateLimit.class)
                    .orElseThrow(() -> new AssertionError("careerflux.rate-limit is not configured"));
        }

        @Test
        @DisplayName("carries the ceilings that were reviewed, and is on")
        void shippedCeilingsAreTheReviewedOnes() throws Exception {
            CareerFluxProperties.RateLimit limits = shipped();

            assertThat(limits.enabled()).isTrue();
            assertThat(limits.maxTrackedKeys()).isEqualTo(50_000);
            assertThat(limits.loginAttempts()).isEqualTo(10);
            assertThat(limits.loginWindow()).isEqualTo(Duration.ofMinutes(5));
            assertThat(limits.resumeUploads()).isEqualTo(10);
            assertThat(limits.resumeUploadWindow()).isEqualTo(Duration.ofHours(1));
            // Deliberately above the reviewed 30; see application.yml for why.
            assertThat(limits.discoveryRequests()).isEqualTo(60);
            assertThat(limits.discoveryWindow()).isEqualTo(Duration.ofMinutes(1));
            assertThat(limits.requirementCreations()).isEqualTo(20);
            assertThat(limits.requirementWindow()).isEqualTo(Duration.ofHours(1));
            assertThat(limits.shortlistMutations()).isEqualTo(120);
            assertThat(limits.shortlistWindow()).isEqualTo(Duration.ofMinutes(1));
        }

        @Test
        @DisplayName("every action in the closed set has a ceiling, so none can be added and forgotten")
        void everyActionIsConfigured() throws Exception {
            RateLimiter limiter = new RateLimiter(
                    new CareerFluxProperties(null, null, null, null, null, null, shipped(), true));
            for (RateLimitedAction action : RateLimitedAction.values()) {
                assertThat(limiter.limitFor(action))
                        .describedAs("no ceiling configured for %s", action)
                        .isPositive();
            }
        }
    }
}
