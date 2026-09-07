package com.careerflux.job.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.common.TextUtils;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.engagement.domain.InteractionType;
import com.careerflux.engagement.domain.JobInteraction;
import com.careerflux.engagement.repository.JobInteractionRepository;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobChange;
import com.careerflux.job.domain.JobObservation;
import com.careerflux.job.domain.JobSkill;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.dto.JobDtos.JobDetail;
import com.careerflux.job.dto.JobDtos.JobPage;
import com.careerflux.job.dto.JobDtos.JobSummary;
import com.careerflux.job.repository.JobChangeRepository;
import com.careerflux.job.repository.JobObservationRepository;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.job.repository.JobSkillRepository;
import com.careerflux.matching.domain.JobMatch;
import com.careerflux.matching.domain.MatchComponent;
import com.careerflux.matching.repository.JobMatchRepository;
import com.careerflux.matching.repository.MatchComponentRepository;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads jobs for the discovery and detail screens.
 *
 * <p>Filtering is expressed as JPA specifications against indexed columns, and
 * free-text search runs against the pre-built {@code search_text} column. That is
 * enough for the data volume CareerFlux actually holds; adding a search cluster
 * would be infrastructure without a problem to solve.
 */
@Service
public class JobQueryService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int RECENT_CHANGE_WINDOW_DAYS = 14;

    private final JobRepository jobRepository;
    private final JobSkillRepository jobSkillRepository;
    private final JobObservationRepository observationRepository;
    private final JobChangeRepository changeRepository;
    private final JobMatchRepository matchRepository;
    private final MatchComponentRepository componentRepository;
    private final JobInteractionRepository interactionRepository;
    private final CandidateProfileService profileService;
    private final JobMapper mapper;

    public JobQueryService(JobRepository jobRepository,
                           JobSkillRepository jobSkillRepository,
                           JobObservationRepository observationRepository,
                           JobChangeRepository changeRepository,
                           JobMatchRepository matchRepository,
                           MatchComponentRepository componentRepository,
                           JobInteractionRepository interactionRepository,
                           CandidateProfileService profileService,
                           JobMapper mapper) {
        this.jobRepository = jobRepository;
        this.jobSkillRepository = jobSkillRepository;
        this.observationRepository = observationRepository;
        this.changeRepository = changeRepository;
        this.matchRepository = matchRepository;
        this.componentRepository = componentRepository;
        this.interactionRepository = interactionRepository;
        this.profileService = profileService;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public JobPage search(UUID userId, JobFilter filter, int page, int size, String sort) {
        CandidateProfile profile = profileService.findByUserId(userId).orElse(null);
        // Whether a query is searchable is decided in one place. Asking
        // hasText() here and the ranking model there let them disagree: a query
        // of "%" canonicalises to nothing, so it is text but not a search, and
        // the two answers produced different orderings for the same results.
        boolean searchable = JobSearchRanking.parse(filter.query()) != null;
        // Both ends are clamped. A negative page was already handled; a size of
        // zero reached PageRequest and came back as a 500 for what is only a
        // malformed request.
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Pageable pageable = PageRequest.of(Math.max(0, page), pageSize, sortOf(sort, searchable));

        Page<Job> results = jobRepository.findAll(specificationFor(filter, profile), pageable);

        // Every filter is in the query above, so the page is already the answer.
        // The match floor and the dismissed list used to be applied to these rows
        // afterwards, which cannot reach a job the page never selected: the first
        // page of a high floor came back empty while the count promised
        // thousands, and the jobs that qualified sat forty pages away.
        List<JobSummary> summaries = decorate(results.getContent(), profile, false);

        return new JobPage(summaries, results.getNumber(), results.getSize(),
                results.getTotalElements(), results.getTotalPages());
    }

    /** The dashboard feed: matches above the visibility floor, best first. */
    @Transactional(readOnly = true)
    public List<JobSummary> recommendations(UUID userId, int limit) {
        CandidateProfile profile = profileService.findByUserId(userId).orElse(null);
        if (profile == null) {
            return List.of();
        }
        Set<UUID> dismissed = new java.util.HashSet<>(
                interactionRepository.findJobIdsByType(profile.getId(), InteractionType.DISMISSED));

        Page<JobMatch> matches = matchRepository.findByCandidateIdAndTierInOrderByOverallScoreDesc(
                profile.getId(),
                EnumSet.of(com.careerflux.matching.domain.MatchTier.EXCELLENT,
                        com.careerflux.matching.domain.MatchTier.STRONG,
                        com.careerflux.matching.domain.MatchTier.MODERATE),
                PageRequest.of(0, Math.min(limit, MAX_PAGE_SIZE)));

        List<Job> jobs = matches.getContent().stream()
                .map(JobMatch::getJob)
                .filter(job -> !dismissed.contains(job.getId()))
                .filter(job -> job.getStatus() != JobStatus.CLOSED)
                .toList();

        return decorate(jobs, profile, true);
    }

    /** Summaries for an explicit set of job ids, preserving the order given. */
    @Transactional(readOnly = true)
    public List<JobSummary> summariesFor(UUID userId, List<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return List.of();
        }
        CandidateProfile profile = profileService.findByUserId(userId).orElse(null);
        Map<UUID, Job> byId = jobRepository.findAllById(jobIds).stream()
                .collect(Collectors.toMap(Job::getId, job -> job));
        List<Job> ordered = jobIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        return decorate(ordered, profile, false);
    }

    @Transactional(readOnly = true)
    public JobDetail detail(UUID userId, UUID jobId) {
        Job job = jobRepository.findFullById(jobId)
                .orElseThrow(() -> NotFoundException.of("Job", jobId));
        CandidateProfile profile = profileService.findByUserId(userId).orElse(null);

        List<JobSkill> skills = jobSkillRepository.findByJobId(jobId);
        List<JobObservation> observations = observationRepository.findByJobIdOrderByFirstObservedAtAsc(jobId);
        List<JobChange> changes = changeRepository.findByJobIdOrderByDetectedAtDesc(
                jobId, PageRequest.of(0, 20));

        JobMatch match = null;
        List<MatchComponent> components = List.of();
        List<JobInteraction> interactions = List.of();
        if (profile != null) {
            match = matchRepository.findByCandidateIdAndJobId(profile.getId(), jobId).orElse(null);
            if (match != null) {
                components = componentRepository.findByMatchIdOrderByDisplayOrderAsc(match.getId());
            }
            interactions = interactionRepository.findByCandidateIdAndJobIdIn(profile.getId(), List.of(jobId));
        }

        int recentChanges = (int) changeRepository.countRealChanges(
                List.of(jobId), Instant.now().minus(RECENT_CHANGE_WINDOW_DAYS, ChronoUnit.DAYS));

        JobSummary summary = mapper.toSummary(job, skills, observations, match, components,
                interactions, recentChanges);
        return mapper.toDetail(summary, job, skills, observations, changes);
    }

    /**
     * Loads everything the summaries need in a fixed number of queries rather than
     * one per job.
     */
    private List<JobSummary> decorate(List<Job> jobs, CandidateProfile profile, boolean hideDismissed) {
        if (jobs.isEmpty()) {
            return List.of();
        }
        List<UUID> jobIds = jobs.stream().map(Job::getId).toList();

        Map<UUID, List<JobSkill>> skillsByJob = jobSkillRepository.findByJobIdIn(jobIds).stream()
                .collect(Collectors.groupingBy(skill -> skill.getJob().getId()));

        Map<UUID, List<JobObservation>> observationsByJob = new HashMap<>();
        for (UUID jobId : jobIds) {
            observationsByJob.put(jobId, observationRepository.findByJobIdOrderByFirstObservedAtAsc(jobId));
        }

        Map<UUID, JobMatch> matchesByJob = Map.of();
        Map<UUID, List<MatchComponent>> componentsByMatch = Map.of();
        Map<UUID, List<JobInteraction>> interactionsByJob = Map.of();
        Set<UUID> dismissedJobIds = Set.of();

        if (profile != null) {
            List<JobMatch> matches = jobIds.stream()
                    .map(jobId -> matchRepository.findByCandidateIdAndJobId(profile.getId(), jobId))
                    .flatMap(java.util.Optional::stream)
                    .toList();
            matchesByJob = matches.stream()
                    .collect(Collectors.toMap(match -> match.getJob().getId(), match -> match,
                            (first, second) -> first));
            if (!matches.isEmpty()) {
                componentsByMatch = componentRepository
                        .findByMatchIdInOrderByDisplayOrderAsc(matches.stream().map(JobMatch::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(component -> component.getMatch().getId()));
            }
            List<JobInteraction> interactions =
                    interactionRepository.findByCandidateIdAndJobIdIn(profile.getId(), jobIds);
            interactionsByJob = interactions.stream()
                    .collect(Collectors.groupingBy(interaction -> interaction.getJob().getId()));
            dismissedJobIds = interactions.stream()
                    .filter(interaction -> interaction.getInteractionType() == InteractionType.DISMISSED)
                    .map(interaction -> interaction.getJob().getId())
                    .collect(Collectors.toSet());
        }

        Instant changeWindow = Instant.now().minus(RECENT_CHANGE_WINDOW_DAYS, ChronoUnit.DAYS);
        Map<UUID, Long> changesByJob = new HashMap<>();
        for (Object[] row : changeRepository.countRealChangesByJob(jobIds, changeWindow)) {
            changesByJob.put((UUID) row[0], (Long) row[1]);
        }

        List<JobSummary> summaries = new ArrayList<>();
        for (Job job : jobs) {
            if (hideDismissed && dismissedJobIds.contains(job.getId())) {
                continue;
            }
            JobMatch match = matchesByJob.get(job.getId());
            List<MatchComponent> components = match == null
                    ? List.of()
                    : componentsByMatch.getOrDefault(match.getId(), List.of());
            int recentChanges = changesByJob.getOrDefault(job.getId(), 0L).intValue();

            summaries.add(mapper.toSummary(
                    job,
                    skillsByJob.getOrDefault(job.getId(), List.of()),
                    observationsByJob.getOrDefault(job.getId(), List.of()),
                    match,
                    components,
                    interactionsByJob.getOrDefault(job.getId(), List.of()),
                    recentChanges));
        }
        return summaries;
    }

    private Specification<Job> specificationFor(JobFilter filter, CandidateProfile profile) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            Set<JobStatus> statuses = filter.includeClosed()
                    ? EnumSet.allOf(JobStatus.class)
                    : EnumSet.of(JobStatus.OPEN, JobStatus.REOPENED);
            predicates.add(root.get("status").in(statuses));

            JobSearchRanking ranking = JobSearchRanking.parse(filter.query());
            if (ranking != null) {
                predicates.add(ranking.toPredicate(root, builder));
            }
            if (TextUtils.hasText(filter.location())) {
                // Trimmed, because this goes straight into a LIKE pattern: an
                // untrimmed "Bengaluru " asks for the space too and matches
                // nothing. The search box already trims via canonicalize; the
                // location box has to do the same or pasting a value silently
                // empties the results.
                String needle = "%" + filter.location().strip().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("city")), needle),
                        builder.like(builder.lower(root.get("locationRaw")), needle)));
            }
            addEnumFilter(predicates, root, "workMode", filter.workModes(), WorkMode.class);
            addEnumFilter(predicates, root, "employmentType", filter.employmentTypes(), EmploymentType.class);
            addEnumFilter(predicates, root, "seniority", filter.seniorities(), Seniority.class);

            if (filter.maxExperienceYears() != null) {
                predicates.add(builder.or(
                        builder.isNull(root.get("minExperienceYears")),
                        builder.lessThanOrEqualTo(root.get("minExperienceYears"),
                                java.math.BigDecimal.valueOf(filter.maxExperienceYears()))));
            }
            if (filter.postedWithinDays() != null && filter.postedWithinDays() > 0) {
                Instant threshold = Instant.now().minus(filter.postedWithinDays(), ChronoUnit.DAYS);
                predicates.add(builder.or(
                        builder.greaterThan(root.get("postedAt"), threshold),
                        builder.and(builder.isNull(root.get("postedAt")),
                                builder.greaterThan(root.get("firstObservedAt"), threshold))));
            }
            if (filter.companyId() != null) {
                predicates.add(builder.equal(root.get("company").get("id"), filter.companyId()));
            }
            if (TextUtils.hasText(filter.skillSlug())) {
                var skills = root.join("skills");
                predicates.add(builder.equal(skills.get("skill").get("slug"), filter.skillSlug()));
                query.distinct(true);
            }
            if (filter.sourceId() != null) {
                var observations = root.join("observations");
                predicates.add(builder.equal(observations.get("source").get("id"), filter.sourceId()));
                query.distinct(true);
            }

            // The candidate-scoped filters, as subqueries rather than joins, so a
            // job cannot appear twice and the count query stays correct.
            if (filter.minMatchScore() != null) {
                if (profile == null) {
                    // Nobody to score against. Asking for a match floor without a
                    // candidate profile matches nothing, which is what filtering
                    // the summaries afterwards also did.
                    predicates.add(builder.disjunction());
                } else {
                    jakarta.persistence.criteria.Subquery<UUID> scored = query.subquery(UUID.class);
                    var match = scored.from(JobMatch.class);
                    scored.select(match.get("job").get("id"))
                            .where(builder.equal(match.get("candidate").get("id"), profile.getId()),
                                    builder.greaterThanOrEqualTo(match.get("overallScore"),
                                            filter.minMatchScore()));
                    predicates.add(root.get("id").in(scored));
                }
            }
            if (filter.hideDismissed() && profile != null) {
                jakarta.persistence.criteria.Subquery<UUID> dismissed = query.subquery(UUID.class);
                var interaction = dismissed.from(JobInteraction.class);
                dismissed.select(interaction.get("job").get("id"))
                        .where(builder.equal(interaction.get("candidate").get("id"), profile.getId()),
                                builder.equal(interaction.get("interactionType"),
                                        InteractionType.DISMISSED));
                predicates.add(builder.not(root.get("id").in(dismissed)));
            }

            // Ordering belongs on the result query only. Spring Data reuses this
            // specification to count the matches, and a count query with an
            // ORDER BY over a selected expression is invalid.
            boolean isCountQuery = query.getResultType() == Long.class
                    || query.getResultType() == long.class;
            if (ranking != null && !isCountQuery) {
                query.orderBy(
                        builder.desc(ranking.relevanceScore(root, builder)),
                        builder.asc(ranking.titleLength(root, builder)),
                        builder.desc(root.get("lastObservedAt")));
            }

            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private <E extends Enum<E>> void addEnumFilter(List<Predicate> predicates,
                                                   jakarta.persistence.criteria.Root<Job> root,
                                                   String attribute, List<String> values, Class<E> type) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<E> parsed = values.stream()
                .map(value -> {
                    try {
                        return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
                    } catch (IllegalArgumentException ex) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        if (!parsed.isEmpty()) {
            predicates.add(root.get(attribute).in(parsed));
        }
    }

    /**
     * Resolves the ordering, and decides who does it.
     *
     * <p>An unsorted {@link Sort} is not an oversight: it is how relevance
     * ordering is requested. Spring Data applies a sorted {@code Pageable} by
     * calling {@code orderBy} on the query, which replaces whatever the
     * specification set, so the relevance ordering only survives when nothing
     * is asked for here.
     *
     * <p>Searching with no stated preference means the caller wants the best
     * matches, not the most recently seen ones — so a query defaults to
     * relevance, and browsing with no query defaults to recency.
     */
    private Sort sortOf(String sort, boolean hasQuery) {
        String requested = sort == null ? "" : sort.toLowerCase(Locale.ROOT).strip();
        if (requested.isEmpty()) {
            return hasQuery ? Sort.unsorted() : Sort.by(Sort.Direction.DESC, "lastObservedAt");
        }
        return switch (requested) {
            case "relevance" -> hasQuery ? Sort.unsorted() : Sort.by(Sort.Direction.DESC, "lastObservedAt");
            case "newest" -> Sort.by(Sort.Direction.DESC, "firstObservedAt");
            case "posted" -> Sort.by(Sort.Direction.DESC, "postedAt");
            case "title" -> Sort.by(Sort.Direction.ASC, "title");
            case "company" -> Sort.by(Sort.Direction.ASC, "company");
            default -> Sort.by(Sort.Direction.DESC, "lastObservedAt");
        };
    }

    /** Discovery filters. Every field is optional. */
    public record JobFilter(
            String query,
            String location,
            List<String> workModes,
            List<String> employmentTypes,
            List<String> seniorities,
            Double maxExperienceYears,
            Integer postedWithinDays,
            UUID companyId,
            UUID sourceId,
            String skillSlug,
            Integer minMatchScore,
            boolean includeClosed,
            boolean hideDismissed) {
    }
}
