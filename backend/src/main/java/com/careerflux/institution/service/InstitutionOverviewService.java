package com.careerflux.institution.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.repository.ResumeRepository;
import com.careerflux.institution.dto.OverviewDtos.CohortCount;
import com.careerflux.institution.dto.OverviewDtos.InstitutionOverview;
import com.careerflux.institution.repository.BatchRepository;
import com.careerflux.institution.repository.DepartmentRepository;
import com.careerflux.institution.repository.InstitutionRepository;
import com.careerflux.security.access.AccessGuard;
import com.careerflux.security.access.AccessScope;
import com.careerflux.user.Permission;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserStatus;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The counts behind the coordinator dashboards.
 *
 * <p>Scope is resolved once, at the top, into the list of student ids the caller
 * may see. Every aggregate below is then keyed on that list. This is the same
 * discipline {@code StudentDirectoryService} uses and it matters for the same
 * reason: a coordinator asking for their department's numbers must not be able
 * to obtain the college's, and no id in the cohort ever comes from the request.
 *
 * <p>A caller with no department gets zeroes rather than the institution. That
 * is the safe default, and it is stated here rather than emerging from an empty
 * {@code in} clause.
 *
 * <p>Nothing counted here comes from what students did with public job
 * postings: views and saves are theirs, and an Apply click is not a confirmed
 * application.
 */
@Service
public class InstitutionOverviewService {

    /** Enough of a distribution to be useful without becoming a report. */
    private static final int TOP_SKILL_LIMIT = 12;

    private final AccessGuard accessGuard;
    private final UserRepository userRepository;
    private final CandidateProfileRepository profileRepository;
    private final ResumeRepository resumeRepository;
    private final InstitutionRepository institutionRepository;
    private final DepartmentRepository departmentRepository;
    private final BatchRepository batchRepository;

    public InstitutionOverviewService(AccessGuard accessGuard,
                                      UserRepository userRepository,
                                      CandidateProfileRepository profileRepository,
                                      ResumeRepository resumeRepository,
                                      InstitutionRepository institutionRepository,
                                      DepartmentRepository departmentRepository,
                                      BatchRepository batchRepository) {
        this.accessGuard = accessGuard;
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.resumeRepository = resumeRepository;
        this.institutionRepository = institutionRepository;
        this.departmentRepository = departmentRepository;
        this.batchRepository = batchRepository;
    }

    @Transactional(readOnly = true)
    public InstitutionOverview overview() {
        accessGuard.requirePermission(Permission.ANALYTICS_VIEW);
        UUID institutionId = accessGuard.requireInstitutionId();
        AccessScope scope = accessGuard.scope();

        String institutionName = institutionRepository.findById(institutionId)
                .map(institution -> institution.getName())
                .orElse("Unknown institution");

        long departmentCount = departmentRepository.countByInstitutionId(institutionId);
        long batchCount = batchRepository.countByInstitutionId(institutionId);
        long staffCount = userRepository.countStaffInInstitution(institutionId);

        List<UUID> studentIds = studentsInScope(institutionId, scope);
        if (studentIds.isEmpty()) {
            return empty(institutionName, scopeLabel(institutionId, scope),
                    scope.seesWholeInstitution(), departmentCount, batchCount, staffCount);
        }

        List<UUID> candidateIds = profileRepository.findIdsByUserIdIn(studentIds);
        long withResume = candidateIds.isEmpty() ? 0
                : resumeRepository.countCandidatesWithResume(candidateIds);

        Double meanCompleteness = profileRepository.averageCompleteness(studentIds);

        return new InstitutionOverview(
                institutionName,
                scopeLabel(institutionId, scope),
                scope.seesWholeInstitution(),
                studentIds.size(),
                userRepository.countByIdInAndStatus(studentIds, UserStatus.ACTIVE),
                profileRepository.countByUserIdIn(studentIds),
                profileRepository.countOnboarded(studentIds),
                withResume,
                profileRepository.countWithAnySkill(studentIds),
                meanCompleteness == null ? null : (int) Math.round(meanCompleteness),
                toCohort(userRepository.countByDepartment(studentIds)),
                toCohort(userRepository.countByBatch(studentIds)),
                toCohort(profileRepository.topSkills(studentIds, PageRequest.of(0, TOP_SKILL_LIMIT))),
                departmentCount,
                batchCount,
                staffCount);
    }

    /**
     * The cohort this caller may count.
     *
     * <p>Mirrors {@code StudentDirectoryService.students} exactly, so the numbers
     * on the dashboard always describe the same people the directory lists.
     */
    private List<UUID> studentsInScope(UUID institutionId, AccessScope scope) {
        if (scope.isEmpty()) {
            return List.of();
        }
        return scope.seesWholeInstitution()
                ? userRepository.findStudentIdsInInstitution(institutionId)
                : userRepository.findStudentIdsInScope(institutionId,
                        nonEmpty(scope.departmentIds()),
                        !scope.hasBatchRestriction(), nonEmpty(scope.batchIds()));
    }

    /** What the caller is looking at, so a short list is not read as a small college. */
    private String scopeLabel(UUID institutionId, AccessScope scope) {
        if (scope.seesWholeInstitution()) {
            return "Whole institution";
        }
        // No department means no scope, whatever batches were granted: a batch
        // only narrows a department and on its own shows nobody.
        if (scope.isEmpty()) {
            return "No scope granted";
        }
        List<String> departments = new ArrayList<>();
        scope.departmentIds().forEach(id -> departmentRepository
                .findByIdAndInstitutionId(id, institutionId)
                .ifPresent(department -> departments.add(department.getName())));
        List<String> batches = new ArrayList<>();
        scope.batchIds().forEach(id -> batchRepository
                .findByIdAndInstitutionId(id, institutionId)
                .ifPresent(batch -> batches.add(batch.getName())));
        if (departments.isEmpty()) {
            return "No scope granted";
        }
        departments.sort(String::compareTo);
        batches.sort(String::compareTo);
        String label = String.join(", ", departments);
        return batches.isEmpty() ? label : label + " — " + String.join(", ", batches) + " only";
    }

    private static InstitutionOverview empty(String institutionName, String scopeLabel,
                                             boolean institutionWide, long departments,
                                             long batches, long staff) {
        return new InstitutionOverview(institutionName, scopeLabel, institutionWide,
                0, 0, 0, 0, 0, 0, null,
                List.of(), List.of(), List.of(), departments, batches, staff);
    }

    private static List<CohortCount> toCohort(List<Object[]> rows) {
        return rows.stream()
                .map(row -> new CohortCount((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    /**
     * An empty {@code in} list is invalid in JPQL, so an unused half of the scope
     * is given one impossible id rather than none.
     */
    private static Collection<UUID> nonEmpty(Collection<UUID> ids) {
        return ids.isEmpty() ? List.of(new UUID(0, 0)) : ids;
    }
}
