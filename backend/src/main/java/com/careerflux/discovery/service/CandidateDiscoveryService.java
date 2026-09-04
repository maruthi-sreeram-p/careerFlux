package com.careerflux.discovery.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.careerflux.candidate.domain.CandidatePreferenceValue;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.CandidateSkill;
import com.careerflux.candidate.domain.PreferenceType;
import com.careerflux.candidate.repository.CandidatePreferenceValueRepository;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.repository.CandidateSkillRepository;
import com.careerflux.common.error.BadRequestException;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.discovery.dto.DiscoveryDtos.CandidatePage;
import com.careerflux.discovery.dto.DiscoveryDtos.CandidateView;
import com.careerflux.discovery.dto.DiscoveryDtos.DimensionView;
import com.careerflux.discovery.dto.DiscoveryDtos.ReasonView;
import com.careerflux.institution.domain.Department;
import com.careerflux.job.domain.SkillRequirement;
import com.careerflux.matching.domain.EligibilityStatus;
import com.careerflux.matching.domain.MatchComponent;
import com.careerflux.matching.domain.MatchDimension;
import com.careerflux.matching.role.RoleSkill;
import com.careerflux.matching.role.ScorableRole;
import com.careerflux.matching.role.ScorableRoles;
import com.careerflux.matching.service.CandidateSnapshot;
import com.careerflux.matching.service.MatchScorer;
import com.careerflux.requirement.domain.CompanyRequirement;
import com.careerflux.requirement.domain.RequirementStatus;
import com.careerflux.requirement.repository.CompanyRequirementRepository;
import com.careerflux.security.access.AccessGuard;
import com.careerflux.security.access.AccessScope;
import com.careerflux.shortlist.domain.PlacementStage;
import com.careerflux.shortlist.repository.ShortlistRepository;
import com.careerflux.user.Permission;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which students in this college are technically relevant to what a company
 * asked for.
 *
 * <p>The direction is the mirror of job matching — one role against many
 * students rather than one student against many roles — but the arithmetic is
 * the same arithmetic. {@link MatchScorer} is used unchanged, through
 * {@link ScorableRole}, so a requirement and a posting are scored by one
 * engine. There is no second formula here, and there must never be one.
 *
 * <p><b>Nobody is filtered out for failing a company's condition.</b> A student
 * below the stated CGPA still appears, ranked on merit, with the condition
 * named. Hiding them is the failure mode this feature exists to prevent: the
 * technically strongest candidate in a department disappearing behind a crude
 * academic filter before anyone looked at their skills.
 *
 * <p>Read-only. Discovery writes nothing — no match rows, no shortlist, no
 * notifications. Running it twice changes nothing.
 */
