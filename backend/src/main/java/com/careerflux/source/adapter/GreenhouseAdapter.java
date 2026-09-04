package com.careerflux.source.adapter;

import java.util.ArrayList;
import java.util.List;

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
 * Greenhouse Job Board API.
 *
 * <p>A documented, unauthenticated, read-only endpoint that Greenhouse publishes
 * specifically so employers' postings can be syndicated. Reading it is exactly
 * what it is for, which is why it is one of the first adapters CareerFlux ships.
 *
 * @see <a href="https://developers.greenhouse.io/job-board.html">Greenhouse Job Board API</a>
 */
@Component
public class GreenhouseAdapter extends AbstractHttpJobAdapter {

    public static final String KEY = "greenhouse";
    private static final String API_ROOT = "https://boards-api.greenhouse.io/v1/boards/";

    public GreenhouseAdapter(RestClient sourceRestClient, SourceRateLimiter rateLimiter,
                             SafeUrlValidator urlValidator, ObjectMapper objectMapper) {
        super(sourceRestClient, rateLimiter, urlValidator, objectMapper);
    }

    @Override
    public SourceMetadata getMetadata() {
        return new SourceMetadata(
                KEY,
                "Greenhouse job board",
                SourceType.ATS_PUBLIC_API,
                AtsProvider.GREENHOUSE,
                AccessPolicyType.PUBLIC_API,
                "https://developers.greenhouse.io/job-board.html",
                "Public, unauthenticated job board API published by Greenhouse for syndication.");
    }

    @Override
    public boolean supports(SourceConfiguration configuration) {
        if (KEY.equals(configuration.adapterKey())) {
            return true;
        }
        String url = configuration.baseUrl() == null ? "" : configuration.baseUrl().toLowerCase();
        return url.contains("greenhouse.io") || url.contains("boards.greenhouse");
    }

    @Override
    protected String endpointFor(SourceConfiguration configuration) {
        return API_ROOT + configuration.externalIdentifier() + "/jobs?content=true";
    }

    @Override
    protected int countPostings(JsonNode body) {
        JsonNode jobs = body.get("jobs");
        return jobs == null ? 0 : jobs.size();
    }

    @Override
    public List<RawJobPosting> fetchJobs(SourceConfiguration configuration) {
        FetchedJson fetched = getJson(configuration, endpointFor(configuration));
        JsonNode jobs = fetched.body().get("jobs");
        if (jobs == null || !jobs.isArray()) {
            throw new AdapterException("Greenhouse response did not contain a jobs array.");
        }

        List<RawJobPosting> postings = new ArrayList<>();
        for (JsonNode job : jobs) {
            if (postings.size() >= configuration.maxJobs()) {
                log.info("Stopping at the configured ceiling of {} postings for {}",
                        configuration.maxJobs(), configuration.sourceName());
                break;
            }
            String externalId = text(job, "id");
            if (externalId == null) {
                continue;
            }
            postings.add(RawJobPosting.builder(externalId)
                    .requisitionId(text(job, "requisition_id"))
                    .title(text(job, "title"))
                    .companyName(companyNameOf(job, configuration))
                    .locationText(nestedText(job, "location", "name"))
                    // Greenhouse returns the description as HTML-escaped markup.
                    .descriptionHtml(unescape(text(job, "content")))
                    .departmentText(firstNamed(job.get("departments")))
                    .applyUrl(text(job, "absolute_url"))
                    .sourceUrl(text(job, "absolute_url"))
                    .postedAt(parseInstant(text(job, "first_published")))
                    .updatedAt(parseInstant(text(job, "updated_at")))
                    .rawPayload(job.toString())
                    .build());
        }
        return postings;
    }

    private String companyNameOf(JsonNode job, SourceConfiguration configuration) {
        String fromPayload = nestedText(job, "company", "name");
        return fromPayload != null ? fromPayload : configuration.sourceName();
    }

    private String firstNamed(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return null;
        }
        return text(array.get(0), "name");
    }

    /** Greenhouse escapes its HTML content; undo that before the pipeline strips tags. */
    private String unescape(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }
}
