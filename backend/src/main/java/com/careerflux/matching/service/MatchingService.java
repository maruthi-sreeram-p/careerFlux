package com.careerflux.matching.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import com.careerflux.candidate.domain.CandidatePreferences;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.PreferenceType;
import com.careerflux.candidate.repository.CandidatePreferenceValueRepository;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.repository.CandidateSkillRepository;
import com.careerflux.common.TextUtils;
import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.matching.domain.JobMatch;
import com.careerflux.matching.role.ScorableRoles;
import com.careerflux.matching.domain.ConfidenceLevel;
import com.careerflux.matching.domain.EligibilityStatus;
import com.careerflux.matching.domain.MatchDimension;
import com.careerflux.matching.domain.MatchComponent;
import com.careerflux.matching.domain.MatchTier;
import com.careerflux.matching.rematch.RematchReason;
import com.careerflux.matching.rematch.RematchStatus;
import com.careerflux.matching.repository.JobMatchRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Computes and stores matches.
 *
 * <p>Matching runs against open jobs for one candidate at a time. Each result is
 * upserted so a re-run updates the existing row rather than accumulating
 * duplicates, and the components are replaced wholesale so an explanation can
 * never be a mix of two different score computations.
 */
@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);

    private final CandidateProfileRepository profileRepository;
    private final CandidateSkillRepository candidateSkillRepository;
    private final CandidatePreferenceValueRepository preferenceValueRepository;
    private final JobRepository jobRepository;
    private final JobMatchRepository matchRepository;
    private final MatchScorer scorer;
    private final MatchNarrator narrator;
    private final CareerFluxProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final com.careerflux.matching.rematch.RematchQueue rematchQueue;

    /** One lock per candidate currently being rematched. */
    private final ConcurrentHashMap<UUID, ReentrantLock> candidateLocks = new ConcurrentHashMap<>();

    public MatchingService(CandidateProfileRepository profileRepository,
                           CandidateSkillRepository candidateSkillRepository,
                           CandidatePreferenceValueRepository preferenceValueRepository,
                           JobRepository jobRepository,
                           JobMatchRepository matchRepository,
                           MatchScorer scorer,
                           MatchNarrator narrator,
                           CareerFluxProperties properties,
                           PlatformTransactionManager transactionManager,
                           com.careerflux.matching.rematch.RematchQueue rematchQueue) {
        this.rematchQueue = rematchQueue;
        this.profileRepository = profileRepository;
        this.candidateSkillRepository = candidateSkillRepository;
        this.preferenceValueRepository = preferenceValueRepository;
        this.jobRepository = jobRepository;
        this.matchRepository = matchRepository;
        this.scorer = scorer;
        this.narrator = narrator;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Reads a candidate into the immutable form the scorer works with. */
    @Transactional(readOnly = true)
    public CandidateSnapshot snapshot(CandidateProfile profile) {
        Set<String> skillSlugs = candidateSkillRepository.findByCandidateId(profile.getId()).stream()
                .map(candidateSkill -> candidateSkill.getSkill().getSlug())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> targetRoles = preferenceValues(profile.getId(), PreferenceType.TARGET_ROLE);
        Set<String> locations = new LinkedHashSet<>(preferenceValues(profile.getId(), PreferenceType.LOCATION));

        Set<WorkMode> workModes = preferenceValues(profile.getId(), PreferenceType.WORK_MODE).stream()
                .map(value -> parseEnum(WorkMode.class, value))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(WorkMode.class)));

        Set<EmploymentType> employmentTypes =
                preferenceValues(profile.getId(), PreferenceType.EMPLOYMENT_TYPE).stream()
                        .map(value -> parseEnum(EmploymentType.class, value))
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(EmploymentType.class)));

        CandidatePreferences preferences = profile.getPreferences();
        return new CandidateSnapshot(
                profile.getId(),
                profile.getUser().getId(),
                profile.getUser().getFullName(),
                profile.getPrimaryRole(),
                profile.getSeniority(),
                profile.getYearsExperience(),
                profile.getLocation(),
                skillSlugs,
                targetRoles,
                locations,
                workModes,
                employmentTypes,
                preferences != null && preferences.isOpenToRelocation(),
                preferences == null || preferences.isImmediateAlerts(),
                preferences == null || preferences.isDailyDigest());
    }

    /**
     * Queues a recompute for the candidate behind a user account.
     *
     * <p>Returns the state of the queued work rather than its results. The
     * caller gets an immediate, honest answer — "this is being recalculated" —
     * and the feed is fetched separately once it completes.
     */
    public com.careerflux.job.web.JobController.RematchResponse requestRematch(java.util.UUID userId) {
        CandidateProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new com.careerflux.common.error.NotFoundException(
                        "No candidate profile for this account."));
        return rematchQueue.enqueue(profile.getId(), RematchReason.MANUAL)
                .map(request -> new com.careerflux.job.web.JobController.RematchResponse(
                        request.getStatus().name(),
                        request.getStatus() == RematchStatus.PENDING
                                || request.getStatus() == RematchStatus.RUNNING,
                        "Your matches are being recalculated. This usually takes a minute or two."))
                .orElseGet(() -> new com.careerflux.job.web.JobController.RematchResponse(
                        "FAILED", false, "That profile could not be found."));
    }

    /** Recomputes every match for the candidate belonging to a user account. */
    public MatchRun recomputeForUser(UUID userId) {
        CandidateProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new com.careerflux.common.error.NotFoundException(
                        "No candidate profile for this account."));
        return recomputeForCandidate(profile.getId());
    }

    /**
     * Recomputes every match for one candidate.
     *
     * <p>Serialised per candidate. Two rematches for the same person can otherwise
     * overlap — the profile-changed listener and an explicit request from the UI
     * commonly arrive together — and both would miss on the find-then-insert in
     * {@link #scoreAndStore}, colliding on the unique (candidate, job) constraint.
     * The second caller waits for the first and then runs against the upserted
     * rows, which is fast and gives it a correct count to report.
     *
     * @return the matches that scored at or above the visibility floor, best first
     */
    public MatchRun recomputeForCandidate(UUID candidateId) {
        ReentrantLock lock = candidateLocks.computeIfAbsent(candidateId, id -> new ReentrantLock());
        lock.lock();
        try {
            // The transaction is opened explicitly rather than with @Transactional,
            // because a self-invocation would not go through the Spring proxy and
            // would silently run without one.
            return transactionTemplate.execute(status -> doRecompute(candidateId));
        } finally {
            lock.unlock();
            // Only drop the lock when nobody else holds or is waiting for it,
            // otherwise the map would grow one entry per candidate forever.
            if (!lock.isLocked() && !lock.hasQueuedThreads()) {
                candidateLocks.remove(candidateId, lock);
            }
        }
    }

    private MatchRun doRecompute(UUID candidateId) {
        CandidateProfile profile = profileRepository.findById(candidateId).orElseThrow();
        CandidateSnapshot candidate = snapshot(profile);

        if (!candidate.isScorable()) {
            log.debug("Candidate {} has too little profile data to score", candidateId);
            return new MatchRun(0, 0, List.of(),
                    "Add skills or a target role and CareerFlux can start matching.");
        }

        // Walk the whole corpus a page at a time. This used to fetch a single
        // page and stop, which the constant's name hid: a candidate was scored
        // against the 400 most recently observed jobs and nothing else. With a
        // corpus of 1,919 open jobs that scored 1 of the 61 backend roles for a
        // backend developer, so every match landed below the visibility floor
        // and the dashboard was empty. Scoring a subset is defensible; scoring
        // the most *recent* subset is not, because recency has nothing to do
        // with fit.
        List<JobMatch> visible = new ArrayList<>();
        int scored = 0;
        boolean truncated = false;

        int batchSize = Math.max(1, properties.matching().batchSize());
        int ceiling = Math.max(batchSize, properties.matching().maxJobsPerCandidate());

        for (int page = 0; page * batchSize < ceiling; page++) {
            List<Job> batch = jobRepository
                    .findByStatusInOrderByLastObservedAtDesc(
                            EnumSet.of(JobStatus.OPEN, JobStatus.REOPENED),
                            PageRequest.of(page, batchSize))
                    .getContent();
            if (batch.isEmpty()) {
                break;
            }
            for (Job job : batch) {
                JobMatch match = scoreAndStore(profile, candidate, job);
                scored++;
                if (match.getTier().isRecommendable()) {
                    visible.add(match);
                }
            }
            if (batch.size() < batchSize) {
                break;
            }
            truncated = (page + 1) * batchSize >= ceiling;
        }

        if (truncated) {
            // Said out loud rather than hidden, because a silent ceiling is what
            // caused the original bug.
            log.warn("Candidate {} was scored against the first {} open jobs only; the corpus is larger "
                    + "than the per-candidate ceiling", candidateId, ceiling);
        }

        visible.sort((left, right) -> Integer.compare(right.getOverallScore(), left.getOverallScore()));
        log.info("Scored {} jobs for candidate {}; {} above the visibility floor", scored, candidateId, visible.size());
        return new MatchRun(scored, visible.size(), visible, null);
    }

    /**
     * Scores one candidate against one job and upserts the result.
     *
     * <p>Takes the same per-candidate lock as a full rematch. It is reentrant, so
     * a rematch calling this in a loop pays nothing, but a newly ingested job
     * being scored for a candidate who is simultaneously rematching cannot
     * collide on the unique constraint.
     */
    @Transactional
    public JobMatch scoreAndStore(CandidateProfile profile, CandidateSnapshot candidate, Job job) {
        ReentrantLock lock = candidateLocks.computeIfAbsent(profile.getId(), id -> new ReentrantLock());
        lock.lock();
        try {
            return doScoreAndStore(profile, candidate, job);
        } finally {
            lock.unlock();
        }
    }

    private JobMatch doScoreAndStore(CandidateProfile profile, CandidateSnapshot candidate, Job job) {
        MatchScorer.Scorecard scorecard = scorer.score(candidate, ScorableRoles.of(job));
        MatchTier tier = tierFor(scorecard);

        JobMatch match = matchRepository.findByCandidateIdAndJobId(profile.getId(), job.getId())
                .orElseGet(() -> {
                    JobMatch created = new JobMatch();
                    created.setCandidate(profile);
                    created.setJob(job);
                    return created;
                });

        // An unavailable match stores 0 rather than null so ordering and
        // aggregate queries stay simple; the tier is what says it is unscored.
        match.setOverallScore(scorecard.isAvailable() ? scorecard.compatibility() : 0);
        match.setSkillScore(scorecard.score(MatchDimension.SKILLS));
        match.setExperienceScore(scorecard.score(MatchDimension.EXPERIENCE));
        match.setRoleScore(scorecard.score(MatchDimension.ROLE));
        match.setLocationScore(scorecard.score(MatchDimension.LOCATION));
        match.setSeniorityScore(scorecard.score(MatchDimension.SENIORITY));
        match.setWorkModeScore(scorecard.score(MatchDimension.WORK_MODE));
        match.setEligibility(scorecard.eligibility());
        match.setConfidenceLevel(scorecard.confidence().level());
        match.setConfidenceCoverage(scorecard.confidence().coveragePercent());
        match.setTier(tier);
        match.setScorerVersion(MatchScorer.VERSION);
        match.setComputedAt(Instant.now());

        match.getComponents().clear();
        for (MatchComponent component : scorecard.components()) {
            component.setMatch(match);
            match.getComponents().add(component);
        }

        // Narratives cost a model call, so only matches the candidate will actually
        // see get one. Hidden matches keep their sub-scores for auditability.
        // A narrative costs a model call, so only matches a candidate will
        // actually see get one.
        if (tier.isRecommendable()) {
            MatchNarrator.Narrative narrative = narrator.write(scorecard, job, candidate);
            match.setNarrative(TextUtils.truncate(narrative.text(), 2000));
            match.setNarrativeEngine(narrative.engine());
        } else {
            match.setNarrative(null);
            match.setNarrativeEngine(null);
        }

        return matchRepository.save(match);
    }

    /** Scores every onboarded candidate against a newly ingested job. */
    @Transactional
    public int scoreNewJobForAllCandidates(UUID jobId) {
        Job job = jobRepository.findFullById(jobId).orElse(null);
        if (job == null) {
            return 0;
        }
        List<CandidateProfile> candidates = profileRepository.findAllOnboarded();
        int visible = 0;
        for (CandidateProfile profile : candidates) {
            CandidateSnapshot snapshot = snapshot(profile);
            if (!snapshot.isScorable()) {
                continue;
            }
            JobMatch match = scoreAndStore(profile, snapshot, job);
            if (match.getTier().isRecommendable()) {
                visible++;
            }
        }
        return visible;
    }

    /**
     * Presentation tier, read from the score together with eligibility, gaps and
     * confidence.
     *
     * <p>This never alters the score. Compatibility stays the plain arithmetic
     * result so it can always be reconstructed by hand; the tier is where the
     * product's rules about what may be called excellent live.
     */
    public MatchTier tierFor(MatchScorer.Scorecard scorecard) {
        if (!scorecard.isAvailable()) {
            return MatchTier.UNAVAILABLE;
        }
        if (scorecard.eligibility() == EligibilityStatus.NOT_ELIGIBLE) {
            return MatchTier.NOT_ELIGIBLE;
        }
        CareerFluxProperties.Matching thresholds = properties.matching();
        int score = scorecard.compatibility();
        if (score < thresholds.hiddenBelow()) {
            return MatchTier.HIDDEN;
        }
        // Excellence requires that nothing the posting asked for is missing, and
        // that we actually knew enough to say so.
        if (score >= thresholds.immediateMin()
                && scorecard.missingRequiredSkills().isEmpty()
                && scorecard.confidence().level() == ConfidenceLevel.HIGH) {
            return MatchTier.EXCELLENT;
        }
        if (score >= thresholds.highPriorityMin()) {
            return MatchTier.STRONG;
        }
        if (score >= thresholds.digestMin()) {
            return MatchTier.MODERATE;
        }
        return MatchTier.WEAK;
    }

    private List<String> preferenceValues(UUID candidateId, PreferenceType type) {
        return preferenceValueRepository.findByCandidateIdAndValueType(candidateId, type).stream()
                .map(com.careerflux.candidate.domain.CandidatePreferenceValue::getValue)
                .toList();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (!TextUtils.hasText(value)) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * @param notice non-null when nothing could be scored and the candidate needs
     *               to be told why, rather than shown an empty list
     */
    public record MatchRun(int jobsScored, int visibleMatches, List<JobMatch> matches, String notice) {
    }
}
