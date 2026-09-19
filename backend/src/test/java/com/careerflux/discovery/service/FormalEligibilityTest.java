package com.careerflux.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.discovery.service.FormalEligibility.Criterion;
import com.careerflux.discovery.service.FormalEligibility.CriterionOutcome;
import com.careerflux.discovery.service.FormalEligibility.Outcome;
import com.careerflux.matching.domain.EligibilityStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The formal verdict, on its own (Phase 3B, D-2).
 *
 * <p>There is no Spring context here and no {@code MatchScorer}, which is the
 * point: the evaluator decides from a requirement's stated conditions and the
 * college's own record of a student, and from nothing else. A test that needed
 * a scorer to reach a verdict would be evidence the separation had not happened.
 */
class FormalEligibilityTest {

    private static final UUID COLLEGE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID OTHER_COLLEGE = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID CSE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID MECH = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    private static RequirementCriteria stating(BigDecimal minCgpa, Integer year, Set<UUID> departments) {
        return new RequirementCriteria(COLLEGE, departments, year, minCgpa);
    }

    private static CandidateEvidence evidence(BigDecimal verifiedCgpa, Integer year, UUID department) {
        return new CandidateEvidence(COLLEGE, department, year, verifiedCgpa);
    }

    @Nested
    @DisplayName("the academic minimum, against the college's own figure")
    class Cgpa {

        @Test
        @DisplayName("above the minimum is eligible, and equal to it is too")
        void aboveAndEqualAreEligible() {
            RequirementCriteria asks7 = stating(new BigDecimal("7.00"), null, Set.of());

            assertThat(FormalEligibility.evaluate(asks7, evidence(new BigDecimal("7.50"), null, null)).status())
                    .isEqualTo(EligibilityStatus.ELIGIBLE);
            assertThat(FormalEligibility.evaluate(asks7, evidence(new BigDecimal("7.00"), null, null)).status())
                    .isEqualTo(EligibilityStatus.ELIGIBLE);
        }

