package com.careerflux.institution.dto;

import java.util.List;

/**
 * What the institutional dashboards are built from.
 *
 * <p>One shape serves both coordinator roles. They differ in what they are
 * allowed to see, not in what the numbers mean, and that difference is already
 * expressed by the scope the server resolves for the caller — a department
 * coordinator's "students" is their department, a placement coordinator's is
 * the college. Giving each role its own endpoint would have duplicated the same
 * aggregates behind two names.
 *
 * <p>Every field is a count of real rows. There is deliberately no placement
 * rate, no offer count and no success percentage: CareerFlux does not model
 * placement outcomes yet, and a zero here would read as a measurement rather
 * than as an absence.
 *
 * <p>Nothing here counts what students do with public job postings. Views are
 * private, saves are private until decided otherwise, and an Apply click is not
 * a confirmed application — so counting any of them for staff would either
 * disclose the private ones or present clicks as applications.
 */
public final class OverviewDtos {

    private OverviewDtos() {
    }

    /**
     * A named cohort slice, used for the department and batch breakdowns and for
     * the skill distribution.
     *
     * @param label the department name, batch name or skill
     * @param count how many students in scope it covers
     */
    public record CohortCount(String label, long count) {
    }

    /**
     * @param scopeLabel      what the caller is looking at, in their own terms —
     *                        "Computer Science" for a department coordinator,
     *                        "Whole institution" for a placement coordinator.
     *                        Without this a short list looks like a small college.
     * @param institutionWide whether these numbers cover the college or a slice
     * @param averageProfileCompleteness null when nobody in scope has a profile,
     *                        so the client shows "no data" rather than 0%
     * @param departmentCount institution-level configuration, meaningful to the
     *                        placement coordinator and harmless to the others
     */
    public record InstitutionOverview(
            String institutionName,
            String scopeLabel,
            boolean institutionWide,
            long studentsInScope,
            long activeStudents,
            long withCandidateProfile,
            long withCareerProfile,
            long withResume,
            long withSkills,
            Integer averageProfileCompleteness,
            List<CohortCount> byDepartment,
            List<CohortCount> byBatch,
            List<CohortCount> topSkills,
            long departmentCount,
            long batchCount,
            long staffCount) {
    }
}
