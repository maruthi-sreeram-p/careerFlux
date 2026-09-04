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
 * Ashby job board posting API.
 *
 * <p>Public and unauthenticated, published so that employers can render their own
 * job boards. Ashby states remote status explicitly, which is worth having: work
 * mode is otherwise one of the least reliable fields to infer from prose.
 *
 * @see <a href="https://developers.ashbyhq.com/reference/job-posting-api">Ashby job posting API</a>
 */
@Component
public class AshbyAdapter extends AbstractHttpJobAdapter {

    public static final String KEY = "ashby";
    private static final String API_ROOT = "https://api.ashbyhq.com/posting-api/job-board/";

    public AshbyAdapter(RestClient sourceRestClient, SourceRateLimiter rateLimiter,
                        SafeUrlValidator urlValidator, ObjectMapper objectMapper) {
        super(sourceRestClient, rateLimiter, urlValidator, objectMapper);
    }

    @Override
    public SourceMetadata getMetadata() {
        return new SourceMetadata(
                KEY,
                "Ashby job board",
                SourceType.ATS_PUBLIC_API,
                AtsProvider.ASHBY,
                AccessPolicyType.PUBLIC_API,
                "https://developers.ashbyhq.com/reference/job-posting-api",
                "Public job board API published by Ashby for employer careers pages.");
    }

    @Override
    public boolean supports(SourceConfiguration configuration) {
        if (KEY.equals(configuration.adapterKey())) {
            return true;
        }
        String url = configuration.baseUrl() == null ? "" : configuration.baseUrl().toLowerCase();
        return url.contains("ashbyhq.com");
    }

    @Override
    protected String endpointFor(SourceConfiguration configuration) {
        return API_ROOT + configuration.externalIdentifier() + "?includeCompensation=true";
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
            throw new AdapterException("Ashby response did not contain a jobs array.");
        }

        List<RawJobPosting> results = new ArrayList<>();
        for (JsonNode job : jobs) {
            if (results.size() >= configuration.maxJobs()) {
                break;
            }
            String externalId = text(job, "id");
            if (externalId == null) {
                continue;
            }
            // Unlisted postings are not shown on the employer's own board either.
            JsonNode listed = job.get("isListed");
            if (listed != null && listed.isBoolean() && !listed.asBoolean()) {
                continue;
            }
            results.add(RawJobPosting.builder(externalId)
                    .title(text(job, "title"))
                    .companyName(configuration.sourceName())
                    .locationText(text(job, "location"))
                    .descriptionHtml(text(job, "descriptionHtml"))
                    .employmentTypeText(text(job, "employmentType"))
                    .workModeText(workMode(job))
                    .departmentText(text(job, "department"))
                    .salaryText(nestedText(job, "compensation", "compensationTierSummary"))
                    .applyUrl(firstNonNull(text(job, "applyUrl"), text(job, "jobUrl")))
                    .sourceUrl(text(job, "jobUrl"))
                    .postedAt(parseInstant(text(job, "publishedAt")))
                    .updatedAt(parseInstant(text(job, "updatedAt")))
                    .rawPayload(job.toString())
                    .build());
        }
        return results;
    }

    private String workMode(JsonNode job) {
        JsonNode remote = job.get("isRemote");
        if (remote != null && remote.isBoolean()) {
            return remote.asBoolean() ? "Remote" : "Onsite";
        }
        return null;
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
