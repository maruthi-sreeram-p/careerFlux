package com.careerflux.matching.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.careerflux.ai.UnavailableAiClient;
import com.careerflux.job.domain.Job;
import com.careerflux.matching.domain.MatchComponent;
import com.careerflux.matching.domain.MatchDimension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules narrative is what most matches get, and it is prose a candidate
 * reads. It has to be coherent on its own.
 */
class MatchNarratorTest {

    private final MatchNarrator narrator = new MatchNarrator(new UnavailableAiClient(),
            org.mockito.Mockito.mock(com.careerflux.ai.quota.AiQuotaService.class));

    private static Job job() {
        Job job = new Job();
        job.setTitle("Backend Engineer");
        return job;
    }

    /** Builds a rules-2 scorecard with the dimension scores the narrator reads. */
    private static MatchScorer.Scorecard card(int overall, int skills, int experience, int role,
                                              int location, int seniority,
                                              List<MatchComponent> components) {
        java.util.Map<MatchDimension, DimensionResult> dimensions =
                new java.util.EnumMap<>(MatchDimension.class);
        dimensions.put(MatchDimension.SKILLS, DimensionResult.scored(skills));
        dimensions.put(MatchDimension.EXPERIENCE, DimensionResult.scored(experience));
        dimensions.put(MatchDimension.ROLE, DimensionResult.scored(role));
        dimensions.put(MatchDimension.LOCATION, DimensionResult.scored(location));
        dimensions.put(MatchDimension.SENIORITY, DimensionResult.scored(seniority));
        dimensions.put(MatchDimension.WORK_MODE, DimensionResult.unknownJob());
        return new MatchScorer.Scorecard(overall,
                com.careerflux.matching.domain.EligibilityStatus.ELIGIBLE,
                List.of(),
                new MatchScorer.Confidence(
                        com.careerflux.matching.domain.ConfidenceLevel.HIGH, 95, List.of()),
                dimensions, components, List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("the dimension it names and the examples it gives come from the same place")
    void namedDimensionMatchesTheExamples() {
        // Experience is the top score, but only skills have quotable strengths.
        // Saying "on experience: Java, TypeScript" would contradict itself.
        var components = List.of(
                MatchComponent.strength(MatchDimension.SKILLS, "Java", null, 0),
                MatchComponent.strength(MatchDimension.SKILLS, "TypeScript", null, 1),
                MatchComponent.strength(MatchDimension.EXPERIENCE, "2-4 years experience", null, 2));

        String text = narrator.rulesNarrative(card(90, 95, 100, 80, 70, 70, components), job());

        assertThat(text).contains("on experience").contains("2-4 years experience");
        assertThat(text).doesNotContain("on experience: Java");
    }

    @Test
    @DisplayName("when the leading dimension has nothing quotable it leads with the evidence instead")
    void fallsBackWhenTheLeaderIsSilent() {
        // Location scores 100 but contributed no strength row.
        var components = List.of(
                MatchComponent.strength(MatchDimension.SKILLS, "React", null, 0),
                MatchComponent.strength(MatchDimension.SKILLS, "Next.js", null, 1));

        String text = narrator.rulesNarrative(card(80, 70, 70, 70, 100, 70, components), job());

        assertThat(text).startsWith("What lines up here: React and Next.js.");
        assertThat(text).doesNotContain("on location:");
    }

    @Test
    @DisplayName("gaps are pluralised correctly")
    void pluralisesGaps() {
        var one = List.of(
                MatchComponent.strength(MatchDimension.SKILLS, "Java", null, 0),
                MatchComponent.gap(MatchDimension.SKILLS, "AWS", null, 1));
        assertThat(narrator.rulesNarrative(card(80, 80, 70, 70, 70, 70, one), job()))
                .contains("The main gap is AWS.");

        var two = List.of(
                MatchComponent.strength(MatchDimension.SKILLS, "Java", null, 0),
                MatchComponent.gap(MatchDimension.SKILLS, "AWS", null, 1),
                MatchComponent.gap(MatchDimension.SKILLS, "Kafka", null, 2));
        assertThat(narrator.rulesNarrative(card(80, 80, 70, 70, 70, 70, two), job()))
                .contains("The main gaps are AWS and Kafka.");
    }

    @Test
    @DisplayName("a match with no strengths says so plainly rather than inventing one")
    void handlesNoStrengths() {
        var components = List.of(MatchComponent.gap(MatchDimension.SKILLS, "Python", null, 0));
        assertThat(narrator.rulesNarrative(card(30, 20, 40, 10, 30, 40, components), job()))
                .startsWith("There is little overlap");
    }

    @Test
    @DisplayName("the closing line reflects the score band")
    void closingLineTracksTheScore() {
        var components = List.of(MatchComponent.strength(MatchDimension.SKILLS, "Java", null, 0));
        assertThat(narrator.rulesNarrative(card(90, 90, 90, 90, 90, 90, components), job()))
                .endsWith("worth a close look.");
        assertThat(narrator.rulesNarrative(card(75, 75, 75, 75, 75, 75, components), job()))
                .endsWith("Worth reading in full before deciding.");
        assertThat(narrator.rulesNarrative(card(50, 50, 50, 50, 50, 50, components), job()))
                .doesNotContain("worth a close look");
    }

    @Test
    @DisplayName("without a model, the narrative is labelled as rules-written")
    void labelsItsOwnEngine() {
        var components = List.of(MatchComponent.strength(MatchDimension.SKILLS, "Java", null, 0));
        var snapshot = new CandidateSnapshot(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "Test", "Engineer",
                com.careerflux.common.taxonomy.Seniority.MID, null, null,
                java.util.Set.of(), List.of("Engineer"), java.util.Set.of(),
                java.util.Set.of(), java.util.Set.of(), false, true, true);

        var narrative = narrator.write(card(80, 80, 70, 70, 70, 70, components), job(), snapshot);

        assertThat(narrative.engine()).isEqualTo(MatchNarrator.RULES_ENGINE);
        assertThat(narrative.text()).isNotBlank();
    }
}
