package com.careerflux.job.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response shapes for job discovery and the job detail page. */
public final class JobDtos {

    private JobDtos() {
    }

    public record CompanyRef(UUID id, String name, String logoUrl, String website, String industry) {
    }

    public record SkillRef(String name, String slug, String category, String requirement, String extractedBy) {
    }

    /**
     * Where one sighting of this job came from. This is the provenance record the
     * candidate sees, and it is deliberately explicit about whether the source is
     * real or bundled sample data.
     */
    public record ProvenanceEntry(
            UUID sourceId,
            String sourceName,
            String sourceType,
            String atsProvider,
            String discoveryMethod,
            String sourceUrl,
            String sourceState,
            String healthStatus,
            String accessPolicy,
            Instant policyVerifiedAt,
            Instant firstObservedAt,
            Instant lastObservedAt,
            int observationCount,
            boolean active,
            boolean sampleData) {
    }

    public record ChangeEntry(
            String changeType,
            String summary,
            String fieldName,
            String previousValue,
            String newValue,
            Instant detectedAt) {
    }

    public record MatchComponentView(String kind, String dimension, String label, String detail) {
    }

    /** The full explanation behind a score. Never returned without its components. */
    public record MatchAnalysis(
            int overall,
            String tier,
            int skills,
            int experience,
            int role,
            int location,
            int seniority,
            List<MatchComponentView> strengths,
            List<MatchComponentView> gaps,
            List<MatchComponentView> context,
            String narrative,
            String narrativeEngine,
            String scorerVersion,
            Instant computedAt) {
    }

    public record SalaryRange(BigDecimal min, BigDecimal max, String currency, String period) {
    }

    public record ExperienceRange(BigDecimal min, BigDecimal max) {
    }

    /** What the candidate has already done with this job. */
    public record InteractionState(boolean saved, boolean dismissed, boolean applied, String applicationStatus) {
    }

    public record JobSummary(
            UUID id,
            String title,
            CompanyRef company,
            String location,
            String city,
            String workMode,
            String employmentType,
            String seniority,
            ExperienceRange experience,
            SalaryRange salary,
            String status,
            Instant postedAt,
            Instant firstObservedAt,
            Instant lastObservedAt,
            int sourceCount,
            String primarySourceName,
            String primarySourceType,
            boolean sampleData,
            List<SkillRef> topSkills,
            MatchAnalysis match,
            InteractionState interaction,
            int recentChangeCount) {
    }

    public record JobDetail(
            JobSummary summary,
            String description,
            String responsibilities,
            String requirements,
            String applyUrl,
            List<SkillRef> skills,
            List<ProvenanceEntry> provenance,
            List<ChangeEntry> changes) {
    }

    /** One page of discovery results plus the facet counts the filter rail shows. */
    public record JobPage(
            List<JobSummary> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    public record InteractionRequest(String note, String applicationStatus) {
    }
}
