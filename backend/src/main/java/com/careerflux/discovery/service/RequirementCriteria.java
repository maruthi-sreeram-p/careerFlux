package com.careerflux.discovery.service;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import com.careerflux.institution.domain.Department;
import com.careerflux.requirement.domain.CompanyRequirement;

/**
 * The conditions a company actually stated, as formal eligibility reads them.
 *
 * <p>Built from the requirement and nothing else. Deliberately <b>not</b> the
 * {@code ScorableRole} the matcher uses: that adapter carries the role's
 * technical shape — skills, experience range, work mode — and omits the CGPA
 * entirely, which is the one condition a college writes down and a job posting
 * never does. Two different questions, two different inputs.
 *
 * <p>What is absent here is as deliberate as what is present. Required skills,
 * experience and location are matching signals and are not conditions a student
 * formally fails; degree, branch and backlogs have no verified source in
 * CareerFlux yet and are therefore not evaluated at all, rather than guessed
 * from what a student typed.
 *
 * @param institutionId  the college that owns the drive; never null
 * @param departmentIds  empty means the whole college rather than nobody
 * @param graduationYear null when the company named no cohort year
 * @param minCgpa        null when the company stated no academic minimum
 */
public record RequirementCriteria(UUID institutionId,
                                  Set<UUID> departmentIds,
                                  Integer graduationYear,
                                  BigDecimal minCgpa) {

    public RequirementCriteria {
        departmentIds = departmentIds == null ? Set.of() : Set.copyOf(departmentIds);
    }

    public static RequirementCriteria of(CompanyRequirement requirement) {
        Set<UUID> departments = requirement.getDepartments().stream()
                .map(Department::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        UUID institutionId = requirement.getInstitution() == null
                ? null : requirement.getInstitution().getId();
        return new RequirementCriteria(institutionId, departments,
                requirement.getGraduationYear(), requirement.getMinCgpa());
    }
}
