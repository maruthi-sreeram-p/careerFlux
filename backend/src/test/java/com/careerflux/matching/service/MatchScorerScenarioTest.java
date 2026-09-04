package com.careerflux.matching.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.matching.role.ScorableRoles;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobSkill;
import com.careerflux.job.domain.SkillRequirement;
import com.careerflux.matching.domain.BlockerType;
import com.careerflux.matching.domain.ComponentKind;
import com.careerflux.matching.domain.ConfidenceLevel;
import com.careerflux.matching.domain.EligibilityStatus;
import com.careerflux.matching.domain.MatchDimension;
import com.careerflux.skill.Skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rules-2 scoring model, exercised through the approved scenarios.
 *
 * <p>Four things are asserted separately throughout, because the whole point of
 * rules-2 is that they are different questions: eligibility, compatibility,
 * skill gaps, and how much was actually known.
 *
 * <p>Scores are asserted as ranges rather than exact values wherever the point
 * is an ordering or a band. Pinning every arithmetic result would make the suite
 * fail on any weight change, which is the opposite of useful — the weights are
 * an explicit hypothesis and expected to move.
 */
class MatchScorerScenarioTest {

    private final MatchScorer scorer = new MatchScorer(properties());

    // =====================================================================
    // The fifteen approved scenarios
    // =====================================================================

    @Nested
    @DisplayName("approved scenarios")
    class Scenarios {

        @Test
        @DisplayName("1 — a perfect candidate is eligible, high scoring and fully known")
        void perfectCandidate() {
            var job = job("Backend Developer").city("Bengaluru").workMode(WorkMode.HYBRID)
                    .seniority(Seniority.MID).experience(2, 5)
                    .required("Java", "Spring Boot", "PostgreSQL").preferred("REST APIs").build();
            var candidate = candidate().role("Backend Developer").years(3).seniority(Seniority.MID)
                    .location("Bengaluru").workModes(WorkMode.HYBRID)
                    .skills("java", "spring-boot", "postgresql", "rest-apis").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            assertThat(card.eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE);
            assertThat(card.compatibility()).isGreaterThanOrEqualTo(95);
            assertThat(card.missingRequiredSkills()).isEmpty();
            assertThat(card.confidence().level()).isEqualTo(ConfidenceLevel.HIGH);
        }

        @Test
        @DisplayName("2 — two missing required skills are gaps, not a disqualification")
        void twoSkillGaps() {
            var job = job("Backend Developer").city("Bengaluru").workMode(WorkMode.HYBRID)
                    .seniority(Seniority.MID).experience(2, 5)
                    .required("Java", "Spring Boot", "AWS", "Kubernetes").build();
            var candidate = candidate().role("Backend Developer").years(3).seniority(Seniority.MID)
                    .location("Bengaluru").workModes(WorkMode.HYBRID)
                    .skills("java", "spring-boot").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            // The heart of the brief: missing skills must never block.
            assertThat(card.eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE_WITH_GAPS);
            assertThat(card.blockers()).isEmpty();
            assertThat(card.missingRequiredSkills()).containsExactlyInAnyOrder("AWS", "Kubernetes");
            assertThat(card.compatibility()).isBetween(70, 85);
        }

        @Test
        @DisplayName("3 — a fresher meeting every requirement scores highly")
        void fresherForEntryRole() {
            var job = job("Software Engineer Graduate").city("Hyderabad").workMode(WorkMode.ONSITE)
                    .seniority(Seniority.ENTRY).experience(0, 1)
                    .required("Java", "Data Structures").preferred("Git").build();
            var candidate = candidate().role("Software Engineer").years(0).seniority(Seniority.ENTRY)
                    .location("Hyderabad").skills("java", "data-structures").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            assertThat(card.blockers()).isEmpty();
            assertThat(card.missingRequiredSkills()).isEmpty();
            assertThat(card.compatibility()).isGreaterThanOrEqualTo(80);
        }

