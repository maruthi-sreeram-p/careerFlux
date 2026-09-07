package com.careerflux.institution.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.CandidateSkill;
import com.careerflux.candidate.domain.Resume;
import com.careerflux.candidate.repository.CandidatePreferenceValueRepository;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.repository.ResumeRepository;
import com.careerflux.common.TextUtils;
import com.careerflux.engagement.domain.InteractionType;
import com.careerflux.engagement.domain.JobInteraction;
import com.careerflux.engagement.repository.JobInteractionRepository;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.institution.dto.InstitutionDtos.BatchView;
import com.careerflux.institution.dto.InstitutionDtos.DepartmentView;
import com.careerflux.institution.dto.InstitutionDtos.ScopeView;
import com.careerflux.institution.dto.InstitutionDtos.StudentActivityEntry;
import com.careerflux.institution.dto.InstitutionDtos.StudentDetail;
import com.careerflux.institution.dto.InstitutionDtos.StudentPage;
import com.careerflux.institution.dto.InstitutionDtos.StudentPlacement;
import com.careerflux.institution.dto.InstitutionDtos.StudentSkillRef;
import com.careerflux.institution.dto.InstitutionDtos.StudentRow;
import com.careerflux.institution.repository.BatchRepository;
import com.careerflux.institution.repository.DepartmentRepository;
import com.careerflux.institution.repository.InstitutionRepository;
import com.careerflux.security.access.AccessGuard;
import com.careerflux.shortlist.domain.PlacementStageChange;
import com.careerflux.shortlist.domain.ShortlistEntry;
import com.careerflux.shortlist.repository.PlacementStageChangeRepository;
import com.careerflux.shortlist.repository.ShortlistRepository;
import com.careerflux.security.access.AccessScope;
import com.careerflux.user.Permission;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The student directory, as staff see it.
 *
 * <p>Every read here is filtered twice, on purpose. The list query is scoped in
 * SQL, so a coordinator's page never contains a student they may not see. The
 * single-student read then re-checks through {@link AccessGuard}, so guessing an
 * id gets you nothing either. Belt and braces is the right posture here: the
 * list filter is an efficiency, the guard is the guarantee.
 */
@Service
public class StudentDirectoryService {

    private static final int MAX_PAGE_SIZE = 100;

    private static final BigDecimal TEN = BigDecimal.TEN;

    /** Sorts below every real score, so unknown values land last on any database. */
    private static final BigDecimal UNRANKED = new BigDecimal("-1");

    /** Declared to LIKE so a typed % or _ cannot widen its own search. */
    private static final char LIKE_ESCAPE = '!';

    /** Stands in for an empty set, because an empty JPQL in-clause is not portable. */
    private static final UUID MATCHES_NOTHING = new UUID(0L, 0L);

    private final UserRepository userRepository;
    private final CandidateProfileRepository profileRepository;
    private final ResumeRepository resumeRepository;
    private final DepartmentRepository departmentRepository;
    private final BatchRepository batchRepository;
    private final InstitutionRepository institutionRepository;
    private final CandidatePreferenceValueRepository preferenceRepository;
    private final ShortlistRepository shortlistRepository;
    private final PlacementStageChangeRepository stageChangeRepository;
    private final JobInteractionRepository interactionRepository;
    private final AccessGuard accessGuard;

