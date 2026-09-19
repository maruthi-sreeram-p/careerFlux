package com.careerflux.discovery.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.careerflux.matching.domain.EligibilityStatus;

/**
 * Whether a student satisfies the conditions a company actually stated.
 *
 * <p>This is the other half of the answer, and it is deliberately not the
 * score. A student can be the strongest technical fit in the college for a role
 * they do not formally qualify for, and a single blended number could never say
 * both things — it would quietly bury one of them. Keeping them apart is the
 * point of the feature: the placement team sees "96% technical, does not meet
 * the stated CGPA" and decides whether to put the student forward anyway or ask
 * the company for an exception. CareerFlux does not make that call.
 *
 * <p>Nothing here rejects anybody. A student who fails a condition still
 * appears in the results, with the condition named.
 *
 * <p><b>Nothing the matcher produces reaches this verdict.</b> The evaluator
 * takes a requirement's stated conditions and the college's own record of the
 * student, and it reads nothing else: no compatibility score, no match
 * confidence, no skill gap, no blocker. It used to. A candidate who met every
 * stated condition came back UNKNOWN when their profile was too thin to score —
 * a verified CGPA of 9.5 against a stated 7.0 was reported as "we cannot tell"
 * because the student had listed no skills. A formal condition that was checked
 * and passed is a fact; how well the profile scores is a different question with
 * a different answer, and the two are now computed apart.
 *
 * <p><b>Only conditions with a verified source are evaluated.</b> Degree, branch
 * and backlogs are not checked, because CareerFlux holds none of them from an
 * institutional source — what exists is free text a student typed or an AI read
 * from a resume, and neither can decide whether somebody attends a drive.
 * Experience and location are matching signals and are not formal conditions:
 * both come from the student's own profile, and Phase 2 settled that a student's
 * own figures never decide their eligibility.
 */
public final class FormalEligibility {

    private FormalEligibility() {
    }

    /** The conditions this evaluator knows how to check, in the order it reports them. */
    public enum Criterion {
        VERIFIED_CGPA,
        INSTITUTION,
        DEPARTMENT,
        GRADUATION_YEAR
    }

    /**
     * One condition's answer.
     *
     * <p>UNKNOWN is a real answer rather than a missing one: it says the
     * college has not recorded what the company asked about. It is never
     * rounded up to MET or down to NOT_MET.
     */
    public enum CriterionOutcome {
        MET,
        NOT_MET,
        UNKNOWN
    }

    /** @param reason null when the condition was met; there is nothing to report */
    public record CriterionResult(Criterion criterion, CriterionOutcome outcome, String reason) {
    }

    /**
     * @param status  reuses the existing {@link EligibilityStatus}; no new enum.
     *                Only ELIGIBLE, NOT_ELIGIBLE and UNKNOWN are produced here —
     *                ELIGIBLE_WITH_GAPS describes a skill gap, which is matching.
     * @param reasons one line per condition that failed or could not be checked,
     *                in {@link Criterion} order. Empty when everything stated was met.
     * @param checks  every condition that was actually evaluated, met or not
     */
    public record Outcome(EligibilityStatus status, List<String> reasons, List<CriterionResult> checks) {

        public Outcome {
            reasons = List.copyOf(reasons);
            checks = List.copyOf(checks);
        }
    }

    /**
     * Applies the stated conditions to the college's record of the student.
     *
     * <p>The precedence is fixed and total:
     *
     * <ol>
     *   <li>Any condition that was evaluated and failed wins. That is a real
     *       answer, however little else is known.
     *   <li>Otherwise any condition the college cannot answer yields UNKNOWN.
     *       "We have not recorded it" is not "they qualify".
     *   <li>Otherwise every stated condition was met, and the student formally
     *       qualifies. A company that stated no conditions beyond the college
     *       itself excludes nobody in it.
     * </ol>
     */
    public static Outcome evaluate(RequirementCriteria requirement, CandidateEvidence candidate) {
        List<CriterionResult> checks = new ArrayList<>();

        checks.add(cgpa(requirement.minCgpa(), candidate.verifiedCgpa()));
        checks.add(institution(requirement.institutionId(), candidate.institutionId()));
        checks.add(department(requirement.departmentIds(), candidate.departmentId()));
        checks.add(graduationYear(requirement.graduationYear(), candidate.graduationYear()));
        checks.removeIf(java.util.Objects::isNull);

        List<String> reasons = checks.stream()
                .filter(check -> check.reason() != null)
                .map(CriterionResult::reason)
                .toList();

        boolean failed = checks.stream().anyMatch(check -> check.outcome() == CriterionOutcome.NOT_MET);
        if (failed) {
            return new Outcome(EligibilityStatus.NOT_ELIGIBLE, reasons, checks);
        }
        boolean unanswerable = checks.stream().anyMatch(check -> check.outcome() == CriterionOutcome.UNKNOWN);
        if (unanswerable) {
            return new Outcome(EligibilityStatus.UNKNOWN, reasons, checks);
        }
        return new Outcome(EligibilityStatus.ELIGIBLE, reasons, checks);
    }