        @Test
        @DisplayName("4 — missing most required skills scores low but stays eligible")
        void missingMajorSkill() {
            var job = job("Machine Learning Engineer").city("Bengaluru").workMode(WorkMode.REMOTE)
                    .seniority(Seniority.MID).experience(2, 4)
                    .required("Python", "TensorFlow", "PyTorch", "Machine Learning")
                    .preferred("SQL").build();
            var candidate = candidate().role("Data Analyst").years(2).seniority(Seniority.JUNIOR)
                    .location("Bengaluru").skills("python", "sql", "pandas").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            assertThat(card.blockers()).isEmpty();
            assertThat(card.eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE_WITH_GAPS);
            assertThat(card.missingRequiredSkills()).hasSize(3);
            assertThat(card.compatibility()).isLessThan(65);
        }

        @Test
        @DisplayName("5 — an experience shortfall past the threshold blocks")
        void belowStatedMinimum() {
            var job = job("Senior Backend Engineer").city("Bengaluru").workMode(WorkMode.HYBRID)
                    .seniority(Seniority.SENIOR).experience(5, null)
                    .required("Java", "Spring Boot", "PostgreSQL").build();
            var candidate = candidate().role("Backend Developer").years(1).seniority(Seniority.JUNIOR)
                    .location("Bengaluru").workModes(WorkMode.HYBRID)
                    .skills("java", "spring-boot", "postgresql").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            assertThat(card.eligibility()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
            assertThat(card.blockers()).extracting(MatchScorer.Blocker::type)
                    .containsExactly(BlockerType.EXPERIENCE_BELOW_MINIMUM);
            // Compatibility is still reported: the technical fit is real, and
            // hiding it would collapse the two answers back into one.
            assertThat(card.compatibility()).isNotNull();
        }

        @Test
        @DisplayName("6 — a wrong role with a perfect stack stays eligible and scores on the stack")
        void wrongRoleStrongOverlap() {
            var job = job("Solutions Architect").city("Bengaluru").workMode(WorkMode.HYBRID)
                    .seniority(Seniority.MID).experience(3, 6)
                    .required("Java", "Spring Boot", "PostgreSQL").build();
            var candidate = candidate().role("Backend Developer").years(3).seniority(Seniority.MID)
                    .location("Bengaluru").workModes(WorkMode.HYBRID)
                    .skills("java", "spring-boot", "postgresql").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            assertThat(card.eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE);
            assertThat(card.score(MatchDimension.ROLE)).isLessThan(50);
            assertThat(componentLabels(card, ComponentKind.GAP))
                    .anyMatch(label -> label.contains("Different from the roles"));
        }

        @Test
        @DisplayName("7 — an unreachable on-site role blocks while compatibility stays high")
        void rightRoleWrongOnsiteLocation() {
            var job = job("Backend Developer").city("Toronto").workMode(WorkMode.ONSITE)
                    .seniority(Seniority.MID).experience(2, 5)
                    .required("Java", "Spring Boot").build();
            var candidate = candidate().role("Backend Developer").years(3).seniority(Seniority.MID)
                    .location("Bengaluru").skills("java", "spring-boot").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            assertThat(card.eligibility()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
            assertThat(card.blockers()).extracting(MatchScorer.Blocker::type)
                    .containsExactly(BlockerType.ONSITE_UNREACHABLE);
            // The case the whole separation exists for: an excellent fit they
            // cannot take. One number could never have said both.
            assertThat(card.compatibility()).isGreaterThanOrEqualTo(75);
        }

        @Test
        @DisplayName("8 — a remote role the candidate wants scores at the top")
        void remoteStrongMatch() {
            var job = job("Backend Engineer").workMode(WorkMode.REMOTE).city("Remote")
                    .seniority(Seniority.MID).experience(2, 5)
                    .required("Java", "Spring Boot", "PostgreSQL")
                    .optional("Docker", "Kafka").build();
            var candidate = candidate().role("Backend Engineer").years(3).seniority(Seniority.MID)
                    .location("Bengaluru").workModes(WorkMode.REMOTE)
                    .skills("java", "spring-boot", "postgresql", "docker").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            assertThat(card.eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE);
            assertThat(card.missingRequiredSkills()).isEmpty();
            assertThat(card.compatibility()).isGreaterThanOrEqualTo(90);
        }

        @Test
        @DisplayName("9 — an empty profile produces no score at all")
        void emptyProfile() {
            var job = job("Backend Developer").city("Bengaluru").workMode(WorkMode.HYBRID)
                    .required("Java").build();
            var candidate = candidate().build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            assertThat(card.isAvailable()).isFalse();
            assertThat(card.compatibility()).isNull();
            assertThat(card.confidence().level()).isEqualTo(ConfidenceLevel.INSUFFICIENT);
            // The scorecard has to agree with itself. Reporting no score and
            // ELIGIBLE at the same time told a student with an empty profile
            // that they qualified, on the strength of having listed nothing.
            assertThat(card.eligibility()).isEqualTo(EligibilityStatus.UNKNOWN);
        }

        @Test
        @DisplayName("9b — a blocker still answers even when little else is known")
        void blockerOutranksInsufficientConfidence() {
            // Nothing is known about skills or role, so confidence is
            // insufficient — but the posting is on-site somewhere this
            // candidate has ruled out, which was genuinely checked and
            // genuinely failed. That is an answer, not an absence of one.
            var job = job("Backend Developer").city("Bengaluru").workMode(WorkMode.ONSITE)
                    .build();
            var candidate = candidate().location("Hyderabad").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            assertThat(card.confidence().level()).isEqualTo(ConfidenceLevel.INSUFFICIENT);
            assertThat(card.eligibility()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
            assertThat(card.blockers()).isNotEmpty();
        }

        @Test
        @DisplayName("10 — a posting stating almost nothing scores with low confidence")
        void jobStatesAlmostNothing() {
            var job = job("Software Engineer").city("Bengaluru").build();
            var candidate = candidate().role("Backend Developer").years(3).seniority(Seniority.MID)
                    .location("Bengaluru").skills("java", "spring-boot").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            assertThat(card.isAvailable()).isTrue();
            assertThat(card.confidence().level())
                    .isIn(ConfidenceLevel.LOW, ConfidenceLevel.MEDIUM);
            assertThat(card.confidence().coveragePercent()).isLessThan(80);
            assertThat(card.confidence().unknown()).contains(MatchDimension.SKILLS);
        }

        @Test
        @DisplayName("11 — a long optional list does not punish a candidate who meets every requirement")
        void longRequirementList() {
            var job = job("Backend Engineer").city("Bengaluru").workMode(WorkMode.HYBRID)
                    .seniority(Seniority.MID).experience(2, 5)
                    .required("Java", "Spring Boot", "PostgreSQL")
                    .preferred("Kafka", "Redis")
                    .optional("Docker", "Kubernetes", "Terraform", "GraphQL",
                            "Elasticsearch", "AWS", "Jenkins", "Linux")
                    .build();
            var candidate = candidate().role("Backend Engineer").years(3).seniority(Seniority.MID)
                    .location("Bengaluru").workModes(WorkMode.HYBRID)
                    .skills("java", "spring-boot", "postgresql").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            // Under rules-1 this scored 35 on skills, because all thirteen were
            // required. Optional now carries a tenth of the weight.
            assertThat(card.score(MatchDimension.SKILLS)).isGreaterThanOrEqualTo(65);
            assertThat(card.missingRequiredSkills()).isEmpty();
            assertThat(card.compatibility()).isGreaterThanOrEqualTo(80);
        }

        @Test
        @DisplayName("12 — a mix of tiers weights required most heavily")
        void mixedTiers() {
            var job = job("Backend Engineer").city("Bengaluru").workMode(WorkMode.HYBRID)
                    .seniority(Seniority.MID).experience(2, 5)
                    .required("Java", "Spring Boot", "PostgreSQL")
                    .preferred("Kafka", "Redis")
                    .optional("Docker", "Kubernetes")
                    .build();
            var candidate = candidate().role("Backend Engineer").years(3).seniority(Seniority.MID)
                    .location("Bengaluru").workModes(WorkMode.HYBRID)
                    .skills("java", "spring-boot", "kafka", "docker").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            assertThat(card.missingRequiredSkills()).containsExactly("PostgreSQL");
            assertThat(card.missingPreferredSkills()).containsExactly("Redis");
            assertThat(card.eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE_WITH_GAPS);
        }

        @Test
        @DisplayName("13 — a posting with no skills cannot reach high confidence")
        void jobWithNoSkillInformation() {
            var job = job("Backend Developer").city("Bengaluru").workMode(WorkMode.HYBRID)
                    .seniority(Seniority.MID).experience(2, 5).build();
            var candidate = candidate().role("Backend Developer").years(3).seniority(Seniority.MID)
                    .location("Bengaluru").workModes(WorkMode.HYBRID)
                    .skills("java", "spring-boot").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            assertThat(card.score(MatchDimension.SKILLS)).isZero();
            assertThat(card.confidence().unknown()).contains(MatchDimension.SKILLS);
            assertThat(card.confidence().level()).isNotEqualTo(ConfidenceLevel.HIGH);
        }

        @Test
        @DisplayName("14 — skills without stated preferences still score, using resume-derived facts")
        void skillsButNoPreferences() {
            var job = job("Backend Engineer").city("Bengaluru").workMode(WorkMode.HYBRID)
                    .seniority(Seniority.MID).experience(2, 5)
                    .required("Java", "Spring Boot", "PostgreSQL").build();
            // No target roles, no preferred locations, no work modes — only what
            // a resume produced.
            var candidate = candidate().role("Backend Engineer").years(3).seniority(Seniority.MID)
                    .location("Bengaluru").skills("java", "spring-boot", "postgresql").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            assertThat(card.isAvailable()).isTrue();
            assertThat(card.compatibility()).isGreaterThanOrEqualTo(85);
            assertThat(card.confidence().unknown()).contains(MatchDimension.WORK_MODE);
        }

        @Test
        @DisplayName("15 — an overqualified candidate is eligible but does not top the list")
        void strongExperienceWrongSeniority() {
            var job = job("Software Engineer Graduate").city("Bengaluru").workMode(WorkMode.ONSITE)
                    .seniority(Seniority.ENTRY).experience(0, 1)
                    .required("Java").build();
            var candidate = candidate().role("Backend Developer").years(8).seniority(Seniority.SENIOR)
                    .location("Bengaluru").skills("java", "spring-boot", "kubernetes").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));

            // Overqualification is not a stated condition, so it never blocks.
            assertThat(card.blockers()).isEmpty();
            assertThat(card.score(MatchDimension.SENIORITY)).isLessThan(60);
            assertThat(card.score(MatchDimension.EXPERIENCE)).isLessThan(60);
            assertThat(card.compatibility()).isLessThan(85);
        }
    }

