package com.careerflux.dashboard;

import java.time.Instant;
import java.util.List;

import com.careerflux.job.dto.JobDtos.JobSummary;

/** What the candidate sees when they open CareerFlux. */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    /**
     * Headline counts. Every one of these is a real query against real rows; if
     * something has not been computed yet the field is null and the UI says so
     * rather than showing a zero that looks like a measurement.
     */
    public record DashboardSummary(
            String greetingName,
            int newSinceLastVisit,
            int excellentMatches,
            int strongMatches,
            int totalVisibleMatches,
            long sourcesMonitored,
            long sourcesActive,
            long openJobs,
            Instant lastMatchComputedAt,
            int profileCompleteness,
            String onboardingStage,
            boolean hasResume,
            boolean aiEnabled,
            String notice) {
    }

    public record ActivityItem(
            String kind,
            String title,
            String detail,
            Instant occurredAt,
            String jobId,
            String sourceId) {
    }

    public record DashboardResponse(
            DashboardSummary summary,
            List<JobSummary> recommendations,
            List<ActivityItem> recentActivity,
            long unreadNotifications,
            EngagementCounts engagement) {
    }

    public record EngagementCounts(long saved, long applied, long dismissed) {
    }
}
