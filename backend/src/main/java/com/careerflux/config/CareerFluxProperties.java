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
        @DefaultValue Demo demo) {

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