    // =====================================================================
    // The specific behaviours called out in the brief
    // =====================================================================

    @Nested
    @DisplayName("skill tiers")
    class Tiers {

        @Test
        @DisplayName("a required skill dominates the skill score")
        void requiredDominates() {
            var job = job("Engineer").required("Java").preferred("Kafka").optional("Docker").build();
            var hasRequiredOnly = candidate().role("Engineer").skills("java").build();
            var hasTheRest = candidate().role("Engineer").skills("kafka", "docker").build();

            assertThat(scorer.score(hasRequiredOnly, ScorableRoles.of(job)).score(MatchDimension.SKILLS))
                    .as("one required skill outweighs a preferred and an optional together")
                    .isGreaterThan(scorer.score(hasTheRest, ScorableRoles.of(job)).score(MatchDimension.SKILLS));
        }

        @Test
        @DisplayName("a preferred gap is reported separately from a required gap")
        void preferredGapIsDistinct() {
            var job = job("Engineer").required("Java").preferred("Kafka").build();
            var candidate = candidate().role("Engineer").skills("java").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));
            assertThat(card.missingRequiredSkills()).isEmpty();
            assertThat(card.missingPreferredSkills()).containsExactly("Kafka");
            assertThat(card.eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE_WITH_GAPS);
        }

