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
 * Workable's public careers widget.
 *
 * <p>The endpoint behind an employer's embedded careers page, keyed by their
 * account subdomain. Unauthenticated, and it returns the whole board in one
 * response — which is why there is no paging here to get wrong.
 *
 * <p>{@code details=true} is what makes the response worth reading: without it
 * the payload carries titles and little else, and every job would arrive with no
 * description for the enricher to find skills in.
 */
@Component
public class WorkableAdapter extends AbstractHttpJobAdapter {

    public static final String KEY = "workable";

    private static final String API_ROOT = "https://apply.workable.com/api/v1/widget/accounts/";

    public WorkableAdapter(RestClient sourceRestClient, SourceRateLimiter rateLimiter,
                           SafeUrlValidator urlValidator, ObjectMapper objectMapper) {
        super(sourceRestClient, rateLimiter, urlValidator, objectMapper);
    }

    @Override
    public SourceMetadata getMetadata() {
        return new SourceMetadata(
                KEY,
                "Workable careers widget",
                SourceType.ATS_PUBLIC_API,
                AtsProvider.WORKABLE,
                AccessPolicyType.PUBLIC_API,
                "https://help.workable.com/hc/en-us/articles/360000914213",
                "Public, unauthenticated careers widget endpoint published by Workable.");
    }

    @Override
    public boolean supports(SourceConfiguration configuration) {
        if (KEY.equals(configuration.adapterKey())) {
            return true;
        }
        String url = configuration.baseUrl() == null ? "" : configuration.baseUrl().toLowerCase();
        return url.contains("workable.com");
    }

    @Override
    protected String endpointFor(SourceConfiguration configuration) {
        return API_ROOT + configuration.externalIdentifier() + "?details=true";
    }

    @Override
    protected int countPostings(JsonNode body) {
        JsonNode jobs = body.get("jobs");
        return jobs == null ? 0 : jobs.size();
    }

    @Override
    public List<RawJobPosting> fetchJobs(SourceConfiguration configuration) {
        FetchedJson fetched = getJson(configuration, endpointFor(configuration));
        JsonNode body = fetched.body();
        JsonNode jobs = body.get("jobs");
        if (jobs == null || !jobs.isArray()) {
            throw new AdapterException("Workable response did not contain a jobs array.");
        }
        // The employer's own name, from the same document, rather than the
        // subdomain — "blueground" is an account handle, not a company.
        String accountName = text(body, "name");

        List<RawJobPosting> postings = new ArrayList<>();
        for (JsonNode job : jobs) {
            if (postings.size() >= configuration.maxJobs()) {
                log.info("Stopping at the configured ceiling of {} postings for {}",
                        configuration.maxJobs(), configuration.sourceName());
                break;
            }
            String externalId = text(job, "shortcode");
            if (externalId == null) {
                continue;
            }
            postings.add(RawJobPosting.builder(externalId)
                    .requisitionId(text(job, "code"))
                    .title(text(job, "title"))
                    .companyName(accountName == null ? configuration.sourceName() : accountName)
                    .locationText(locationOf(job))
                    .descriptionHtml(text(job, "description"))
                    .employmentTypeText(text(job, "employment_type"))
                    .departmentText(text(job, "department"))
                    // telecommuting is the provider's own remote flag. Passed as
                    // text for the normalizer to interpret, so the mapping from
                    // "remote" to a work mode stays in one place.
                    .workModeText(remoteFlag(job))
                    .applyUrl(text(job, "url"))
                    .sourceUrl(text(job, "shortlink"))
                    .postedAt(parseInstant(text(job, "published_on")))
                    .updatedAt(parseInstant(text(job, "created_at")))
                    .rawPayload(job.toString())
                    .build());
        }
        return postings;
    }

    private static String remoteFlag(JsonNode job) {
        JsonNode telecommuting = job.get("telecommuting");
        return telecommuting != null && telecommuting.asBoolean(false) ? "Remote" : null;
    }

    /**
     * City, state and country as the provider sends them.
     *
     * <p>Workable leaves city and state as empty strings rather than omitting
     * them for a fully remote role, so blanks are skipped instead of producing
     * ", , United States".
     */
    private static String locationOf(JsonNode job) {
        StringBuilder parts = new StringBuilder();
        for (String field : new String[] {"city", "state", "country"}) {
            String value = text(job, field);
            if (value != null && !value.isBlank()) {
                if (!parts.isEmpty()) {
                    parts.append(", ");
                }
                parts.append(value);
            }
        }
        return parts.isEmpty() ? null : parts.toString();
    }
}
