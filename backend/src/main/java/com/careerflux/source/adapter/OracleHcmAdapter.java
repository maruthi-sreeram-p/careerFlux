package com.careerflux.source.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.careerflux.common.TextUtils;
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
 * Oracle Recruiting Cloud's candidate-experience API.
 *
 * <p>The endpoint an Oracle-hosted careers page calls for itself. Public and
 * unauthenticated, but it needs two things the other adapters do not.
 *
 * <p><b>A tenant host and a site number.</b> Every employer is on their own
 * Fusion host, so the base URL carries the host and the external identifier
 * carries the site — {@code CX_1} and similar. Neither can be inferred from the
 * other, which is why both are configured.
 *
 * <p><b>An explicit expand.</b> Without {@code expand=requisitionList} the
 * response still returns HTTP 200 and a well-formed envelope with a job count in
 * it — and no jobs. That reads as an empty board rather than a mistake, so it is
 * requested explicitly and the absence of the list is treated as a fault.
 */
@Component
public class OracleHcmAdapter extends AbstractHttpJobAdapter {

    public static final String KEY = "oracle-hcm";

    /** The provider's own per-page ceiling; the pipeline's ceiling is applied again below. */
    private static final int PAGE_LIMIT = 100;

    public OracleHcmAdapter(RestClient sourceRestClient, SourceRateLimiter rateLimiter,
                            SafeUrlValidator urlValidator, ObjectMapper objectMapper) {
        super(sourceRestClient, rateLimiter, urlValidator, objectMapper);
    }

    @Override
    public SourceMetadata getMetadata() {
        return new SourceMetadata(
                KEY,
                "Oracle Recruiting Cloud",
                SourceType.ATS_PUBLIC_API,
                AtsProvider.ORACLE_HCM,
                AccessPolicyType.PUBLIC_API,
                "https://docs.oracle.com/en/cloud/saas/talent-management/",
                "Public candidate-experience REST endpoint served by an employer's own "
                        + "Oracle Fusion host. No credentials; the site number is public.");
    }

    @Override
    public boolean supports(SourceConfiguration configuration) {
        if (KEY.equals(configuration.adapterKey())) {
            return true;
        }
        String url = configuration.baseUrl() == null ? "" : configuration.baseUrl().toLowerCase(Locale.ROOT);
        return url.contains("oraclecloud.com") || url.contains("fa.ocs.oraclecloud.com");
    }

    @Override
    protected String endpointFor(SourceConfiguration configuration) {
        return endpointFor(configuration, 0);
    }

    private String endpointFor(SourceConfiguration configuration, int offset) {
        String host = hostOf(configuration);
        int limit = Math.min(configuration.maxJobs(), PAGE_LIMIT);
        return host + "/hcmRestApi/resources/latest/recruitingCEJobRequisitions"
                + "?onlyData=true&expand=requisitionList"
                + "&finder=findReqs;siteNumber=" + configuration.externalIdentifier()
                + ",limit=" + limit + ",offset=" + offset;
    }

    @Override
    protected int countPostings(JsonNode body) {
        return requisitions(body).size();
    }

    @Override
    public List<RawJobPosting> fetchJobs(SourceConfiguration configuration) {
        List<RawJobPosting> postings = new ArrayList<>();
        int offset = 0;

        // Paged, because a Fusion tenant routinely carries thousands of
        // requisitions and the provider caps a page at a hundred. Bounded by the
        // run's own ceiling and by the page coming back short — never by trusting
        // a total the server reports.
        while (postings.size() < configuration.maxJobs()) {
            FetchedJson fetched = getJson(configuration, endpointFor(configuration, offset));
            List<JsonNode> page = requisitions(fetched.body());
            if (page.isEmpty()) {
                break;
            }
            for (JsonNode job : page) {
                if (postings.size() >= configuration.maxJobs()) {
                    log.info("Stopping at the configured ceiling of {} postings for {}",
                            configuration.maxJobs(), configuration.sourceName());
                    return postings;
                }
                RawJobPosting posting = toPosting(job, configuration);
                if (posting != null) {
                    postings.add(posting);
                }
            }
            if (page.size() < Math.min(configuration.maxJobs(), PAGE_LIMIT)) {
                break;
            }
            offset += page.size();
        }
        return postings;
    }

    private RawJobPosting toPosting(JsonNode job, SourceConfiguration configuration) {
        String externalId = text(job, "Id");
        if (externalId == null) {
            return null;
        }
        return RawJobPosting.builder(externalId)
                .title(text(job, "Title"))
                .companyName(configuration.sourceName())
                .locationText(text(job, "PrimaryLocation"))
                // The short description is the only body the list carries. It is
                // plain text, not markup, so it is passed as-is; the normalizer
                // strips tags it does not find rather than inventing any.
                .descriptionHtml(text(job, "ShortDescriptionStr"))
                .employmentTypeText(text(job, "JobType"))
                .workModeText(text(job, "WorkplaceType"))
                .departmentText(text(job, "JobFamily"))
                .applyUrl(advertUrl(configuration, externalId))
                .sourceUrl(advertUrl(configuration, externalId))
                .postedAt(parseInstant(text(job, "PostedDate")))
                .rawPayload(job.toString())
                .build();
    }

    /** The candidate-facing advert on the employer's own careers site. */
    private static String advertUrl(SourceConfiguration configuration, String externalId) {
        return hostOf(configuration) + "/hcmUI/CandidateExperience/en/sites/"
                + configuration.externalIdentifier() + "/job/" + externalId;
    }

    /**
     * The tenant host, without a trailing slash and without a path.
     *
     * <p>Taken from the configured base URL rather than assembled from a tenant
     * name: Fusion hosts vary by pod and region ({@code us2}, {@code em2}, and
     * so on) and cannot be derived. SafeUrlValidator still vets the result.
     */
    private static String hostOf(SourceConfiguration configuration) {
        String base = configuration.baseUrl();
        if (!TextUtils.hasText(base)) {
            throw new AdapterException("An Oracle HCM source needs its Fusion host as the base URL.");
        }
        String trimmed = base.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * The requisitions, which sit one level deeper than every other provider's.
     *
     * <p>The envelope is a search result: {@code items} holds the search itself,
     * and the postings hang off it. An envelope with no list is the
     * missing-expand case described on the class, and is reported rather than
     * quietly returning nothing.
     */
    private static List<JsonNode> requisitions(JsonNode body) {
        List<JsonNode> found = new ArrayList<>();
        if (body == null) {
            return found;
        }
        JsonNode items = body.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) {
            return found;
        }
        boolean sawList = false;
        for (JsonNode search : items) {
            JsonNode list = search.get("requisitionList");
            if (list != null && list.isArray()) {
                sawList = true;
                list.forEach(found::add);
            }
        }
        if (!sawList) {
            throw new AdapterException(
                    "Oracle HCM returned a search envelope with no requisitionList. "
                            + "The endpoint needs expand=requisitionList.");
        }
        return found;
    }
}
