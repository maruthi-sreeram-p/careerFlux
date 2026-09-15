package com.careerflux.requirement.service;

import java.util.UUID;

import com.careerflux.common.error.NotFoundException;
import com.careerflux.requirement.domain.CompanyRequirement;
import com.careerflux.requirement.repository.CompanyRequirementRepository;
import com.careerflux.security.access.AccessGuard;
import com.careerflux.security.access.AccessScope;

import org.springframework.stereotype.Component;

/**
 * Whether the caller may see a company requirement at all: the one rule every
 * requirement-shaped endpoint shares.
 *
 * <p>Two tests, both required. The requirement must belong to the caller's own
 * college, which is part of the query, so another college's row is never
 * loaded. And a department coordinator must be granted one of the departments
 * it targets, or it must target none — a college-wide requirement is open to
 * every department.
 *
 * <p>Held in one place because it used to live only in
 * {@link CompanyRequirementService}. Discovery, the shortlist and placement
 * history loaded a requirement by college alone, so a coordinator refused the
 * requirement itself could still read its company, role, target departments,
 * minimum CGPA and shortlist count through any of them. Every one of them now
 * asks this.
 */
@Component
public class RequirementAccess {

    private static final String LABEL = "Company requirement";

    private final CompanyRequirementRepository requirements;
    private final AccessGuard accessGuard;

    public RequirementAccess(CompanyRequirementRepository requirements, AccessGuard accessGuard) {
        this.requirements = requirements;
        this.accessGuard = accessGuard;
    }

    /**
     * The requirement, if this caller may see it, and not-found otherwise. The
     * answer is the same for another college's requirement, one outside the
     * caller's departments and one that does not exist, so none of them is
     * confirmed to exist.
     */
    public CompanyRequirement visible(UUID requirementId) {
        UUID institutionId = accessGuard.requireInstitutionId();
        CompanyRequirement requirement = requirements.findByIdAndInstitutionId(requirementId, institutionId)
                .orElseThrow(() -> NotFoundException.of(LABEL, requirementId));
        if (!isVisibleTo(requirement, accessGuard.scope())) {
            throw NotFoundException.of(LABEL, requirementId);
        }
        return requirement;
    }

    /**
     * Whether a requirement already known to be in the caller's college is one
     * they may see.
     *
     * <p>A caller with no department — a department coordinator granted nothing,
     * or only batches — sees no requirement, not even a college-wide one. An
     * empty scope is empty everywhere, rather than empty for students and open
     * for everything else.
     */
    public boolean isVisibleTo(CompanyRequirement requirement, AccessScope scope) {
        if (scope.seesWholeInstitution()) {
            return true;
        }
        if (scope.isEmpty()) {
            return false;
        }
        if (requirement.getDepartments().isEmpty()) {
            return true;
        }
        return requirement.getDepartments().stream()
                .anyMatch(department -> scope.departmentIds().contains(department.getId()));
    }
}
