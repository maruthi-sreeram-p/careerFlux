package com.careerflux.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every tunable knob in CareerFlux, bound once and injected as a typed object
 * instead of scattering {@code @Value} annotations across services.
 */
@ConfigurationProperties(prefix = "careerflux")
public record CareerFluxProperties(
        Security security,
        Storage storage,
        Ingestion ingestion,
        Sources sources,
        Matching matching,
        // @DefaultValue so the section exists even when nothing configures it.
        // Without it Spring binds null, and the seeder's own safety check throws
        // on startup — a guard that crashes is not a guard.
        @DefaultValue Demo demo,

        /** Request ceilings. Same reason for {@code @DefaultValue}: a limiter
         *  that binds to null is a limiter that does not run. */
        @DefaultValue RateLimit rateLimit,

        /**
         * Master switch for every scheduled worker, read through
         * {@link BackgroundWorkGate}.
         *
         * <p>Top level rather than under {@code ingestion} because it governs
         * more than ingestion: the rematch queue, stalled-run recovery and both
         * retention sweeps answer to it as well.
         *
         * <p>Defaults to true so an existing deployment behaves exactly as it
         * did before this switch existed. Set it to false for a genuine freeze —
         * that is the only setting under which nothing scheduled writes.
         */
        @DefaultValue("true") boolean backgroundWorkEnabled) {

    public record Security(Jwt jwt, Cors cors) {
    }

    /**
     * Development conveniences that write data.
     *
     * <p>Separated from the {@code demo} profile deliberately. The profile says
     * "this is a demonstration deployment"; these say "insert rows". Conflating
     * the two meant asking for demo login accounts also inserted sample job
     * postings, and it did so into a database that already held 1,947 real ones.
     */
    public record Demo(
            /**
             * Whether to register the bundled fixture source and ingest it.
             *
             * <p>Off unless asked for. Starting the application must never add
             * sample postings to a corpus somebody is relying on, and a profile
             * name is too blunt an instrument to express that.
             */
            @DefaultValue("false") boolean seedSampleJobs,

            /**
             * Whether sample jobs may be seeded into a database that already
             * holds postings from other sources.
             *
             * <p>The second safety catch: even with seeding enabled, a corpus
             * with real employer data in it is almost certainly not the one you
             * meant to fill with fixtures.
             */
            @DefaultValue("false") boolean allowSeedingIntoPopulatedCorpus) {
    }

    /**
     * How many requests one caller may make, per endpoint group.
     *
     * <p>These are ceilings on abuse, not on use. Every value is set well above
     * what the busiest legitimate placement officer does in a morning, so a
     * limit being reached is evidence of a script rather than of a person.
     *
     * <p>Counting is in memory, in this JVM. That is correct while the pilot
     * runs one backend instance and stops being correct the moment a second one
     * is added — see {@code RateLimiter} for what that costs and what would
     * replace it.
     */
    public record RateLimit(
            /** Off only for a deployment that has put a limiter in front of the app. */
            @DefaultValue("true") boolean enabled,

            /**
             * Ceiling on how many distinct callers are tracked at once.
             *
             * <p>The authenticated buckets are bounded by the number of accounts;
             * this exists for the login bucket, whose key includes an address the
             * caller chooses and is therefore attacker-controlled.
             */
            @DefaultValue("50000") int maxTrackedKeys,

            /** Sign-in attempts per client address and login identity. */
            @DefaultValue("10") int loginAttempts,
            @DefaultValue("PT5M") Duration loginWindow,

            /** Resume uploads per account. Bounds disk, not AI — AI has its own daily quota. */
            @DefaultValue("10") int resumeUploads,
            @DefaultValue("PT1H") Duration resumeUploadWindow,

            /**
             * Candidate discovery per account. The heaviest read in the product.
             *
             * <p>Above the shortlist rhythm on purpose: the browser re-runs
             * discovery each time a candidate is shortlisted, so a ceiling set
             * for reads alone would throttle a placement officer part-way
             * through a drive.
             */
            @DefaultValue("60") int discoveryRequests,
            @DefaultValue("PT1M") Duration discoveryWindow,

            /** Requirement creation per account. Human-paced by nature. */
            @DefaultValue("20") int requirementCreations,
            @DefaultValue("PT1H") Duration requirementWindow,

            /** Shortlist additions and withdrawals per account. Deliberately loose: these are clicked in bursts. */
            @DefaultValue("120") int shortlistMutations,
            @DefaultValue("PT1M") Duration shortlistWindow,

            /**
             * Sign-in attempts against one login identity, from any address.
             *
             * <p>The per-address ceiling cannot stop guesses spread across many
             * addresses, and wherever a forwarded header is believed the address
             * is the caller's to choose. This one counts the account itself. It
             * is looser than the per-address ceiling so that a person mistyping
             * their own password is not locked out, and the cost of having it is
             * that a determined attacker can make one account wait out this
             * window — accepted, over unlimited guessing against it.
             */
            @DefaultValue("20") int loginAccountAttempts,
            @DefaultValue("PT15M") Duration loginAccountWindow) {
    }

    public record Jwt(
            String secret,
            @DefaultValue("PT2H") Duration accessTokenTtl,
            @DefaultValue("P14D") Duration refreshTokenTtl) {
    }

    public record Cors(@DefaultValue("http://localhost:5173") List<String> allowedOrigins) {
    }

    public record Storage(@DefaultValue("./data/resumes") String resumeDir) {
    }

    public record Ingestion(
            @DefaultValue("in-process") String transport,
            @DefaultValue("true") boolean schedulerEnabled,
            @DefaultValue("200") int maxJobsPerRun) {
    }

    public record Sources(
            @DefaultValue("CareerFluxBot/0.1") String userAgent,
            @DefaultValue("PT20S") Duration requestTimeout,
            @DefaultValue("20") int defaultRateLimitPerMinute,
            @DefaultValue("PT6H") Duration healthCheckInterval) {
    }

    /**
     * Score thresholds from the product spec. A match below {@code hiddenBelow} is
     * never shown; the remaining bands decide how loudly the candidate is told.
     */
    public record Matching(
            @DefaultValue("70") int hiddenBelow,
            @DefaultValue("70") int digestMin,
            @DefaultValue("85") int highPriorityMin,
            @DefaultValue("95") int immediateMin,

            /**
             * How many jobs are loaded per round trip when scoring a candidate.
             * A paging size, not a limit on how many jobs are scored — that
             * distinction was the bug this became configurable to test.
             */
            @DefaultValue("400") int batchSize,

            /**
             * Ceiling on jobs scored for one candidate in a run. Exists because
             * scoring is O(candidates x jobs); exceeding it is logged loudly
             * rather than silently truncating the corpus.
             */
            @DefaultValue("20000") int maxJobsPerCandidate,

            // ---- rules-2 dimension weights -------------------------------
            // A hypothesis, tunable without a code change. They need not sum to
            // 100: the score is normalised over whichever dimensions could be
            // compared, so the ratios are what matter.
            @DefaultValue("40") int weightSkills,
            @DefaultValue("20") int weightRole,
            @DefaultValue("15") int weightExperience,
            @DefaultValue("10") int weightLocation,
            @DefaultValue("10") int weightSeniority,
            @DefaultValue("5") int weightWorkMode,

            /**
             * How far below a stated minimum counts as disqualifying rather than
             * as a gap. Two years reads correctly for campus hiring: a fresher
             * against "2+ years" is genuinely blocked, while one year short is
             * a stretch worth showing. It has no evidence behind it yet.
             */
            @DefaultValue("2.0") double experienceBlockerYears,

            /** How often the background worker looks for queued rescoring work. */
            @DefaultValue("5000") long workerIntervalMs) {
    }
}
