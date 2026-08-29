package com.careerflux.source.adapter;

import java.util.ArrayList;
import java.util.List;

import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.service.SourceRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Lever Postings API.
 *
 * <p>Public, unauthenticated, and documented for exactly this use: it is the
 * endpoint Lever customers point their own careers pages at.
 *
 * @see <a href="https://github.com/lever/postings-api">Lever postings API</a>
 */
@Component
public class LeverAdapter extends AbstractHttpJobAdapter {

    public static final String KEY = "lever";
    private static final String API_ROOT = "https://api.lever.co/v0/postings/";

    public LeverAdapter(RestClient sourceRestClient, SourceRateLimiter rateLimiter,
                        ObjectMapper objectMapper) {
        super(sourceRestClient, rateLimiter, objectMapper);
    }

    @Override
    public SourceMetadata getMetadata() {
        return new SourceMetadata(
                KEY,
                "Lever postings",
                SourceType.ATS_PUBLIC_API,
                AtsProvider.LEVER,
                AccessPolicyType.PUBLIC_API,
                "https://github.com/lever/postings-api",
                "Public postings API that Lever publishes for careers-page syndication.");
    }

    @Override
    public boolean supports(SourceConfiguration configuration) {
        if (KEY.equals(configuration.adapterKey())) {
            return true;
        }
        String url = configuration.baseUrl() == null ? "" : configuration.baseUrl().toLowerCase();
        return url.contains("lever.co");
    }

    @Override
    protected String endpointFor(SourceConfiguration configuration) {
        return API_ROOT + configuration.externalIdentifier() + "?mode=json";
    }

    @Override
    protected int countPostings(JsonNode body) {
        return body != null && body.isArray() ? body.size() : 0;
    }

    @Override
    public List<RawJobPosting> fetchJobs(SourceConfiguration configuration) {
        FetchedJson fetched = getJson(configuration, endpointFor(configuration));
        JsonNode postings = fetched.body();
        if (!postings.isArray()) {
            throw new AdapterException("Lever response was not an array of postings.");
        }

        List<RawJobPosting> results = new ArrayList<>();
        for (JsonNode posting : postings) {
            if (results.size() >= configuration.maxJobs()) {
                break;
            }
            String externalId = text(posting, "id");
            if (externalId == null) {
                continue;
            }
            results.add(RawJobPosting.builder(externalId)
                    .title(text(posting, "text"))
                    .companyName(configuration.sourceName())
                    .locationText(nestedText(posting, "categories", "location"))
                    .descriptionHtml(fullDescription(posting))
                    .employmentTypeText(nestedText(posting, "categories", "commitment"))
                    .workModeText(text(posting, "workplaceType"))
                    .departmentText(nestedText(posting, "categories", "department"))
                    .salaryText(nestedText(posting, "salaryRange", "currency"))
                    .applyUrl(firstNonNull(text(posting, "applyUrl"), text(posting, "hostedUrl")))
                    .sourceUrl(text(posting, "hostedUrl"))
                    .postedAt(parseEpochMillis(posting, "createdAt"))
                    .updatedAt(parseEpochMillis(posting, "updatedAt"))
                    .rawPayload(posting.toString())
                    .build());
        }
        return results;
    }

    /**
     * Lever splits a posting across a summary and a set of titled lists
     * (requirements, benefits and so on). Concatenating them gives the pipeline
     * the same complete text a human reader would see on the posting page.
     */
    private String fullDescription(JsonNode posting) {
        StringBuilder builder = new StringBuilder();
        String description = text(posting, "description");
        if (description != null) {
            builder.append(description);
        }
        JsonNode lists = posting.get("lists");
        if (lists != null && lists.isArray()) {
            for (JsonNode list : lists) {
                String heading = text(list, "text");
                String content = text(list, "content");
                if (heading != null) {
                    builder.append("<h3>").append(heading).append("</h3>");
                }
                if (content != null) {
                    builder.append("<ul>").append(content).append("</ul>");
                }
            }
        }
        String additional = text(posting, "additional");
        if (additional != null) {
            builder.append(additional);
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
