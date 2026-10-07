package com.careerflux.source.adapter;

import java.util.ArrayList;
import java.util.List;

import com.careerflux.source.adapter.jsonld.JobPostingJsonLd;
import com.careerflux.source.adapter.jsonld.JobPostingMapper;
import com.careerflux.source.adapter.jsonld.JobPostingNode;
import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.net.SafeUrlValidator;
import com.careerflux.source.service.SourceRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * A company's own careers page, read through the schema.org JobPosting markup it
 * publishes.
 *
 * <p>Every other adapter here speaks to one applicant tracking system's API. That
 * leaves out the employers who run their own careers stack, and the ones on a
 * system whose API needs the employer's own key — JazzHR and iCIMS among them.
 * Those pages are not silent, though: an employer who wants their jobs in a
 * search engine publishes {@code JobPosting} markup describing each one, and that
 * markup is a documented, machine-readable description the employer put there on
 * purpose.
 *
 * <p>This adapter reads exactly that, and nothing else. It fetches one page
 * through the same bounded, rate-limited, redirect-checked transport as the API
 * adapters, hands the text to {@link JobPostingJsonLd}, and maps what comes back
 * with {@link JobPostingMapper}. It runs no JavaScript, follows no link on the
 * page, submits no form and reads no second page — a careers page that builds its
 * listing in the browser is not readable here, and is honestly reported as such
 * rather than scraped some other way.
 *
 * <p><b>It claims no source it was not given.</b> {@link #supports} matches only a
 * source already assigned this adapter's key. The registry hands a configuration
 * to the first adapter that claims it, so a catch-all here would take Greenhouse
 * and Lever sources away from the adapters that read them properly, decided by
 * nothing more than bean ordering.
 */
@Component
public class JobPostingPageAdapter extends AbstractHttpJobAdapter {

    public static final String KEY = "career-page-jsonld";

    public JobPostingPageAdapter(RestClient sourceRestClient, SourceRateLimiter rateLimiter,
                                 SafeUrlValidator urlValidator, ObjectMapper objectMapper) {
        super(sourceRestClient, rateLimiter, urlValidator, objectMapper);
    }

    @Override
    public SourceMetadata getMetadata() {
        return new SourceMetadata(
                KEY,
                "Careers page with JobPosting markup",
                SourceType.COMPANY_CAREER_PAGE,
                AtsProvider.OTHER,
                AccessPolicyType.PUBLIC_PAGE,
                "https://schema.org/JobPosting",
                "Reads the schema.org JobPosting markup a careers page publishes for search engines. "
                        + "Needs no API key, so it reaches employers whose applicant tracking system "
                        + "has no public board API.");
    }

    @Override
    public boolean supports(SourceConfiguration configuration) {
        return KEY.equals(configuration.adapterKey());
    }

    @Override
    protected String endpointFor(SourceConfiguration configuration) {
        return configuration.baseUrl();
    }

    /**
     * Never called. The default health probe parses JSON from an API; this source
     * is a web page, so {@link #checkHealth} is overridden to read it as one.
     */
    @Override
    protected int countPostings(JsonNode body) {
        return 0;
    }

    @Override
    public List<RawJobPosting> fetchJobs(SourceConfiguration configuration) {
        String url = endpointFor(configuration);
        JobPostingJsonLd.Result result = JobPostingJsonLd.parse(getPage(configuration, url).html());

        List<RawJobPosting> postings = new ArrayList<>();
        JobPostingMapper.PageContext context = new JobPostingMapper.PageContext(
                // The employer is what the source says it is, never what the page
                // claims: hiringOrganization is written by whoever wrote the page.
                configuration.sourceName(), url, null);
        for (JobPostingNode node : result.postings()) {
            if (postings.size() >= configuration.maxJobs()) {
                log.info("Stopping at the configured ceiling of {} postings for {}",
                        configuration.maxJobs(), configuration.sourceName());
                break;
            }
            JobPostingMapper.map(node, context).ifPresent(postings::add);
        }

        if (postings.isEmpty()) {
            // Treated as a failure to read, not as "this employer is hiring
            // nobody". The two look identical from here, and calling it an empty
            // page would let one site redesign close every job this source has
            // ever reported. A failure leaves them standing and marks the source.
            throw new AdapterException("No readable JobPosting markup at " + url
                    + " (" + result.blocksRead() + " JSON-LD blocks read, "
                    + result.blocksRejected() + " rejected).");
        }

        log.info("Read {} postings from {} for {}: {} blocks, {} rejected, {} duplicates collapsed{}{}",
                postings.size(), url, configuration.sourceName(), result.blocksRead(),
                result.blocksRejected(), result.duplicatesCollapsed(),
                result.blockLimitReached() ? ", block limit reached" : "",
                result.postingLimitReached() ? ", posting limit reached" : "");
        return postings;
    }

    /**
     * Fetches the page and reports how many postings it declares.
     *
     * <p>There is no lighter request to make: the markup is in the page, so the
     * health probe costs what a read costs. It is still worth having, because a
     * page that stops declaring postings is exactly the failure this adapter needs
     * to notice.
     */
    @Override
    public SourceHealthResult checkHealth(SourceConfiguration configuration) {
        long startedAt = System.nanoTime();
        try {
            FetchedPage page = getPage(configuration, endpointFor(configuration));
            JobPostingJsonLd.Result result = JobPostingJsonLd.parse(page.html());
            return SourceHealthResult.healthy(page.httpStatus(), page.latencyMs(),
                    result.postings().size());
        } catch (AdapterException ex) {
            int latencyMs = (int) ((System.nanoTime() - startedAt) / 1_000_000);
            return SourceHealthResult.from(ex, latencyMs);
        } catch (RuntimeException ex) {
            return SourceHealthResult.unreachable("Unexpected failure: " + ex.getMessage());
        }
    }
}