    public StudentDirectoryService(UserRepository userRepository,
                                   CandidateProfileRepository profileRepository,
                                   ResumeRepository resumeRepository,
                                   DepartmentRepository departmentRepository,
                                   BatchRepository batchRepository,
                                   InstitutionRepository institutionRepository,
                                   CandidatePreferenceValueRepository preferenceRepository,
                                   ShortlistRepository shortlistRepository,
                                   PlacementStageChangeRepository stageChangeRepository,
                                   JobInteractionRepository interactionRepository,
                                   AccessGuard accessGuard) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.resumeRepository = resumeRepository;
        this.departmentRepository = departmentRepository;
        this.batchRepository = batchRepository;
        this.institutionRepository = institutionRepository;
        this.preferenceRepository = preferenceRepository;
        this.shortlistRepository = shortlistRepository;
        this.stageChangeRepository = stageChangeRepository;
        this.interactionRepository = interactionRepository;
        this.accessGuard = accessGuard;
    }

    /**
     * What a placement officer asked for, as values rather than parameters.
     *
     * <p>Every field is optional. An officer who sets nothing gets the directory
     * they had before, which is what keeps the old two-parameter call honest.
     */
    public record StudentFilter(
            String query,
            UUID departmentId,
            UUID batchId,
            Double minCgpa,
            List<String> skillSlugs,
            Integer minProfileCompleteness,
            Boolean resumeUploaded) {

        public StudentFilter {
            skillSlugs = skillSlugs == null ? List.of() : List.copyOf(skillSlugs);
        }

        public static StudentFilter none() {
            return new StudentFilter(null, null, null, null, List.of(), null, null);
        }
    }

    @Transactional(readOnly = true)
    public StudentPage students(int page, int size) {
        return students(StudentFilter.none(), page, size, null);
    }

    @Transactional(readOnly = true)
    public StudentPage students(StudentFilter filter, int page, int size, String sort) {
        accessGuard.requirePermission(Permission.STUDENT_READ_SCOPED);
        UUID institutionId = accessGuard.requireInstitutionId();
        AccessScope scope = accessGuard.scope();

        int pageNumber = Math.max(page, 0);
        int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);

        // A coordinator with no grants sees nobody. That default is stated here
        // rather than left to an in-clause, so the intent is visible.
        if (scope.isEmpty()) {
            return new StudentPage(List.of(), pageNumber, pageSize, 0, 0);
        }

        Pageable pageable = PageRequest.of(pageNumber, pageSize, sortOf(sort));
        Page<User> students = userRepository.findAll(
                specificationFor(filter, institutionId, scope, sort), pageable);

        // Three queries for the page rather than two per row. toRow used to
        // fetch each student's profile and count their resumes individually,
        // which is invisible at a page of five and fifty statements at
        // twenty-five. Filtering moved into the query above rather than into
        // this loop, so the count and the page still agree once a filter is set.
        List<UUID> userIds = students.getContent().stream().map(User::getId).toList();
        Map<UUID, CandidateProfile> profilesByUser = userIds.isEmpty() ? Map.of()
                : profileRepository.findForDiscovery(userIds).stream()
                        .collect(Collectors.toMap(profile -> profile.getUser().getId(),
                                profile -> profile));
        Set<UUID> withResume = profilesByUser.isEmpty() ? Set.of()
                : new HashSet<>(resumeRepository.findCandidateIdsWithResume(
                        profilesByUser.values().stream().map(CandidateProfile::getId).toList()));

        List<StudentRow> rows = students.getContent().stream()
                .map(user -> toRow(user, profilesByUser.get(user.getId()), withResume))
                .toList();
        return new StudentPage(rows, students.getNumber(), students.getSize(),
                students.getTotalElements(), students.getTotalPages());
    }

    /**
     * Name ascending unless asked otherwise.
     *
     * <p>CGPA and profile completion live on the candidate profile, which the
     * User entity does not map a path to, so they cannot be expressed as a
     * {@code Sort} over this root. Those two are ordered inside the
     * specification instead; here they only suppress the default.
     */
    private Sort sortOf(String sort) {
        return ordersInsideTheQuery(sort) ? Sort.unsorted() : Sort.by(Sort.Direction.ASC, "fullName");
    }

    private static boolean ordersInsideTheQuery(String sort) {
        String requested = sort == null ? "" : sort.toLowerCase(Locale.ROOT).strip();
        return requested.equals("cgpa") || requested.equals("profile");
    }

    /**
     * The whole directory query: tenant, scope, then whatever was asked for.
     *
     * <p>Each filter is its own deliberate predicate. The three that live on
     * related tables — skills, resumes, and everything on the candidate profile —
     * are correlated subqueries rather than joins, because a join to a
     * to-many association returns one row per match and would list a student with
     * two resumes twice. A subquery cannot do that, and it also leaves the count
     * query correct, which a {@code distinct} on a join does not reliably do.
     */
    private Specification<User> specificationFor(StudentFilter filter, UUID institutionId,
                                                 AccessScope scope, String sort) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Tenant and role first. These are the guarantees; everything after
            // them only narrows.
            predicates.add(builder.equal(root.get("institution").get("id"), institutionId));
            predicates.add(builder.equal(root.get("role"), UserRole.STUDENT));

            if (!scope.seesWholeInstitution()) {
                // Exactly the rule the previous JPQL stated: granted department
                // OR granted batch. A student with neither stays invisible to
                // coordinators rather than visible to all of them.
                predicates.add(builder.or(
                        root.get("department").get("id").in(nonEmpty(scope.departmentIds())),
                        root.get("batch").get("id").in(nonEmpty(scope.batchIds()))));
            }

            if (TextUtils.hasText(filter.query())) {
                predicates.add(searchPredicate(filter.query(), root, query, builder));
            }
            if (filter.departmentId() != null) {
                predicates.add(builder.equal(root.get("department").get("id"), filter.departmentId()));
            }
            if (filter.batchId() != null) {
                predicates.add(builder.equal(root.get("batch").get("id"), filter.batchId()));
            }
            if (filter.minCgpa() != null) {
                predicates.add(root.get("id").in(atLeastCgpa(filter.minCgpa(), query, builder)));
            }
            if (filter.minProfileCompleteness() != null) {
                predicates.add(root.get("id").in(
                        atLeastComplete(filter.minProfileCompleteness(), query, builder)));
            }
            // One subquery per skill, so several skills mean ALL of them. An IN
            // over a list of slugs would have meant ANY, which is a different
            // question and a much less useful one for a placement office.
            for (String slug : filter.skillSlugs()) {
                if (TextUtils.hasText(slug)) {
                    predicates.add(root.get("id").in(withSkill(slug, query, builder)));
                }
            }
            if (filter.resumeUploaded() != null) {
                Subquery<UUID> uploaded = withResume(query, builder);
                predicates.add(filter.resumeUploaded()
                        ? root.get("id").in(uploaded)
                        : builder.not(root.get("id").in(uploaded)));
            }

            // Ordering belongs on the result query only; a count query with an
            // ORDER BY over a subquery is invalid.
            boolean isCountQuery = query.getResultType() == Long.class
                    || query.getResultType() == long.class;
            if (ordersInsideTheQuery(sort) && !isCountQuery) {
                String requested = sort.toLowerCase(Locale.ROOT).strip();
                // Coalesced to a value below any real one, so a student with no
                // CGPA — or no profile at all — sorts last on both H2 and
                // PostgreSQL rather than relying on their differing NULL order.
                Expression<BigDecimal> ranked = requested.equals("cgpa")
                        ? builder.coalesce(normalisedCgpa(root, query, builder), UNRANKED)
                        : builder.coalesce(completeness(root, query, builder), UNRANKED);
                query.orderBy(builder.desc(ranked), builder.asc(root.get("fullName")));
            }

            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * One search box, four separate questions.
     *
     * <p>Name, email and roll number are substring matches on the student; the
     * fourth asks the skill taxonomy. Searching "Java" therefore finds people who
     * know Java rather than people called Java, which a single expression over
     * concatenated columns could not distinguish.
     */
    private Predicate searchPredicate(String rawQuery, Root<User> root,
                                      CriteriaQuery<?> query, CriteriaBuilder builder) {
        String needle = "%" + escapeLike(rawQuery.strip().toLowerCase(Locale.ROOT)) + "%";

        Subquery<UUID> bySkill = query.subquery(UUID.class);
        Root<CandidateSkill> skill = bySkill.from(CandidateSkill.class);
        bySkill.select(skill.get("candidate").get("user").get("id"))
                .where(builder.like(builder.lower(skill.get("skill").get("canonicalName")),
                        needle, LIKE_ESCAPE));

        return builder.or(
                builder.like(builder.lower(root.get("fullName")), needle, LIKE_ESCAPE),
                builder.like(builder.lower(root.get("email")), needle, LIKE_ESCAPE),
                builder.like(builder.lower(root.get("rollNumber")), needle, LIKE_ESCAPE),
                root.get("id").in(bySkill));
    }

    /**
     * Students whose CGPA reaches the floor once put on a ten-point scale.
     *
     * <p>Written as {@code cgpa * 10 >= scale * floor} rather than
     * {@code cgpa / scale * 10 >= floor}. The two are equivalent for any positive
     * scale — and the scale is validated positive on the way in — but the
     * multiplied form has no division in it, so there is no rounding to reason
     * about and no way for a bad row to divide by zero. It is also plain
     * arithmetic that H2 and PostgreSQL treat identically.
     *
     * <p>A student with no CGPA is excluded rather than defaulted. Unknown is not
     * zero and it is not "probably fine": nobody can be said to meet a
     * requirement that was never recorded for them.
     */
    private Subquery<UUID> atLeastCgpa(double floor, CriteriaQuery<?> query, CriteriaBuilder builder) {
        Subquery<UUID> qualifying = query.subquery(UUID.class);
        Root<CandidateProfile> profile = qualifying.from(CandidateProfile.class);
        return qualifying.select(profile.get("user").get("id"))
                .where(builder.isNotNull(profile.get("cgpa")),
                        builder.greaterThanOrEqualTo(
                                builder.prod(profile.<BigDecimal>get("cgpa"), TEN),
                                builder.prod(profile.<BigDecimal>get("cgpaScale"),
                                        BigDecimal.valueOf(floor))));
    }

    private Subquery<UUID> atLeastComplete(int threshold, CriteriaQuery<?> query, CriteriaBuilder builder) {
        Subquery<UUID> qualifying = query.subquery(UUID.class);
        Root<CandidateProfile> profile = qualifying.from(CandidateProfile.class);
        return qualifying.select(profile.get("user").get("id"))
                .where(builder.greaterThanOrEqualTo(profile.get("profileCompleteness"), threshold));
    }

    private Subquery<UUID> withSkill(String slug, CriteriaQuery<?> query, CriteriaBuilder builder) {
        Subquery<UUID> holders = query.subquery(UUID.class);
        Root<CandidateSkill> skill = holders.from(CandidateSkill.class);
        return holders.select(skill.get("candidate").get("user").get("id"))
                .where(builder.equal(builder.lower(skill.get("skill").get("slug")),
                        slug.strip().toLowerCase(Locale.ROOT)));
    }

    private Subquery<UUID> withResume(CriteriaQuery<?> query, CriteriaBuilder builder) {
        Subquery<UUID> uploaded = query.subquery(UUID.class);
        Root<Resume> resume = uploaded.from(Resume.class);
        return uploaded.select(resume.get("candidate").get("user").get("id"));
    }

    private Subquery<BigDecimal> normalisedCgpa(Root<User> root, CriteriaQuery<?> query,
                                                CriteriaBuilder builder) {
        Subquery<BigDecimal> score = query.subquery(BigDecimal.class);
        Root<CandidateProfile> profile = score.from(CandidateProfile.class);
        return score.select(builder.quot(
                        builder.prod(profile.<BigDecimal>get("cgpa"), TEN),
                        profile.<BigDecimal>get("cgpaScale")).as(BigDecimal.class))
                .where(builder.equal(profile.get("user").get("id"), root.get("id")));
    }

    private Subquery<BigDecimal> completeness(Root<User> root, CriteriaQuery<?> query,
                                              CriteriaBuilder builder) {
        Subquery<BigDecimal> score = query.subquery(BigDecimal.class);
        Root<CandidateProfile> profile = score.from(CandidateProfile.class);
        return score.select(profile.get("profileCompleteness").as(BigDecimal.class))
                .where(builder.equal(profile.get("user").get("id"), root.get("id")));
    }

    /** Neutralises wildcards the caller typed, so "%" cannot match everyone. */
    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /**
     * One student by id.
     *
     * <p>This is the route an id-tampering attempt goes through, so it does not
     * trust the caller's page having contained the student. It loads the record
     * and asks the guard, which answers not-found for anything outside the
     * caller's institution or scope.
     */
    @Transactional(readOnly = true)
    public StudentRow student(UUID userId) {
        accessGuard.requirePermission(Permission.STUDENT_READ_SCOPED);

        CandidateProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> NotFoundException.of("Student", userId));
        accessGuard.requireCanReadCandidate(profile);
        // One student, so the "batch" is a set of one.
        Set<UUID> withResume = new HashSet<>(
                resumeRepository.findCandidateIdsWithResume(List.of(profile.getId())));
        return toRow(profile.getUser(), profile, withResume);
    }

    /**
     * One student in full, for the placement officer's detail view.
     *
     * <p>Goes through the same guard as {@link #student(UUID)}: an id from
     * outside the caller's institution or scope answers not-found, so the extra
     * detail here widens what a permitted caller sees and not who may call.
     *
     * <p>What is deliberately absent: the password hash and every other
     * credential, the phone number, and the contents of the resume. Whether the
     * caller may open the resume is a separate permission on a separate route,
     * and that has not changed.
     */
    @Transactional(readOnly = true)
    public StudentDetail studentDetail(UUID userId) {
        accessGuard.requirePermission(Permission.STUDENT_READ_SCOPED);

        CandidateProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> NotFoundException.of("Student", userId));
        accessGuard.requireCanReadCandidate(profile);

        Set<UUID> withResume = new HashSet<>(
                resumeRepository.findCandidateIdsWithResume(List.of(profile.getId())));
        StudentRow summary = toRow(profile.getUser(), profile, withResume);

        List<StudentSkillRef> skills = profile.getSkills().stream()
                .map(held -> new StudentSkillRef(held.getSkill().getId(),
                        held.getSkill().getCanonicalName(), held.getSkill().getSlug()))
                .sorted(Comparator.comparing(StudentSkillRef::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<String> preferences = preferenceRepository
                .findByCandidateIdOrderByValueTypeAscDisplayOrderAsc(profile.getId()).stream()
                .map(value -> value.getValueType().name() + ": " + value.getValue())
                .toList();

        List<ShortlistEntry> shortlists = shortlistRepository.findForCandidate(profile.getId());
        List<StudentPlacement> placements = shortlists.stream()
                .map(entry -> new StudentPlacement(
                        entry.getRequirement().getId(),
                        entry.getRequirement().getCompanyName(),
                        entry.getRequirement().getRoleTitle(),
                        entry.getStage().name(),
                        entry.getStageChangedAt()))
                .toList();

        return new StudentDetail(summary, profile.getHeadline(), profile.getLocation(),
                profile.getCgpa(), profile.getCgpaScale(), normalise(profile),
                profile.getCgpaSource() == null ? null : profile.getCgpaSource().name(),
                skills, preferences, placements, activityFor(profile, shortlists));
    }

    /**
     * The student's CGPA on a ten-point scale, or null when there isn't one.
     *
     * <p>Computed for display only. The stored value and the institution's own
     * scale are returned alongside it, so the officer sees "4.00 / 5" as well as
     * the 8.00 the filters compare against.
     */
    private BigDecimal normalise(CandidateProfile profile) {
        if (profile.getCgpa() == null || profile.getCgpaScale() == null
                || profile.getCgpaScale().signum() <= 0) {
            return null;
        }
        return profile.getCgpa().multiply(TEN)
                .divide(profile.getCgpaScale(), 2, RoundingMode.HALF_UP);
    }

    /**
     * A placement-relevant timeline, composed from records that already exist.
     *
     * <p>Three sources: resumes, the student's own job interactions, and the
     * stage changes on their shortlist entries. No new table, and no
     * {@code AuditEvent} — those are keyed by actor and entity rather than by
     * student, and replaying everything the platform recorded about a person is
     * surveillance rather than placement work.
     *
     * <p>Dismissals are left out for the same reason. A student deciding a job is
     * not for them is theirs to decide, and an officer does not need the list.
     */
    private List<StudentActivityEntry> activityFor(CandidateProfile profile,
                                                   List<ShortlistEntry> shortlists) {
        List<StudentActivityEntry> entries = new ArrayList<>();

        for (Resume resume : resumeRepository.findByCandidateIdOrderByUploadedAtDesc(profile.getId())) {
            entries.add(new StudentActivityEntry(resume.getUploadedAt(), "RESUME_UPLOADED",
                    "Resume uploaded" + (TextUtils.hasText(resume.getOriginalFilename())
                            ? ": " + resume.getOriginalFilename() : "")));
        }

        for (JobInteraction interaction
                : interactionRepository.findByCandidateIdOrderByCreatedAtDesc(profile.getId())) {
            if (interaction.getInteractionType() == InteractionType.DISMISSED) {
                continue;
            }
            String title = interaction.getJob() == null ? "a job" : interaction.getJob().getTitle();
            entries.add(new StudentActivityEntry(
                    interaction.getInteractionType() == InteractionType.APPLIED
                            && interaction.getAppliedAt() != null
                            ? interaction.getAppliedAt() : interaction.getCreatedAt(),
                    "JOB_" + interaction.getInteractionType().name(),
                    switch (interaction.getInteractionType()) {
                        case APPLIED -> "Applied to " + title;
                        case SAVED -> "Saved " + title;
                        default -> "Viewed " + title;
                    }));
        }

        for (ShortlistEntry shortlist : shortlists) {
            String where = shortlist.getRequirement().getCompanyName()
                    + " — " + shortlist.getRequirement().getRoleTitle();
            for (PlacementStageChange change
                    : stageChangeRepository.findByShortlistIdOrderByOccurredAtAsc(shortlist.getId())) {
                entries.add(new StudentActivityEntry(change.getOccurredAt(),
                        "PLACEMENT_" + change.getToStage().name(),
                        (change.getFromStage() == null
                                ? "Shortlisted for " + where
                                : change.getFromStage().name() + " → " + change.getToStage().name()
                                        + " for " + where)));
            }
        }

        // Newest first, and nulls last so an undated row cannot claim the top.
        entries.sort(Comparator.comparing(StudentActivityEntry::at,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(entries);
    }

    @Transactional(readOnly = true)
    public List<DepartmentView> departments() {
        UUID institutionId = accessGuard.requireInstitutionId();
        return departmentRepository.findByInstitutionIdOrderByNameAsc(institutionId).stream()
                .map(department -> new DepartmentView(
                        department.getId(), department.getName(), department.getCode(), 0L))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BatchView> batches() {
        UUID institutionId = accessGuard.requireInstitutionId();
        return batchRepository.findByInstitutionIdOrderByGraduationYearDescNameAsc(institutionId).stream()
                .map(batch -> new BatchView(batch.getId(), batch.getName(), batch.getGraduationYear(), 0L))
                .toList();
    }

    /**
     * What the caller may see, in their own words.
     *
     * <p>Worth having as a first-class endpoint: a coordinator shown four
     * students should be able to discover that this is because they are scoped to
     * one department, rather than concluding the college has four students.
     */
    @Transactional(readOnly = true)
    public ScopeView myScope() {
        UUID institutionId = accessGuard.requireInstitutionId();
        AccessScope scope = accessGuard.scope();

        String institutionName = institutionRepository.findById(institutionId)
                .map(institution -> institution.getName())
                .orElse("Unknown institution");

        List<String> departments = scope.departmentIds().stream()
                .map(id -> departmentRepository.findByIdAndInstitutionId(id, institutionId)
                        .map(department -> department.getName())
                        .orElse("Removed department"))
                .sorted()
                .toList();
        List<String> batches = scope.batchIds().stream()
                .map(id -> batchRepository.findByIdAndInstitutionId(id, institutionId)
                        .map(batch -> batch.getName())
                        .orElse("Removed batch"))
                .sorted()
                .toList();

        List<String> permissions = scope.role().getPermissions().stream()
                .map(Enum::name)
                .sorted()
                .toList();

        return new ScopeView(institutionId, institutionName, scope.role().name(),
                scope.seesWholeInstitution(), departments, batches, permissions);
    }

    /** Built from rows already loaded for the whole page, never per student. */
    private StudentRow toRow(User user, CandidateProfile profile, Set<UUID> withResume) {
        return new StudentRow(
                user.getId(),
                profile == null ? null : profile.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRollNumber(),
                user.getDepartment() == null ? null : user.getDepartment().getName(),
                user.getBatch() == null ? null : user.getBatch().getName(),
                profile == null ? null : profile.getPrimaryRole(),
                profile == null ? "NOT_STARTED" : profile.getOnboardingStage().name(),
                profile == null ? 0 : profile.getProfileCompleteness(),
                profile != null && withResume.contains(profile.getId()),
                profile == null ? null : profile.getCgpa(),
                profile == null ? null : profile.getCgpaScale(),
                profile == null ? null : normalise(profile),
                user.getCreatedAt());
    }

    private static Set<UUID> nonEmpty(Set<UUID> ids) {
        return ids.isEmpty() ? Set.of(MATCHES_NOTHING) : ids;
    }
}
