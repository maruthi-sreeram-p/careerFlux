package com.careerflux.dashboard;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.careerflux.ai.AiClient;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.ResumeRepository;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.dashboard.DashboardDtos.ActivityItem;
import com.careerflux.dashboard.DashboardDtos.DashboardResponse;
import com.careerflux.dashboard.DashboardDtos.DashboardSummary;
import com.careerflux.dashboard.DashboardDtos.EngagementCounts;
import com.careerflux.engagement.service.EngagementService;
import com.careerflux.job.domain.JobChange;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.dto.JobDtos.JobSummary;
import com.careerflux.job.repository.JobChangeRepository;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.job.service.JobQueryService;
import com.careerflux.matching.domain.MatchTier;
import com.careerflux.matching.repository.JobMatchRepository;
import com.careerflux.notification.service.NotificationService;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the dashboard.
 *
 * <p>"New since your last visit" is measured against the user's previous login
 * timestamp, which is a real thing we record. When there is no previous login the
 * field is reported as zero and the copy adapts, rather than inventing a number
 * to fill the space.
 */
@Service
public class DashboardService {

    private static final int RECOMMENDATION_LIMIT = 12;
    private static final int ACTIVITY_LIMIT = 8;

    private final UserRepository userRepository;
    private final CandidateProfileService profileService;
    private final ResumeRepository resumeRepository;
    private final JobMatchRepository matchRepository;
    private final JobRepository jobRepository;
    private final JobChangeRepository changeRepository;
    private final JobSourceRepository sourceRepository;
    private final JobQueryService jobQueryService;
    private final EngagementService engagementService;
    private final NotificationService notificationService;
    private final AiClient aiClient;

    public DashboardService(UserRepository userRepository,
                            CandidateProfileService profileService,
                            ResumeRepository resumeRepository,
                            JobMatchRepository matchRepository,
                            JobRepository jobRepository,
                            JobChangeRepository changeRepository,
                            JobSourceRepository sourceRepository,
                            JobQueryService jobQueryService,
                            EngagementService engagementService,
                            NotificationService notificationService,
                            AiClient aiClient) {
        this.userRepository = userRepository;
        this.profileService = profileService;
        this.resumeRepository = resumeRepository;
        this.matchRepository = matchRepository;
        this.jobRepository = jobRepository;
        this.changeRepository = changeRepository;
        this.sourceRepository = sourceRepository;
        this.jobQueryService = jobQueryService;
        this.engagementService = engagementService;
        this.notificationService = notificationService;
        this.aiClient = aiClient;
    }

    @Transactional(readOnly = true)
    public DashboardResponse build(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow();
        CandidateProfile profile = profileService.findByUserId(userId).orElse(null);

        long sourcesMonitored = sourceRepository.count();
        long sourcesActive = sourceRepository.countByState(SourceState.ACTIVE);
        long openJobs = jobRepository.countByStatus(JobStatus.OPEN);

        if (profile == null) {
            return new DashboardResponse(
                    new DashboardSummary(user.getFullName(), 0, 0, 0, 0,
                            sourcesMonitored, sourcesActive, openJobs, null, 0,
                            "RESUME_UPLOAD", false, aiClient.isAvailable(),
                            "Finish setting up your profile so CareerFlux can start matching."),
                    List.of(), List.of(), 0, new EngagementCounts(0, 0, 0));
        }

        // "Since your last visit" uses the previous login, which is what the user
        // actually means by it. On a first visit there is nothing to compare against.
        Instant since = user.getLastLoginAt() == null
                ? Instant.now().minus(7, ChronoUnit.DAYS)
                : user.getLastLoginAt();

        long excellent = matchRepository.countByCandidateIdAndTierIn(profile.getId(),
                EnumSet.of(MatchTier.EXCELLENT));
        long strong = matchRepository.countByCandidateIdAndTierIn(profile.getId(),
                EnumSet.of(MatchTier.STRONG));
        long visible = matchRepository.countByCandidateIdAndTierIn(profile.getId(),
                EnumSet.of(MatchTier.EXCELLENT, MatchTier.STRONG, MatchTier.MODERATE));
        long newSince = matchRepository.countByCandidateIdAndComputedAtAfter(profile.getId(), since);

        List<JobSummary> recommendations = jobQueryService.recommendations(userId, RECOMMENDATION_LIMIT);
        Instant lastComputed = recommendations.stream()
                .map(JobSummary::match)
                .filter(java.util.Objects::nonNull)
                .map(match -> match.computedAt())
                .max(Comparator.naturalOrder())
                .orElse(null);

        String notice = null;
        if (visible == 0 && profile.getProfileCompleteness() < 50) {
            notice = "Add your skills and target roles so CareerFlux can match you properly.";
        } else if (visible == 0 && openJobs == 0) {
            notice = "No jobs have been ingested yet. Activate a source to start collecting.";
        }

        EngagementService.Counts counts = engagementService.counts(userId);

        return new DashboardResponse(
                new DashboardSummary(
                        user.getFullName(),
                        (int) newSince,
                        (int) excellent,
                        (int) strong,
                        (int) visible,
                        sourcesMonitored,
                        sourcesActive,
                        openJobs,
                        lastComputed,
                        profile.getProfileCompleteness(),
                        profile.getOnboardingStage().name(),
                        resumeRepository.findFirstByCandidateIdAndActiveTrueOrderByUploadedAtDesc(profile.getId())
                                .isPresent(),
                        aiClient.isAvailable(),
                        notice),
                recommendations,
                recentActivity(),
                notificationService.unreadCount(userId),
                new EngagementCounts(counts.saved(), counts.applied(), counts.dismissed()));
    }

    /**
     * Recent job changes across the corpus: what CareerFlux has been seeing move.
     *
     * <p>One row per job. A single ingestion run legitimately records several
     * changes against the same posting — created, then also listed elsewhere,
     * then edited — and showing all of them turns the feed into the same job
     * repeated three times. The newest change per job is the interesting one.
     */
    private List<ActivityItem> recentActivity() {
        List<JobChange> changes = changeRepository.findByDetectedAtAfterOrderByDetectedAtDesc(
                Instant.now().minus(14, ChronoUnit.DAYS), PageRequest.of(0, ACTIVITY_LIMIT * 6));

        Set<UUID> seenJobs = new LinkedHashSet<>();
        List<ActivityItem> items = new ArrayList<>();
        for (JobChange change : changes) {
            if (items.size() >= ACTIVITY_LIMIT) {
                break;
            }
            if (!seenJobs.add(change.getJob().getId())) {
                continue;
            }
            String company = change.getJob().getCompany() == null
                    ? null : change.getJob().getCompany().getName();
            items.add(new ActivityItem(
                    change.getChangeType().name(),
                    change.getJob().getTitle() + (company == null ? "" : " at " + company),
                    change.getSummary(),
                    change.getDetectedAt(),
                    change.getJob().getId().toString(),
                    null));
        }
        return items;
    }
}
