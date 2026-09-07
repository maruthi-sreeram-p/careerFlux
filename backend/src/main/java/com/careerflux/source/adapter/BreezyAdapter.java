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
 * Breezy HR's public board feed.
 *
 * <p>Each employer publishes at {@code {company}.breezy.hr/json}. The response is
 * a bare array rather than an envelope, which is the one thing that makes this
 * adapter differ in shape from the others.
 *
 * <p>Like SmartRecruiters, the list carries no advert body — Breezy keeps that on
 * the posting page — so descriptions are left missing rather than fetched one
 * request at a time.
 */
@Component
public class BreezyAdapter extends AbstractHttpJobAdapter {

    public static final String KEY = "breezy";

    public BreezyAdapter(RestClient sourceRestClient, SourceRateLimiter rateLimiter,
                         SafeUrlValidator urlValidator, ObjectMapper objectMapper) {
        super(sourceRestClient, rateLimiter, urlValidator, objectMapper);
    }

    @Override
    public SourceMetadata getMetadata() {
        return new SourceMetadata(
                KEY,
                "Breezy HR job board",
                SourceType.ATS_PUBLIC_API,
                AtsProvider.BREEZY,
                AccessPolicyType.PUBLIC_API,
                "https://breezy.hr",
                "Public, unauthenticated board feed published by Breezy HR at {company}.breezy.hr/json.");
    }

    @Override
    public boolean supports(SourceConfiguration configuration) {
        if (KEY.equals(configuration.adapterKey())) {
            return true;
        }
        String url = configuration.baseUrl() == null ? "" : configuration.baseUrl().toLowerCase();
        return url.contains("breezy.hr");
    }

    @Override
    protected String endpointFor(SourceConfiguration configuration) {
        return "https://" + configuration.externalIdentifier() + ".breezy.hr/json";
    }

    @Override
    protected int countPostings(JsonNode body) {
        return body != null && body.isArray() ? body.size() : 0;
    }

    @Override
    public List<RawJobPosting> fetchJobs(SourceConfiguration configuration) {
        FetchedJson fetched = getJson(configuration, endpointFor(configuration));
        JsonNode jobs = fetched.body();
        if (jobs == null || !jobs.isArray()) {
            throw new AdapterException("Breezy response was not a job array.");
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
                    // friendly_id is the slug in the advert URL, which is the
                    // closest thing Breezy has to a requisition number.
                    .requisitionId(text(job, "friendly_id"))
                    .title(text(job, "name"))
                    .companyName(nestedText(job, "company", "name"))
                    .locationText(locationOf(job))
                    .employmentTypeText(nestedText(job, "type", "name"))
                    .departmentText(text(job, "department"))
                    .salaryText(text(job, "salary"))
                    .applyUrl(text(job, "url"))
                    .sourceUrl(text(job, "url"))
                    .postedAt(parseInstant(text(job, "published_date")))
                    .rawPayload(job.toString())
                    .build());
        }
        return postings;
    }

    /**
     * Breezy nests each location part as its own object with a {@code name}.
     *
     * <p>City is sometimes a plain string and sometimes an object, so it is read
     * both ways rather than assumed.
     */
    private static String locationOf(JsonNode job) {
        JsonNode location = job.get("location");
        if (location == null || location.isNull()) {
            return null;
        }
        StringBuilder parts = new StringBuilder();
        appendIfPresent(parts, flatten(location.get("city")));
        appendIfPresent(parts, flatten(location.get("state")));
        appendIfPresent(parts, flatten(location.get("country")));
        return parts.isEmpty() ? null : parts.toString();
    }

    private static String flatten(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isObject() ? text(node, "name") : node.asText(null);
    }

    private static void appendIfPresent(StringBuilder parts, String value) {
        if (value != null && !value.isBlank()) {
            if (!parts.isEmpty()) {
                parts.append(", ");
            }
            parts.append(value);
        }
    }
}
