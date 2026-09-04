package com.careerflux.institution.service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.repository.ResumeRepository;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.institution.dto.InstitutionDtos.BatchView;
import com.careerflux.institution.dto.InstitutionDtos.DepartmentView;
import com.careerflux.institution.dto.InstitutionDtos.ScopeView;
import com.careerflux.institution.dto.InstitutionDtos.StudentPage;
import com.careerflux.institution.dto.InstitutionDtos.StudentRow;
import com.careerflux.institution.repository.BatchRepository;
import com.careerflux.institution.repository.DepartmentRepository;
import com.careerflux.institution.repository.InstitutionRepository;
import com.careerflux.security.access.AccessGuard;
import com.careerflux.security.access.AccessScope;
import com.careerflux.user.Permission;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    /** Stands in for an empty set, because an empty JPQL in-clause is not portable. */
    private static final UUID MATCHES_NOTHING = new UUID(0L, 0L);

    private final UserRepository userRepository;
    private final CandidateProfileRepository profileRepository;
    private final ResumeRepository resumeRepository;
    private final DepartmentRepository departmentRepository;
    private final BatchRepository batchRepository;
    private final InstitutionRepository institutionRepository;
    private final AccessGuard accessGuard;

    public StudentDirectoryService(UserRepository userRepository,
                                   CandidateProfileRepository profileRepository,
                                   ResumeRepository resumeRepository,
                                   DepartmentRepository departmentRepository,
                                   BatchRepository batchRepository,
                                   InstitutionRepository institutionRepository,
                                   AccessGuard accessGuard) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.resumeRepository = resumeRepository;
        this.departmentRepository = departmentRepository;
        this.batchRepository = batchRepository;
        this.institutionRepository = institutionRepository;
        this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public StudentPage students(int page, int size) {
        accessGuard.requirePermission(Permission.STUDENT_READ_SCOPED);
        UUID institutionId = accessGuard.requireInstitutionId();
        AccessScope scope = accessGuard.scope();

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.ASC, "fullName"));

        // A coordinator with no grants sees nobody. That default is stated here
        // rather than left to an in-clause, so the intent is visible.
        if (scope.isEmpty()) {
            return new StudentPage(List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0, 0);
        }

        Page<User> students = scope.seesWholeInstitution()
                ? userRepository.findStudentsInInstitution(institutionId, pageable)
                : userRepository.findStudentsInScope(
                        institutionId,
                        nonEmpty(scope.departmentIds()),
                        nonEmpty(scope.batchIds()),
                        pageable);

        // Three queries for the page rather than two per row. toRow used to
        // fetch each student's profile and count their resumes individually,
        // which is invisible at a page of five and fifty statements at
        // twenty-five.
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
                user.getCreatedAt());
    }

    private static Set<UUID> nonEmpty(Set<UUID> ids) {
        return ids.isEmpty() ? Set.of(MATCHES_NOTHING) : ids;
    }
}
