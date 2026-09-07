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
 * SmartRecruiters' public posting API.
 *
 * <p>One page of postings per request, keyed by the company identifier the
 * employer chose — "BoschGroup", not a numeric id. The endpoint is
 * unauthenticated and documented for syndication.
 *
 * <p><b>Descriptions are deliberately absent.</b> The list endpoint returns
 * everything except the advert body; that needs a second request per posting,
 * and two hundred extra round trips per sync is precisely the shape this
 * pipeline avoids elsewhere. A job with no description is honest and still
 * matches on title, location and skills inferred from the title; two hundred
 * requests to fill it in is not a trade worth making. Nothing here invents one.
 */
@Component
public class SmartRecruitersAdapter extends AbstractHttpJobAdapter {

    public static final String KEY = "smartrecruiters";

    private static final String API_ROOT = "https://api.smartrecruiters.com/v1/companies/";
    /** Where a candidate actually applies; the API's own `ref` is another API URL. */
    private static final String PUBLIC_BOARD = "https://jobs.smartrecruiters.com/";

    public SmartRecruitersAdapter(RestClient sourceRestClient, SourceRateLimiter rateLimiter,
                                  SafeUrlValidator urlValidator, ObjectMapper objectMapper) {
        super(sourceRestClient, rateLimiter, urlValidator, objectMapper);
    }

    @Override
    public SourceMetadata getMetadata() {
        return new SourceMetadata(
                KEY,
                "SmartRecruiters job board",
                SourceType.ATS_PUBLIC_API,
                AtsProvider.SMARTRECRUITERS,
                AccessPolicyType.PUBLIC_API,
                "https://developers.smartrecruiters.com/reference/postings",
                "Public, unauthenticated posting API published by SmartRecruiters for syndication.");
    }

    @Override
    public boolean supports(SourceConfiguration configuration) {
        if (KEY.equals(configuration.adapterKey())) {
            return true;
        }
        String url = configuration.baseUrl() == null ? "" : configuration.baseUrl().toLowerCase();
        return url.contains("smartrecruiters.com");
    }

    @Override
    protected String endpointFor(SourceConfiguration configuration) {
        // limit is the provider's own ceiling per page; the pipeline's ceiling is
        // applied again below, because the two are set independently.
        return API_ROOT + configuration.externalIdentifier() + "/postings?limit="
                + Math.min(configuration.maxJobs(), 100);
    }

    @Override
    protected int countPostings(JsonNode body) {
        JsonNode content = body.get("content");
        return content == null ? 0 : content.size();
    }

    @Override
    public List<RawJobPosting> fetchJobs(SourceConfiguration configuration) {
        FetchedJson fetched = getJson(configuration, endpointFor(configuration));
        JsonNode content = fetched.body().get("content");
        if (content == null || !content.isArray()) {
            throw new AdapterException("SmartRecruiters response did not contain a content array.");
        }

        List<RawJobPosting> postings = new ArrayList<>();
        for (JsonNode job : content) {
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
                    .requisitionId(text(job, "refNumber"))
                    .title(text(job, "name"))
                    .companyName(nestedText(job, "company", "name"))
                    .locationText(locationOf(job))
                    .employmentTypeText(nestedText(job, "typeOfEmployment", "label"))
                    .departmentText(nestedText(job, "department", "label"))
                    .applyUrl(applyUrl(job, configuration))
                    .sourceUrl(applyUrl(job, configuration))
                    .postedAt(parseInstant(text(job, "releasedDate")))
                    .rawPayload(job.toString())
                    .build());
        }
        return postings;
    }

    /** "Bengaluru, KA, in" from the parts the provider actually sends. */
    private static String locationOf(JsonNode job) {
        JsonNode location = job.get("location");
        if (location == null || location.isNull()) {
            return null;
        }
        StringBuilder parts = new StringBuilder();
        appendIfPresent(parts, text(location, "city"));
        appendIfPresent(parts, text(location, "region"));
        appendIfPresent(parts, text(location, "country"));
        return parts.isEmpty() ? null : parts.toString();
    }

    private static void appendIfPresent(StringBuilder parts, String value) {
        if (value != null && !value.isBlank()) {
            if (!parts.isEmpty()) {
                parts.append(", ");
            }
            parts.append(value);
        }
    }

    /**
     * The candidate-facing advert.
     *
     * <p>Built from the company identifier the posting itself carries rather than
     * the one configured, so a company that has been renamed still produces a
     * link that resolves. Falls back to the configured identifier when the
     * payload omits it.
     */
    private static String applyUrl(JsonNode job, SourceConfiguration configuration) {
        String identifier = nestedText(job, "company", "identifier");
        String company = identifier == null ? configuration.externalIdentifier() : identifier;
        String id = text(job, "id");
        return company == null || id == null ? null : PUBLIC_BOARD + company + "/" + id;
    }
}