    /**
     * The academic minimum, against the college's own figure and only that one.
     *
     * <p>A student's own reported CGPA never reaches this method: the evidence
     * record carries the verified accessor, which is null unless an institution
     * recorded the value. Null is answered UNKNOWN and never parsed from a
     * free-text grade, because guessing one would decide whether a student
     * attends a drive.
     */
    private static CriterionResult cgpa(BigDecimal required, BigDecimal verified) {
        if (required == null) {
            return null;
        }
        if (verified == null) {
            return new CriterionResult(Criterion.VERIFIED_CGPA, CriterionOutcome.UNKNOWN,
                    "The company asks for a CGPA of " + required
                            + ". CareerFlux does not hold a verified CGPA for this student, so this"
                            + " cannot be checked.");
        }
        if (verified.compareTo(required) < 0) {
            return new CriterionResult(Criterion.VERIFIED_CGPA, CriterionOutcome.NOT_MET,
                    "CGPA " + verified + " is below the " + required + " this company asked for.");
        }
        return new CriterionResult(Criterion.VERIFIED_CGPA, CriterionOutcome.MET, null);
    }

    /**
     * The college itself.
     *
     * <p>Always stated, because a requirement belongs to one. Discovery has
     * already confined the cohort to it, so this restates rather than filters —
     * and if the two ever disagreed, the answer would be the honest one instead
     * of a silent pass.
     */
    private static CriterionResult institution(java.util.UUID required, java.util.UUID enrolled) {
        if (required == null) {
            return null;
        }
        if (enrolled == null) {
            return new CriterionResult(Criterion.INSTITUTION, CriterionOutcome.UNKNOWN,
                    "This student is not recorded against a college, so this drive's college "
                            + "cannot be checked.");
        }
        if (!required.equals(enrolled)) {
            return new CriterionResult(Criterion.INSTITUTION, CriterionOutcome.NOT_MET,
                    "This student belongs to a different college from the one running this drive.");
        }
        return new CriterionResult(Criterion.INSTITUTION, CriterionOutcome.MET, null);
    }

    /** The departments the company named. Empty means the whole college, so nothing is checked. */
    private static CriterionResult department(java.util.Set<java.util.UUID> targeted,
                                              java.util.UUID enrolled) {
        if (targeted.isEmpty()) {
            return null;
        }
        if (enrolled == null) {
            return new CriterionResult(Criterion.DEPARTMENT, CriterionOutcome.UNKNOWN,
                    "This drive is open to named departments, and the college has not recorded "
                            + "which department this student is in.");
        }
        if (!targeted.contains(enrolled)) {
            return new CriterionResult(Criterion.DEPARTMENT, CriterionOutcome.NOT_MET,
                    "This student's department is not one this drive is open to.");
        }
        return new CriterionResult(Criterion.DEPARTMENT, CriterionOutcome.MET, null);
    }

    /** The cohort year, taken from the student's batch rather than anything they typed. */
    private static CriterionResult graduationYear(Integer required, Integer enrolled) {
        if (required == null) {
            return null;
        }
        if (enrolled == null) {
            return new CriterionResult(Criterion.GRADUATION_YEAR, CriterionOutcome.UNKNOWN,
                    "The company asks for the " + required + " batch, and this student is not in "
                            + "a batch the college has recorded.");
        }
        if (!required.equals(enrolled)) {
            return new CriterionResult(Criterion.GRADUATION_YEAR, CriterionOutcome.NOT_MET,
                    "The company asks for the " + required + " batch; this student graduates in "
                            + enrolled + ".");
        }
        return new CriterionResult(Criterion.GRADUATION_YEAR, CriterionOutcome.MET, null);
    }
}
