package com.careerflux.source.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.SourceHealthStatus;
import com.careerflux.source.domain.SourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Reads postings from a bundled JSON fixture instead of the network.
 *
 * <p>This exists so the ingestion pipeline, matching and the whole frontend can
 * be exercised on a laptop with no internet access and without putting load on
 * anybody's servers. Sources served by this adapter are typed
 * {@link SourceType#LOCAL_FIXTURE}, and the UI labels them as sample data
 * everywhere they appear — they are never dressed up as a real employer feed.
 */
@Component
public class LocalFixtureAdapter implements JobSourceAdapter {

    public static final String KEY = "local-fixture";
    private static final Logger log = LoggerFactory.getLogger(LocalFixtureAdapter.class);
    private static final String FIXTURE_ROOT = "fixtures/";

    private final ObjectMapper objectMapper;

    public LocalFixtureAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SourceMetadata getMetadata() {
        return new SourceMetadata(
                KEY,
                "Local sample data",
                SourceType.LOCAL_FIXTURE,
                AtsProvider.NONE,
                AccessPolicyType.PUBLIC_FEED,
                null,
                "Bundled sample postings for local development. Not a real employer feed.");
    }

    @Override
    public boolean supports(SourceConfiguration configuration) {
        return KEY.equals(configuration.adapterKey());
    }

    @Override
    public List<RawJobPosting> fetchJobs(SourceConfiguration configuration) {
        JsonNode root = readFixture(configuration.externalIdentifier());
        JsonNode jobs = root.get("jobs");
        if (jobs == null || !jobs.isArray()) {
            throw new AdapterException("Fixture " + configuration.externalIdentifier()
                    + " does not contain a jobs array.");
        }

        List<RawJobPosting> results = new ArrayList<>();
        for (JsonNode job : jobs) {
            if (results.size() >= configuration.maxJobs()) {
                break;
            }
            String externalId = job.path("externalId").asText(null);
            if (externalId == null) {
                continue;
            }
            results.add(RawJobPosting.builder(externalId)
                    .requisitionId(optional(job, "requisitionId"))
                    .title(optional(job, "title"))
                    .companyName(optional(job, "companyName"))
                    .locationText(optional(job, "location"))
                    .descriptionHtml(optional(job, "description"))
                    .employmentTypeText(optional(job, "employmentType"))
                    .workModeText(optional(job, "workMode"))
                    .departmentText(optional(job, "department"))
                    .salaryText(optional(job, "salary"))
                    .applyUrl(optional(job, "applyUrl"))
                    .sourceUrl(optional(job, "sourceUrl"))
                    .postedAt(AbstractHttpJobAdapter.parseInstant(optional(job, "postedAt")))
                    .rawPayload(job.toString())
                    .build());
        }
        return results;
    }

    @Override
    public SourceHealthResult checkHealth(SourceConfiguration configuration) {
        try {
            JsonNode root = readFixture(configuration.externalIdentifier());
            JsonNode jobs = root.get("jobs");
            int count = jobs == null ? 0 : jobs.size();
            return new SourceHealthResult(SourceHealthStatus.HEALTHY, 200, 0, count,
                    "Local fixture read successfully.");
        } catch (AdapterException ex) {
            return SourceHealthResult.unreachable(ex.getMessage());
        }
    }

    private JsonNode readFixture(String name) {
        String safeName = name == null ? "" : name.replaceAll("[^a-zA-Z0-9._-]", "");
        if (safeName.isEmpty()) {
            throw new AdapterException("No fixture name was configured for this source.");
        }
        String path = FIXTURE_ROOT + safeName + ".json";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new AdapterException("Fixture " + path + " is not bundled with this build.");
        }
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            log.warn("Could not read fixture {}: {}", path, ex.getMessage());
            throw new AdapterException("Fixture " + path + " could not be read.", null, ex);
        }
    }

    private static String optional(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text;
    }
}
