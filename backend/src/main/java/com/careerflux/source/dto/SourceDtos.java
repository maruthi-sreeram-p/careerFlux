package com.careerflux.source.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Response and request shapes for the Source Intelligence console. */
public final class SourceDtos {

    private SourceDtos() {
    }

    /**
     * Row in the source list.
     *
     * <p>{@code reliabilityPercent} is -1 when no sync has been attempted. The UI
     * renders that as "not measured", never as 0%, because those mean very
     * different things to someone deciding whether to trust a source.
     */
    public record SourceSummary(
            UUID id,
            String name,
            String baseUrl,
            String sourceType,
            String atsProvider,
            String adapterKey,
            String discoveryMethod,
            String state,
            String healthStatus,
            String companyName,
            UUID companyId,
            Instant discoveredAt,
            Instant stateChangedAt,
            Instant lastSuccessfulSyncAt,
            Instant lastHealthCheckAt,
            Instant nextReviewAt,
            int consecutiveFailures,
            int reliabilityPercent,
            int jobsIngestedTotal,
            long activeJobCount,
            int rateLimitPerMinute,
            boolean sampleData,
            boolean needsAttention,
            PolicyView policy) {
    }

    /** The access policy record, shown in full because it is the trust story. */
    public record PolicyView(
            String robotsStatus,
            String robotsUrl,
            String robotsRule,
            Instant robotsCheckedAt,
            Integer crawlDelaySeconds,
            String tosStatus,
            String tosUrl,
            String tosNotes,
            Instant tosReviewedAt,
            String tosReviewedBy,
            String accessPolicy,
            boolean requiresAuthentication,
            boolean requiresCaptcha,
            boolean hasAntiBot,
            boolean paywalled,
            String allowedFields,
            String decision,
            String decisionReason,
            String decidedBy,
            Instant verifiedAt) {
    }

    public record HealthCheckView(
            String status,
            Integer httpStatus,
            Integer latencyMs,
            Integer jobsSeen,
            String message,
            Instant checkedAt) {
    }

    public record LifecycleEntry(
            String fromState,
            String toState,
            String reason,
            String actor,
            Instant occurredAt) {
    }

    public record IngestionRunView(
            UUID id,
            String status,
            String trigger,
            String correlationId,
            int rawCount,
            int newCount,
            int updatedCount,
            int duplicateCount,
            int closedCount,
            int errorCount,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt) {
    }

    /** The verdict from the policy gate, so an operator sees exactly what is blocking. */
    public record PolicyVerdictView(boolean passed, List<String> blockers, List<String> warnings) {
    }

    public record SourceDetail(
            SourceSummary summary,
            PolicyVerdictView verdict,
            List<HealthCheckView> recentHealth,
            List<LifecycleEntry> lifecycle,
            List<IngestionRunView> recentRuns,
            List<String> allowedNextStates,
            String notes) {
    }

    /** Counts by state for the console header. All real, all queried. */
    public record SourceStats(
            long total,
            Map<String, Long> byState,
            Map<String, Long> byHealth,
            long needingAttention,
            long jobsFromActiveSources,
            String ingestionTransport,
            boolean aiEnabled) {
    }

    public record RegisterSourceRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 500) String baseUrl,
            @Size(max = 64) String adapterKey,
            @Size(max = 200) String externalIdentifier,
            @Size(max = 200) String companyName,
            @Size(max = 300) String companyWebsite,
            @Size(max = 500) String tosUrl,
            @Size(max = 600) String discoveryDetail,
            Integer rateLimitPerMinute) {
    }

    public record TermsReviewRequest(
            @NotBlank String tosStatus,
            @Size(max = 500) String tosUrl,
            @Size(max = 1000) String notes) {
    }

    public record AccessCharacteristicsRequest(
            @NotBlank String accessPolicy,
            boolean requiresAuthentication,
            boolean requiresCaptcha,
            boolean hasAntiBot,
            boolean paywalled,
            @Size(max = 600) String allowedFields) {
    }

    public record TransitionRequest(@NotBlank String targetState, @Size(max = 600) String reason) {
    }

    public record AdapterInfo(
            String key,
            String displayName,
            String sourceType,
            String atsProvider,
            String intendedAccessPolicy,
            String documentationUrl,
            String description) {
    }
}