        @Test
        @DisplayName("below the minimum is not eligible, and the reason says both numbers")
        void belowIsNotEligible() {
            Outcome outcome = FormalEligibility.evaluate(
                    stating(new BigDecimal("7.00"), null, Set.of()),
                    evidence(new BigDecimal("6.82"), null, null));

            assertThat(outcome.status()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
            assertThat(outcome.reasons()).singleElement().asString()
                    .contains("6.82").contains("7.00");
        }

        @Test
        @DisplayName("no verified figure is unknown, which is not the same as failing")
        void missingIsUnknown() {
            Outcome outcome = FormalEligibility.evaluate(
                    stating(new BigDecimal("7.00"), null, Set.of()),
                    evidence(null, null, null));

            assertThat(outcome.status()).isEqualTo(EligibilityStatus.UNKNOWN);
            assertThat(outcome.reasons()).singleElement().asString()
                    .contains("does not hold a verified CGPA");
        }

        @Test
        @DisplayName("a company that stated no minimum makes nobody ineligible")
        void unstatedMinimumIsNotChecked() {
            Outcome outcome = FormalEligibility.evaluate(
                    stating(null, null, Set.of()), evidence(null, null, null));

            assertThat(outcome.status()).isEqualTo(EligibilityStatus.ELIGIBLE);
            assertThat(outcome.reasons()).isEmpty();
            assertThat(outcome.checks()).extracting(FormalEligibility.CriterionResult::criterion)
                    .doesNotContain(Criterion.VERIFIED_CGPA);
        }

        @Test
        @DisplayName("a figure the student reported themselves is not evidence at all")
        void reportedCgpaIsNotEvidence() {
            CandidateProfile profile = new CandidateProfile();
            profile.recordReportedCgpa(new BigDecimal("9.90"));

            // The evidence record reads the verified accessor, which stays null
            // until an institution records a figure. A student cannot promote
            // their own number by writing a large one.
            assertThat(CandidateEvidence.of(profile).verifiedCgpa()).isNull();
            assertThat(FormalEligibility.evaluate(
                    stating(new BigDecimal("7.00"), null, Set.of()),
                    CandidateEvidence.of(profile)).status())
                    .isEqualTo(EligibilityStatus.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("the cohort the company named")
    class Cohort {

        @Test
        @DisplayName("the stated graduation year is met, missed, or unrecorded")
        void graduationYear() {
            RequirementCriteria asks2026 = stating(null, 2026, Set.of());

            assertThat(FormalEligibility.evaluate(asks2026, evidence(null, 2026, null)).status())
                    .isEqualTo(EligibilityStatus.ELIGIBLE);
            assertThat(FormalEligibility.evaluate(asks2026, evidence(null, 2027, null)).status())
                    .isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
            assertThat(FormalEligibility.evaluate(asks2026, evidence(null, null, null)).status())
                    .isEqualTo(EligibilityStatus.UNKNOWN);
        }

        @Test
        @DisplayName("named departments are checked; naming none opens the drive to the college")
        void departments() {
            RequirementCriteria asksCse = stating(null, null, Set.of(CSE));

            assertThat(FormalEligibility.evaluate(asksCse, evidence(null, null, CSE)).status())
                    .isEqualTo(EligibilityStatus.ELIGIBLE);
            assertThat(FormalEligibility.evaluate(asksCse, evidence(null, null, MECH)).status())
                    .isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
            assertThat(FormalEligibility.evaluate(asksCse, evidence(null, null, null)).status())
                    .isEqualTo(EligibilityStatus.UNKNOWN);
            assertThat(FormalEligibility.evaluate(stating(null, null, Set.of()),
                    evidence(null, null, MECH)).status())
                    .isEqualTo(EligibilityStatus.ELIGIBLE);
        }

        @Test
        @DisplayName("a student of another college never formally qualifies")
        void otherCollege() {
            Outcome outcome = FormalEligibility.evaluate(stating(null, null, Set.of()),
                    new CandidateEvidence(OTHER_COLLEGE, CSE, 2026, new BigDecimal("9.0")));

            assertThat(outcome.status()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
            assertThat(outcome.reasons()).singleElement().asString().contains("different college");
        }
    }

    @Nested
    @DisplayName("how the answers combine")
    class Precedence {

        @Test
        @DisplayName("a condition that failed outranks one that could not be checked")
        void failureWinsOverUnknown() {
            // CGPA unrecorded, and the wrong batch entirely.
            Outcome outcome = FormalEligibility.evaluate(
                    stating(new BigDecimal("7.00"), 2026, Set.of()),
                    evidence(null, 2027, null));

            assertThat(outcome.status()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
            assertThat(outcome.checks()).extracting(FormalEligibility.CriterionResult::outcome)
                    .contains(CriterionOutcome.UNKNOWN, CriterionOutcome.NOT_MET);
        }

        @Test
        @DisplayName("every failure is reported, in a fixed order")
        void failuresAreAllReported() {
            Outcome outcome = FormalEligibility.evaluate(
                    stating(new BigDecimal("7.00"), 2026, Set.of(CSE)),
                    evidence(new BigDecimal("5.00"), 2027, MECH));

            assertThat(outcome.status()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
            assertThat(outcome.reasons()).hasSize(3);
            assertThat(outcome.checks()).extracting(FormalEligibility.CriterionResult::criterion)
                    .containsExactly(Criterion.VERIFIED_CGPA, Criterion.INSTITUTION,
                            Criterion.DEPARTMENT, Criterion.GRADUATION_YEAR);
        }

        @Test
        @DisplayName("every unanswerable condition is reported, in the same fixed order")
        void unknownsAreAllReported() {
            Outcome outcome = FormalEligibility.evaluate(
                    stating(new BigDecimal("7.00"), 2026, Set.of(CSE)),
                    evidence(null, null, null));

            assertThat(outcome.status()).isEqualTo(EligibilityStatus.UNKNOWN);
            assertThat(outcome.reasons()).hasSize(3);
            assertThat(outcome.checks()).filteredOn(check -> check.outcome() == CriterionOutcome.UNKNOWN)
                    .extracting(FormalEligibility.CriterionResult::criterion)
                    .containsExactly(Criterion.VERIFIED_CGPA, Criterion.DEPARTMENT,
                            Criterion.GRADUATION_YEAR);
        }

        @Test
        @DisplayName("the same evidence always produces the same verdict")
        void isDeterministic() {
            RequirementCriteria criteria = stating(new BigDecimal("7.00"), 2026, Set.of(CSE, MECH));
            CandidateEvidence candidate = evidence(new BigDecimal("8.25"), 2026, MECH);

            Outcome first = FormalEligibility.evaluate(criteria, candidate);
            for (int run = 0; run < 25; run++) {
                Outcome again = FormalEligibility.evaluate(criteria, candidate);
                assertThat(again.status()).isEqualTo(first.status());
                assertThat(again.reasons()).isEqualTo(first.reasons());
                assertThat(again.checks()).isEqualTo(first.checks());
            }
            assertThat(first.status()).isEqualTo(EligibilityStatus.ELIGIBLE);
        }

        @Test
        @DisplayName("nothing here can ever answer ELIGIBLE_WITH_GAPS: a gap is a matching word")
        void skillGapsAreNotAVerdict() {
            List<Outcome> everyShape = List.of(
                    FormalEligibility.evaluate(stating(null, null, Set.of()), evidence(null, null, null)),
                    FormalEligibility.evaluate(stating(new BigDecimal("7.0"), null, Set.of()),
                            evidence(new BigDecimal("6.0"), null, null)),
                    FormalEligibility.evaluate(stating(new BigDecimal("7.0"), null, Set.of()),
                            evidence(null, null, null)));

            assertThat(everyShape).extracting(Outcome::status)
                    .containsExactly(EligibilityStatus.ELIGIBLE, EligibilityStatus.NOT_ELIGIBLE,
                            EligibilityStatus.UNKNOWN)
                    .doesNotContain(EligibilityStatus.ELIGIBLE_WITH_GAPS);
        }
    }
}
