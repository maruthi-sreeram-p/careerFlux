package com.careerflux.source.adapter;

import java.util.UUID;

import com.careerflux.source.domain.JobSource;

/**
 * Everything an adapter needs to do its job, and nothing else. Adapters receive
 * this rather than the JPA entity so they cannot mutate persistent state or
 * wander into lazily-loaded associations.
 */
public record SourceConfiguration(
        UUID sourceId,
        String sourceName,
        String baseUrl,
        String externalIdentifier,
        String adapterKey,
        int rateLimitPerMinute,
        Integer crawlDelaySeconds,
        int maxJobs) {

    public static SourceConfiguration from(JobSource source, int maxJobs) {
        Integer crawlDelay = source.getAccessPolicy() == null
                ? null
                : source.getAccessPolicy().getCrawlDelaySeconds();
        return new SourceConfiguration(
                source.getId(),
                source.getName(),
                source.getBaseUrl(),
                source.getExternalIdentifier(),
                source.getAdapterKey(),
                source.getRateLimitPerMinute(),
                crawlDelay,
                maxJobs);
    }
}
