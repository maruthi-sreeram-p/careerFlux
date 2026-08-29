package com.careerflux.discovery.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.careerflux.matching.domain.ConfidenceLevel;
import com.careerflux.matching.domain.EligibilityStatus;
import com.careerflux.matching.service.MatchScorer;
import com.careerflux.requirement.domain.CompanyRequirement;

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
 * <p><b>Only conditions that are actually modelled are evaluated.</b> There is
 * no work-authorization check, no degree check and no certification check,
 * because CareerFlux holds none of those. Inventing a blocker from data that
 * does not exist would be worse than saying nothing.
 */
public final class FormalEligibility {

    private FormalEligibility() {
    }

    /**
     * @param status  reuses the existing {@link EligibilityStatus}; no new enum
     * @param reasons one line per condition that was checked and failed, or that
     *                could not be checked. Empty when everything stated was met.
     */
    public record Outcome(EligibilityStatus status, List<String> reasons) {

        public Outcome {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * Combines what the scorer already determined with the academic rule the
     * requirement states.
     *
     * <p>The scorer evaluates the conditions carried by the role itself — an
     * experience minimum, an unreachable on-site location — and those are
     * formal conditions too. This adds the one the requirement carries which a
     * job posting never does: the CGPA the company asked for.
     *
     * <p>Precedence is deliberate and matches the rule established when the
     * empty-profile contradiction was fixed:
     *
     * <ol>
     *   <li>A condition that was genuinely evaluated and genuinely failed wins.
     *       That is a real answer, however little else is known.
     *   <li>Otherwise, if too little is known to have an opinion at all, the
     *       answer is UNKNOWN rather than a flattering guess.
     *   <li>Otherwise, a stated rule that cannot be checked also yields UNKNOWN.
     *       "We cannot tell" is not "they qualify".
     * </ol>
     */
    public static Outcome evaluate(MatchScorer.Scorecard technical,
                                   CompanyRequirement requirement,
                                   BigDecimal candidateCgpa) {
        List<String> reasons = new ArrayList<>();

        for (MatchScorer.Blocker blocker : technical.blockers()) {
            reasons.add(blocker.detail() == null ? blocker.label() : blocker.detail());
        }

        BigDecimal required = requirement.getMinCgpa();
        boolean cgpaUncheckable = false;
        if (required != null) {
            if (candidateCgpa == null) {
                // Never parsed from a free-text grade, never inferred from
                // marks, never defaulted. CareerFlux does not hold a verified
                // numeric CGPA for anybody yet, and guessing one would put a
                // student's place on a drive on a number nobody entered.
                cgpaUncheckable = true;
                reasons.add("The company asks for a CGPA of " + required
                        + ". CareerFlux does not hold a verified CGPA for this student, so this"
                        + " cannot be checked.");
            } else if (candidateCgpa.compareTo(required) < 0) {
                reasons.add("CGPA " + candidateCgpa + " is below the " + required
                        + " this company asked for.");
                return new Outcome(EligibilityStatus.NOT_ELIGIBLE, reasons);
            }
        }

        if (!technical.blockers().isEmpty()) {
            return new Outcome(EligibilityStatus.NOT_ELIGIBLE, reasons);
        }
        if (technical.confidence().level() == ConfidenceLevel.INSUFFICIENT) {
            return new Outcome(EligibilityStatus.UNKNOWN, reasons);
        }
        if (cgpaUncheckable) {
            return new Outcome(EligibilityStatus.UNKNOWN, reasons);
        }
        return new Outcome(technical.eligibility(), reasons);
    }
}
