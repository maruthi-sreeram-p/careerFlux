package com.careerflux.source.adapter;

import java.util.List;

/**
 * The contract every job source implements.
 *
 * <p>Adapters are the only code in CareerFlux that talks to the outside world for
 * job data. Each one knows exactly one access protocol, returns raw postings in a
 * common shape, and reports its own health. What an adapter deliberately does not
 * do is decide whether it is <em>allowed</em> to run: that is the policy engine's
 * job, and the ingestion service checks it before an adapter is ever invoked.
 *
 * <p>Implementations must be side-effect free with respect to the database and
 * must never follow redirects into authenticated areas, submit forms, or attempt
 * to work around rate limiting.
 */
public interface JobSourceAdapter {

    /** Static description of what this adapter handles. */
    SourceMetadata getMetadata();

    /**
     * Whether this adapter can serve the given configuration. Used at
     * classification time to pick an adapter for a newly discovered source.
     */
    boolean supports(SourceConfiguration configuration);

    /**
     * Fetches the current postings.
     *
     * @throws AdapterException when the source is unreachable or returns something
     *         this adapter cannot read. Callers translate that into a health
     *         observation rather than a user-visible error.
     */
    List<RawJobPosting> fetchJobs(SourceConfiguration configuration);

    /**
     * Cheap liveness probe. Must not fetch the full posting list when a lighter
     * request will do, and must never throw: an unreachable source is a health
     * result, not an exception.
     */
    SourceHealthResult checkHealth(SourceConfiguration configuration);

    /**
     * The exact URL this adapter would request. Policy checks evaluate robots.txt
     * against this rather than against the source's display URL, because those are
     * frequently different paths with different rules.
     */
    default String probeUrl(SourceConfiguration configuration) {
        return configuration.baseUrl();
    }
}
