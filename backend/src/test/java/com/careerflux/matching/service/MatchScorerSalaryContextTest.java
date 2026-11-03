package com.careerflux.matching.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.job.domain.Job;
import com.careerflux.matching.domain.MatchComponent;
import com.careerflux.matching.domain.MatchDimension;
import com.careerflux.matching.role.ScorableRoles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Salary is shown beside the score and must never move it. */
class MatchScorerSalaryContextTest {

    private final MatchScorer scorer = new MatchScorer(new CareerFluxProperties(null, null, null, null,
            new CareerFluxProperties.Matching(70, 75, 85, 95, 400, 20_000,
                    40, 20, 15, 10, 10, 5, 2.0, 5000),
            new CareerFluxProperties.Demo(false, false), null, true));

    @Test
    @DisplayName("pay at or above the expectation is noted, and pay below it is noted")
    void notesBothDirections() {
        assertThat(salaryLabels(job("INR", "YEARLY", 1_200_000), candidate(1_000_000, "INR", "YEARLY")))
                .contains("Pay meets your expectation");
        assertThat(salaryLabels(job("INR", "YEARLY", 800_000), candidate(1_000_000, "INR", "YEARLY")))
                .contains("Pay is below your expectation");
    }

    @Test
    @DisplayName("different currency or period is never compared, so nothing is claimed")
    void incomparableSaysNothing() {
        assertThat(salaryLabels(job("USD", "YEARLY", 90_000), candidate(1_000_000, "INR", "YEARLY"))).isEmpty();
        assertThat(salaryLabels(job("INR", "MONTHLY", 90_000), candidate(1_000_000, "INR", "YEARLY"))).isEmpty();
        assertThat(salaryLabels(job("INR", "YEARLY", null), candidate(1_000_000, "INR", "YEARLY"))).isEmpty();
        assertThat(salaryLabels(job("INR", "YEARLY", 1_200_000), candidate(null, null, null))).isEmpty();
    }

    @Test
    @DisplayName("the score is identical whatever the pay says")
    void doesNotChangeTheScore() {
        var withPay = scorer.score(candidate(1_000_000, "INR", "YEARLY"),
                ScorableRoles.of(job("INR", "YEARLY", 500_000)));
        var withoutPay = scorer.score(candidate(null, null, null),
                ScorableRoles.of(job("INR", "YEARLY", 5_000_000)));
        assertThat(withPay.compatibility()).isEqualTo(withoutPay.compatibility());
        assertThat(withPay.eligibility()).isEqualTo(withoutPay.eligibility());
    }

    private List<String> salaryLabels(Job job, CandidateSnapshot candidate) {
        return scorer.score(candidate, ScorableRoles.of(job)).components().stream()
                .filter(c -> c.getDimension() == MatchDimension.CONTEXT)
                .map(MatchComponent::getLabel)
                .filter(label -> label.startsWith("Pay "))
                .toList();
    }

    private static Job job(String currency, String period, Integer max) {
        Job job = new Job();
        job.setTitle("Backend Developer");
        job.setNormalizedTitle("backend developer");
        job.setSalaryCurrency(currency);
        job.setSalaryPeriod(period);
        job.setSalaryMax(max == null ? null : BigDecimal.valueOf(max));
        return job;
    }

    private static CandidateSnapshot candidate(Integer min, String currency, String period) {
        return new CandidateSnapshot(UUID.randomUUID(), UUID.randomUUID(), "Test Candidate",
                "Backend Developer", Seniority.UNSPECIFIED, null, null, Set.of("java"),
                List.of("Backend Developer"), Set.of(), Set.<WorkMode>of(), Set.<EmploymentType>of(),
                false, true, true, min == null ? null : BigDecimal.valueOf(min), null, currency, period);
    }
}