        @Test
        @DisplayName("optional gaps are not listed as failures")
        void optionalGapsAreQuiet() {
            var job = job("Engineer").required("Java")
                    .optional("Docker", "Kubernetes", "Terraform").build();
            var candidate = candidate().role("Engineer").skills("java").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));
            assertThat(componentLabels(card, ComponentKind.GAP))
                    .as("a posting naming three technologies in passing is not three failures")
                    .doesNotContain("Docker", "Kubernetes", "Terraform");
        }

        @Test
        @DisplayName("absent tiers earn no free points")
        void absentTiersEarnNothing() {
            var requiredOnly = job("Engineer").required("Java", "Python").build();
            var candidate = candidate().role("Engineer").skills("java").build();

            // Half the required skills, and no preferred or optional list to
            // inflate the total: exactly half.
            assertThat(scorer.score(candidate, ScorableRoles.of(requiredOnly)).score(MatchDimension.SKILLS))
                    .isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("unknown data")
    class Unknowns {

        @Test
        @DisplayName("nothing unknown ever scores a default")
        void noDefaultScore() {
            var job = job("Engineer").build();
            var candidate = candidate().role("Engineer").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));
            // rules-1 gave 70 here, which was both a pass and the visibility
            // floor, so an empty comparison looked like a moderate match.
            assertThat(card.score(MatchDimension.SKILLS)).isZero();
            assertThat(card.score(MatchDimension.EXPERIENCE)).isZero();
        }

        @Test
        @DisplayName("an unknown dimension is excluded rather than scored")
        void unknownIsExcludedFromTheAverage() {
            var withSeniority = job("Backend Developer").city("Bengaluru").workMode(WorkMode.HYBRID)
                    .seniority(Seniority.MID).experience(2, 5).required("Java").build();
            var withoutSeniority = job("Backend Developer").city("Bengaluru").workMode(WorkMode.HYBRID)
                    .experience(2, 5).required("Java").build();
            var candidate = candidate().role("Backend Developer").years(3).seniority(Seniority.MID)
                    .location("Bengaluru").workModes(WorkMode.HYBRID).skills("java").build();

            var scored = scorer.score(candidate, ScorableRoles.of(withSeniority));
            var unknown = scorer.score(candidate, ScorableRoles.of(withoutSeniority));

            // Both are perfect on everything comparable, so dropping a dimension
            // must not change the result — only the confidence.
            assertThat(unknown.compatibility()).isEqualTo(scored.compatibility());
            assertThat(unknown.confidence().coveragePercent())
                    .isLessThan(scored.confidence().coveragePercent());
        }

        @Test
        @DisplayName("an unknown says whose gap it is")
        void unknownIdentifiesTheSide() {
            // The posting states everything; the profile does not. Every unknown
            // here is therefore the candidate's, and must be phrased as something
            // they can act on rather than as a fact about the posting.
            var job = job("Engineer").city("Bengaluru").workMode(WorkMode.HYBRID)
                    .seniority(Seniority.MID).experience(2, 5).required("Java").build();
            var candidate = candidate().role("Engineer").skills("java").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));
            assertThat(componentLabels(card, ComponentKind.UNKNOWN))
                    .as("an unknown the student can fix must address the student")
                    .anyMatch(label -> label.toLowerCase().contains("your profile")
                            || label.toLowerCase().contains("you have not"));
        }

        @Test
        @DisplayName("an unknown the posting caused is phrased as the posting's silence")
        void unknownIdentifiesTheJobSide() {
            var job = job("Engineer").build();
            var candidate = candidate().role("Engineer").years(3).seniority(Seniority.MID)
                    .location("Bengaluru").workModes(WorkMode.HYBRID).skills("java").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));
            assertThat(componentLabels(card, ComponentKind.UNKNOWN))
                    .anyMatch(label -> label.toLowerCase().contains("this posting"));
        }
    }

    @Nested
    @DisplayName("eligibility")
    class Eligibility {

        @Test
        @DisplayName("a hybrid role in another city never blocks")
        void hybridElsewhereDoesNotBlock() {
            var job = job("Backend Developer").city("Toronto").workMode(WorkMode.HYBRID)
                    .required("Java").build();
            var candidate = candidate().role("Backend Developer").location("Bengaluru")
                    .skills("java").build();

            assertThat(scorer.score(candidate, ScorableRoles.of(job)).blockers()).isEmpty();
        }

        @Test
        @DisplayName("an on-site role elsewhere does not block a candidate open to relocation")
        void relocationClearsTheBlocker() {
            var job = job("Backend Developer").city("Toronto").workMode(WorkMode.ONSITE)
                    .required("Java").build();
            var candidate = candidate().role("Backend Developer").location("Bengaluru")
                    .openToRelocation().skills("java").build();

            assertThat(scorer.score(candidate, ScorableRoles.of(job)).blockers()).isEmpty();
        }

        @Test
        @DisplayName("a shortfall under the threshold is a gap, not a blocker")
        void smallShortfallIsAGap() {
            var job = job("Backend Developer").city("Bengaluru").workMode(WorkMode.HYBRID)
                    .experience(2, null).required("Java").build();
            var candidate = candidate().role("Backend Developer").years(1).location("Bengaluru")
                    .skills("java").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));
            assertThat(card.blockers()).isEmpty();
            assertThat(componentLabels(card, ComponentKind.GAP))
                    .anyMatch(label -> label.contains("below the stated minimum"));
        }

        @Test
        @DisplayName("no quantity of missing skills produces a blocker")
        void missingEverythingStillDoesNotBlock() {
            var job = job("Backend Developer").city("Bengaluru").workMode(WorkMode.HYBRID)
                    .required("Java", "Python", "Go", "Rust", "Scala").build();
            var candidate = candidate().role("Backend Developer").location("Bengaluru")
                    .skills("cobol").build();

            var card = scorer.score(candidate, ScorableRoles.of(job));
            assertThat(card.blockers()).isEmpty();
            assertThat(card.eligibility()).isEqualTo(EligibilityStatus.ELIGIBLE_WITH_GAPS);
        }
    }

    @Test
    @DisplayName("scoring the same pair twice gives the same answer")
    void deterministic() {
        var job = job("Backend Developer").city("Bengaluru").workMode(WorkMode.HYBRID)
                .seniority(Seniority.MID).experience(2, 5).required("Java", "Spring Boot").build();
        var candidate = candidate().role("Backend Developer").years(3).seniority(Seniority.MID)
                .location("Bengaluru").workModes(WorkMode.HYBRID).skills("java").build();

        assertThat(scorer.score(candidate, ScorableRoles.of(job)).compatibility())
                .isEqualTo(scorer.score(candidate, ScorableRoles.of(job)).compatibility());
    }

    // =====================================================================
    // Fixtures
    // =====================================================================

    private static CareerFluxProperties properties() {
        return new CareerFluxProperties(null, null, null, null,
                new CareerFluxProperties.Matching(70, 70, 85, 95, 400, 20_000,
                        40, 20, 15, 10, 10, 5, 2.0, 5000),
                new CareerFluxProperties.Demo(false, false),
                null /* rate limits: not exercised here */,
                true /* background work: not exercised here */);
    }

    private static List<String> componentLabels(MatchScorer.Scorecard card, ComponentKind kind) {
        return card.components().stream()
                .filter(component -> component.getKind() == kind)
                .map(component -> component.getLabel())
                .toList();
    }

    private static JobBuilder job(String title) {
        return new JobBuilder(title);
    }

    private static CandidateBuilder candidate() {
        return new CandidateBuilder();
    }

    /** Builds a job with skills already at the tier a classifier would have assigned. */
    private static final class JobBuilder {
        private final Job job = new Job();
        private final List<JobSkill> skills = new ArrayList<>();

        JobBuilder(String title) {
            job.setTitle(title);
            job.setNormalizedTitle(title.toLowerCase(java.util.Locale.ROOT));
        }

        JobBuilder city(String city) {
            job.setCity(city);
            job.setLocationRaw(city);
            return this;
        }

        JobBuilder workMode(WorkMode mode) {
            job.setWorkMode(mode);
            return this;
        }

        JobBuilder seniority(Seniority seniority) {
            job.setSeniority(seniority);
            return this;
        }

        JobBuilder experience(Integer min, Integer max) {
            job.setMinExperienceYears(min == null ? null : BigDecimal.valueOf(min));
            job.setMaxExperienceYears(max == null ? null : BigDecimal.valueOf(max));
            return this;
        }

        JobBuilder required(String... names) {
            return add(SkillRequirement.REQUIRED, names);
        }

        JobBuilder preferred(String... names) {
            return add(SkillRequirement.PREFERRED, names);
        }

        JobBuilder optional(String... names) {
            return add(SkillRequirement.OPTIONAL, names);
        }

        private JobBuilder add(SkillRequirement tier, String... names) {
            for (String name : names) {
                Skill skill = new Skill();
                skill.setCanonicalName(name);
                skill.setSlug(name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
                JobSkill jobSkill = new JobSkill();
                jobSkill.setJob(job);
                jobSkill.setSkill(skill);
                jobSkill.setRequirement(tier);
                skills.add(jobSkill);
            }
            return this;
        }

        Job build() {
            job.getSkills().addAll(skills);
            return job;
        }
    }

    private static final class CandidateBuilder {
        private String primaryRole;
        private final List<String> targetRoles = new ArrayList<>();
        private Seniority seniority = Seniority.UNSPECIFIED;
        private BigDecimal years;
        private String location;
        private final Set<String> skills = new LinkedHashSet<>();
        private final Set<String> preferredLocations = new LinkedHashSet<>();
        private final Set<WorkMode> workModes = new LinkedHashSet<>();
        private boolean openToRelocation;

        CandidateBuilder role(String role) {
            this.primaryRole = role;
            this.targetRoles.add(role);
            return this;
        }

        CandidateBuilder years(int value) {
            this.years = BigDecimal.valueOf(value);
            return this;
        }

        CandidateBuilder seniority(Seniority value) {
            this.seniority = value;
            return this;
        }

        CandidateBuilder location(String value) {
            this.location = value;
            return this;
        }

        CandidateBuilder skills(String... slugs) {
            this.skills.addAll(List.of(slugs));
            return this;
        }

        CandidateBuilder workModes(WorkMode... modes) {
            this.workModes.addAll(List.of(modes));
            return this;
        }

        CandidateBuilder openToRelocation() {
            this.openToRelocation = true;
            return this;
        }

        CandidateSnapshot build() {
            return new CandidateSnapshot(UUID.randomUUID(), UUID.randomUUID(), "Test Candidate",
                    primaryRole, seniority, years, location, skills, targetRoles,
                    preferredLocations, workModes, Set.<EmploymentType>of(),
                    openToRelocation, true, true);
        }
    }
}