@Service
public class CandidateDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(CandidateDiscoveryService.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final CompanyRequirementRepository requirements;
    private final UserRepository users;
    private final CandidateProfileRepository profiles;
    private final CandidateSkillRepository candidateSkills;
    private final CandidatePreferenceValueRepository preferences;
    private final MatchScorer scorer;
    private final AccessGuard accessGuard;
    private final DiscoveryScope discoveryScope;
    private final ShortlistRepository shortlists;

    public CandidateDiscoveryService(CompanyRequirementRepository requirements,
                                     UserRepository users,
                                     CandidateProfileRepository profiles,
                                     CandidateSkillRepository candidateSkills,
                                     CandidatePreferenceValueRepository preferences,
                                     MatchScorer scorer,
                                     AccessGuard accessGuard,
                                     DiscoveryScope discoveryScope,
                                     ShortlistRepository shortlists) {
        this.requirements = requirements;
        this.users = users;
        this.profiles = profiles;
        this.candidateSkills = candidateSkills;
        this.preferences = preferences;
        this.scorer = scorer;
        this.accessGuard = accessGuard;
        this.discoveryScope = discoveryScope;
        this.shortlists = shortlists;
    }

    /**
     * @param eligibility  optional filter; null keeps every student
     * @param minimumScore optional floor on technical compatibility
     * @param sort         {@code match} (default), {@code eligibility} or {@code experience}
     */
    @Transactional(readOnly = true)
    public CandidatePage discover(UUID requirementId, String eligibility, Integer minimumScore,
                                  Boolean shortlisted, String sort, int page, int size) {
        accessGuard.requirePermission(Permission.STUDENT_READ_SCOPED);
        UUID institutionId = accessGuard.requireInstitutionId();
        AccessScope scope = accessGuard.scope();

        // Loaded by id AND institution, so a requirement belonging to another
        // college is never in memory to be discovered against.
        CompanyRequirement requirement = requirements
                .findByIdAndInstitutionId(requirementId, institutionId)
                .orElseThrow(() -> NotFoundException.of("Company requirement", requirementId));

        if (requirement.getStatus() != RequirementStatus.OPEN) {
            throw new BadRequestException("Candidates can only be discovered for an open "
                    + "requirement. This one is " + requirement.getStatus().name().toLowerCase(Locale.ROOT)
                    + ".");
        }

        DiscoveryScope.Criteria criteria =
                discoveryScope.criteriaFor(requirement, institutionId, scope);
        ScorableRole role = ScorableRoles.of(requirement);

        // One lookup for the whole page rather than a query per row.
        // Who is on the list and how far each has got, in one read. The
        // keys are exactly the old id set, so everything that asked
        // "is this candidate shortlisted" still asks the same question.
        Map<UUID, PlacementStage> onShortlist = stagesFor(requirementId);
        List<CandidateProfile> cohort = criteria.empty() ? List.of()
                : profiles.findForDiscoveryScoped(criteria.institutionId(),
                        criteria.allDepartments(), criteria.departmentIds(),
                        criteria.anyBatch(), criteria.graduationYear());
        List<CandidateView> scored = score(cohort, requirement, role, onShortlist);

        List<CandidateView> filtered = scored.stream()
                .filter(candidate -> eligibility == null || eligibility.isBlank()
                        || candidate.eligibility().equalsIgnoreCase(eligibility.strip()))
                .filter(candidate -> minimumScore == null
                        || (candidate.compatibility() != null
                            && candidate.compatibility() >= minimumScore))
                .filter(candidate -> shortlisted == null
                        || candidate.shortlisted() == shortlisted)
                .sorted(comparator(sort))
                .toList();

        int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        int from = Math.min(pageNumber * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        int totalPages = (int) Math.ceil((double) filtered.size() / pageSize);

        log.info("Discovery for requirement {}: {} students in scope, {} after filters",
                requirementId, cohort.size(), filtered.size());

        return new CandidatePage(
                requirement.getId(),
                requirement.getCompanyName(),
                requirement.getRoleTitle(),
                requirement.getStatus().name(),
                namesOf(role, SkillRequirement.REQUIRED),
                namesOf(role, SkillRequirement.PREFERRED),
                requirement.getDepartments().stream().map(Department::getName).sorted().toList(),
                requirement.getGraduationYear(),
                requirement.getMinCgpa(),
                scopeLabel(scope),
                cohort.size(),
                // Whether this requirement's cohort has any verified CGPA at
                // all, so the screen can explain a page full of UNKNOWNs once
                // rather than on every row.
                scored.stream().anyMatch(candidate -> candidate.cgpa() != null),
                onShortlist.size(),
                filtered.subList(from, to),
                pageNumber, pageSize, filtered.size(), totalPages);
    }

    // ----------------------------------------------------------------- scope

    /**
     * The shortlist, scored the same way discovery scores.
     *
     * <p>Separate from {@link #discover} for one reason: a shortlist stays
     * readable after the requirement closes. It is the record of what the
     * placement team decided, and a closed drive's list is exactly when someone
     * wants to look back at it. Discovery refuses a closed requirement because
     * searching for new candidates against one makes no sense; reading the
     * decision already made does.
     *
     * <p>The figures shown are today's, not the ones at the moment of
     * shortlisting. Nothing is frozen into the shortlist, so a student whose
     * profile has improved shows the better number — and one whose eligibility
     * has changed shows that too, without being removed. A human put them on
     * the list; only a human takes them off.
     */
    @Transactional(readOnly = true)
    public CandidatePage shortlist(UUID requirementId) {
        accessGuard.requirePermission(Permission.PLACEMENT_DRIVE_VIEW);
        UUID institutionId = accessGuard.requireInstitutionId();
        AccessScope scope = accessGuard.scope();

        CompanyRequirement requirement = requirements
                .findByIdAndInstitutionId(requirementId, institutionId)
                .orElseThrow(() -> NotFoundException.of("Company requirement", requirementId));

        // Who is on the list and how far each has got, in one read. The
        // keys are exactly the old id set, so everything that asked
        // "is this candidate shortlisted" still asks the same question.
        Map<UUID, PlacementStage> onShortlist = stagesFor(requirementId);

        // Narrowed to what this caller may see. A coordinator opening a
        // shortlist their officer built sees the part of it inside their own
        // department, not the whole college's.
        //
        // A shortlist is tens of people, not thousands, so loading it by id is
        // the right shape here — the parameter-count problem that drove
        // discovery onto scoped predicates does not arise at this size.
        Set<UUID> visible = new LinkedHashSet<>(
                discoveryScope.studentIds(requirement, institutionId, scope));

        List<CandidateProfile> cohort = onShortlist.isEmpty() ? List.of()
                : profiles.findAllById(onShortlist.keySet()).stream()
                        .filter(profile -> visible.contains(profile.getUser().getId()))
                        .toList();

        ScorableRole role = ScorableRoles.of(requirement);
        List<CandidateView> scored = score(cohort, requirement, role, onShortlist).stream()
                .sorted(comparator("match"))
                .toList();

        return new CandidatePage(
                requirement.getId(),
                requirement.getCompanyName(),
                requirement.getRoleTitle(),
                requirement.getStatus().name(),
                namesOf(role, SkillRequirement.REQUIRED),
                namesOf(role, SkillRequirement.PREFERRED),
                requirement.getDepartments().stream().map(Department::getName).sorted().toList(),
                requirement.getGraduationYear(),
                requirement.getMinCgpa(),
                scopeLabel(scope),
                cohort.size(),
                scored.stream().anyMatch(candidate -> candidate.cgpa() != null),
                onShortlist.size(),
                scored,
                0, scored.size(), scored.size(), scored.isEmpty() ? 0 : 1);
    }

    // ---------------------------------------------------------------- scoring

    /**
     * Scores the cohort.
     *
     * <p>Four queries for the whole cohort rather than five per student. The
     * per-candidate path {@code MatchingService.snapshot} takes is correct for
     * one student and would be ten thousand queries for two thousand, so the
     * skills and preferences are loaded in bulk and the snapshots assembled in
     * memory.
     */
    /** Candidate id to current stage, for the candidates on this requirement. */
    private Map<UUID, PlacementStage> stagesFor(UUID requirementId) {
        Map<UUID, PlacementStage> stages = new LinkedHashMap<>();
        for (Object[] row : shortlists.findCandidateStages(requirementId)) {
            stages.put((UUID) row[0], (PlacementStage) row[1]);
        }
        return stages;
    }

    /** Null for somebody nobody shortlisted: they are not in the workflow. */
    private static String stageNameOf(PlacementStage stage) {
        return stage == null ? null : stage.name();
    }

    private List<CandidateView> score(List<CandidateProfile> found, CompanyRequirement requirement,
                                      ScorableRole role, Map<UUID, PlacementStage> onShortlist) {
        if (found.isEmpty()) {
            return List.of();
        }

        List<UUID> profileIds = found.stream().map(CandidateProfile::getId).toList();

        Map<UUID, Set<String>> skillsByCandidate = candidateSkills.findByCandidateIdIn(profileIds)
                .stream()
                .collect(Collectors.groupingBy(skill -> skill.getCandidate().getId(),
                        Collectors.mapping(skill -> skill.getSkill().getSlug(),
                                Collectors.toCollection(LinkedHashSet::new))));

        Map<UUID, List<CandidatePreferenceValue>> preferencesByCandidate =
                preferences.findByCandidateIdIn(profileIds).stream()
                        .collect(Collectors.groupingBy(value -> value.getCandidate().getId()));

        List<CandidateView> results = new ArrayList<>(found.size());
        for (CandidateProfile profile : found) {
            CandidateSnapshot snapshot = snapshotOf(profile,
                    skillsByCandidate.getOrDefault(profile.getId(), Set.of()),
                    preferencesByCandidate.getOrDefault(profile.getId(), List.of()));

            MatchScorer.Scorecard card = scorer.score(snapshot, role);
            // No verified CGPA exists, so this is null for everyone today. It is
            // passed rather than assumed so the day a real one lands, the rule
            // starts working without touching this class.
            FormalEligibility.Outcome outcome =
                    FormalEligibility.evaluate(card, requirement, verifiedCgpa(profile));

            results.add(toView(profile, card, outcome, role,
                    onShortlist.containsKey(profile.getId()),
                    stageNameOf(onShortlist.get(profile.getId()))));
        }
        return results;
    }

    /**
     * The same snapshot the job side uses, assembled from pre-loaded rows.
     *
     * <p>Deliberately not a second candidate representation: one shape feeds
     * both directions, so a change to what "the candidate" means cannot apply
     * to one and not the other.
     */
    private CandidateSnapshot snapshotOf(CandidateProfile profile, Set<String> skillSlugs,
                                         List<CandidatePreferenceValue> values) {
        List<String> targetRoles = valuesOf(values, PreferenceType.TARGET_ROLE);
        Set<String> locations = new LinkedHashSet<>(valuesOf(values, PreferenceType.LOCATION));

        Set<WorkMode> workModes = valuesOf(values, PreferenceType.WORK_MODE).stream()
                .map(value -> parseEnum(WorkMode.class, value))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(WorkMode.class)));

        Set<EmploymentType> employmentTypes = valuesOf(values, PreferenceType.EMPLOYMENT_TYPE).stream()
                .map(value -> parseEnum(EmploymentType.class, value))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(EmploymentType.class)));

        var candidatePreferences = profile.getPreferences();
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
                candidatePreferences != null && candidatePreferences.isOpenToRelocation(),
                false,
                false);
    }

    /**
     * A verified numeric CGPA, or null.
     *
     * <p>Verified means an institution recorded it. A student's own figure is
     * kept on their profile and shown back to them, and is not consulted here —
     * a student must not be able to answer a company's stated minimum about
     * themselves.
     *
     * <p>Nothing is parsed or inferred. {@code CandidateEducation.grade} is
     * still free text — it may hold "8.1", "First Class", "82%" or nothing — and
     * it is never read: a hiring bar decided from a string like that would put a
     * student's place on a drive on a guess.
     */
    private BigDecimal verifiedCgpa(CandidateProfile profile) {
        return profile.getVerifiedCgpa();
    }

    // ------------------------------------------------------------------ views

    private CandidateView toView(CandidateProfile profile, MatchScorer.Scorecard card,
                                 FormalEligibility.Outcome outcome, ScorableRole role,
                                 boolean shortlisted,
                                 String placementStage) {
        User user = profile.getUser();

        // Preferred skills the candidate does have: the tier's full list minus
        // what the scorer reported as missing. Derived rather than recomputed so
        // it cannot disagree with the score.
        //
        // Only when the skills dimension was actually compared. If the scorer
        // could not compare — the candidate lists no skills, or the role lists
        // none — it reports nothing missing, and reading that as "everything
        // matched" would credit a student with skills nobody has recorded. An
        // absence of data is not an absence of gaps.
        boolean skillsCompared = card.dimensions().containsKey(MatchDimension.SKILLS)
                && card.dimensions().get(MatchDimension.SKILLS).isKnown();
        List<String> matchedPreferred = new ArrayList<>();
        if (skillsCompared) {
            for (RoleSkill skill : role.skills()) {
                if (skill.tier() == SkillRequirement.PREFERRED
                        && !card.missingPreferredSkills().contains(skill.canonicalName())) {
                    matchedPreferred.add(skill.canonicalName());
                }
            }
        }

        List<DimensionView> dimensions = card.dimensions().entrySet().stream()
                .map(entry -> new DimensionView(entry.getKey().name(),
                        entry.getValue().score(),
                        entry.getValue().isKnown() ? null
                                : String.valueOf(entry.getValue().unknown())))
                .toList();

        List<ReasonView> strengths = componentsOf(card, "STRENGTH");
        List<ReasonView> gaps = componentsOf(card, "GAP");

        return new CandidateView(
                profile.getId(),
                user.getId(),
                user.getFullName(),
                user.getDepartment() == null ? null : user.getDepartment().getName(),
                user.getBatch() == null ? null : user.getBatch().getName(),
                verifiedCgpa(profile),
                card.compatibility(),
                card.confidence().level().name(),
                card.confidence().coveragePercent(),
                outcome.status().name(),
                outcome.reasons(),
                card.matchedRequiredSkills(),
                card.missingRequiredSkills(),
                matchedPreferred,
                card.missingPreferredSkills(),
                profile.getYearsExperience(),
                profile.getPrimaryRole(),
                dimensions,
                strengths,
                gaps,
                shortlisted,
                placementStage);
    }

    /** The scorer's own words. Nothing here is composed by the client. */
    private List<ReasonView> componentsOf(MatchScorer.Scorecard card, String kind) {
        List<ReasonView> found = new ArrayList<>();
        for (MatchComponent component : card.components()) {
            if (component.getKind() != null && component.getKind().name().equals(kind)) {
                found.add(new ReasonView(kind,
                        component.getDimension() == null ? null : component.getDimension().name(),
                        component.getLabel(), component.getDetail()));
            }
        }
        return found;
    }

    // --------------------------------------------------------------- helpers

    private static Comparator<CandidateView> comparator(String sort) {
        Comparator<CandidateView> byMatch = Comparator.comparing(
                CandidateView::compatibility, Comparator.nullsLast(Comparator.reverseOrder()));
        if (sort == null || sort.isBlank() || sort.equalsIgnoreCase("match")) {
            return byMatch;
        }
        if (sort.equalsIgnoreCase("experience")) {
            return Comparator.comparing(CandidateView::yearsExperience,
                    Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(byMatch);
        }
        if (sort.equalsIgnoreCase("eligibility")) {
            // Eligible first, but only as an ordering. Nobody is removed, which
            // is the difference between surfacing a constraint and enforcing it.
            return Comparator.comparingInt(
                    (CandidateView view) -> eligibilityRank(view.eligibility())).thenComparing(byMatch);
        }
        return byMatch;
    }

    private static int eligibilityRank(String status) {
        return switch (EligibilityStatus.valueOf(status)) {
            case ELIGIBLE -> 0;
            case ELIGIBLE_WITH_GAPS -> 1;
            case UNKNOWN -> 2;
            case NOT_ELIGIBLE -> 3;
        };
    }

    private static List<String> namesOf(ScorableRole role, SkillRequirement tier) {
        return role.skills().stream()
                .filter(skill -> skill.tier() == tier)
                .map(RoleSkill::canonicalName)
                .sorted()
                .toList();
    }

    private static List<String> valuesOf(List<CandidatePreferenceValue> values, PreferenceType type) {
        return values.stream()
                .filter(value -> value.getValueType() == type)
                .map(CandidatePreferenceValue::getValue)
                .toList();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private String scopeLabel(AccessScope scope) {
        return scope.seesWholeInstitution() ? "Whole institution" : "Your department scope";
    }
}
