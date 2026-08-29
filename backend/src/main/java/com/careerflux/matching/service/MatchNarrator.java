package com.careerflux.matching.service;

import java.util.List;
import java.util.stream.Collectors;

import com.careerflux.ai.AiClient;
import com.careerflux.ai.quota.AiQuotaService;
import com.careerflux.common.TextUtils;
import com.careerflux.common.error.AiUnavailableException;
import com.careerflux.job.domain.Job;
import com.careerflux.matching.domain.ComponentKind;
import com.careerflux.matching.domain.MatchComponent;
import com.careerflux.matching.domain.MatchDimension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the short "why this matters" paragraph under a match.
 *
 * <p>The rules-based version always works and is what most matches get. The model
 * is used only to phrase what the scorer already decided — it is handed the
 * computed strengths and gaps and asked to summarise them. It never sees the
 * score before it is final and cannot change it, so the explanation can never
 * drift away from the arithmetic it is explaining.
 */
@Component
public class MatchNarrator {

    private static final Logger log = LoggerFactory.getLogger(MatchNarrator.class);
    public static final String RULES_ENGINE = "rules";

    private static final String SYSTEM_PROMPT = """
            You write one short paragraph explaining why a job fits a candidate.

            Rules:
            - Use only the strengths and gaps you are given. Do not add any other claim.
            - Two or three sentences. Plain, direct, second person ("your", "you").
            - Name the single strongest alignment first, then the most important gap if there is one.
            - No greeting, no sign-off, no bullet points, no restating the percentage.
            - Do not flatter the candidate and do not sell the job.
            """;

    private final AiClient aiClient;
    private final AiQuotaService quotaService;

    public MatchNarrator(AiClient aiClient, AiQuotaService quotaService) {
        this.aiClient = aiClient;
        this.quotaService = quotaService;
    }

    /**
     * Explains one match.
     *
     * <p>The model only ever improves the prose here; the deterministic
     * narrative is always available and is what the candidate gets when AI is
     * off, failing, or out of allowance. A rematch can produce many visible
     * matches, so a student can reach their daily limit part-way through one
     * run — the remaining matches quietly use the rules narrative rather than
     * the run failing.
     */
    public Narrative write(MatchScorer.Scorecard scorecard, Job job, CandidateSnapshot candidate) {
        String rulesText = rulesNarrative(scorecard, job);

        if (!aiClient.isAvailable()) {
            return new Narrative(rulesText, RULES_ENGINE);
        }
        if (!quotaService.tryConsume(candidate.userId()).allowed()) {
            return new Narrative(rulesText, RULES_ENGINE);
        }
        try {
            String prompt = buildPrompt(scorecard, job, candidate);
            String generated = aiClient.text(SYSTEM_PROMPT, prompt);
            return new Narrative(TextUtils.truncate(generated, 2000), aiClient.modelName());
        } catch (AiUnavailableException ex) {
            quotaService.refund(candidate.userId());
            log.debug("Falling back to the rules narrative: {}", ex.getMessage());
            return new Narrative(rulesText, RULES_ENGINE);
        }
    }

    /**
     * Deterministic explanation, assembled from the components the scorer produced.
     *
     * <p>The named dimension and the examples that follow it must come from the
     * same place. Saying "your strongest alignment is on experience" and then
     * listing three skills is a sentence that contradicts itself, so the leading
     * clause is only used when that dimension actually has strengths to show.
     */
    String rulesNarrative(MatchScorer.Scorecard scorecard, Job job) {
        // Zero when the scorecard is unavailable; the closing remark is then
        // simply omitted rather than promising anything about the fit.
        int score = scorecard.isAvailable() ? scorecard.compatibility() : 0;
        List<String> gaps = labels(scorecard.components(), ComponentKind.GAP);
        List<MatchComponent> strengths = scorecard.components().stream()
                .filter(component -> component.getKind() == ComponentKind.STRENGTH)
                .toList();

        StringBuilder builder = new StringBuilder();
        if (strengths.isEmpty()) {
            builder.append("There is little overlap between this posting and your profile as it stands.");
        } else {
            MatchDimension leading = strongestDimension(scorecard);
            List<String> fromLeading = strengths.stream()
                    .filter(component -> component.getDimension() == leading)
                    .map(MatchComponent::getLabel)
                    .limit(3)
                    .toList();

            if (fromLeading.isEmpty()) {
                // The winning dimension has nothing quotable — a perfect location
                // score, say. Lead with the evidence instead of naming a dimension.
                builder.append("What lines up here: ")
                        .append(joinNaturally(strengths.stream().map(MatchComponent::getLabel)
                                .limit(3).toList()))
                        .append('.');
            } else {
                builder.append("Your strongest alignment here is ")
                        .append(describe(leading)).append(": ")
                        .append(joinNaturally(fromLeading)).append('.');
            }
        }

        if (!gaps.isEmpty()) {
            builder.append(gaps.size() == 1 ? " The main gap is " : " The main gaps are ")
                    .append(joinNaturally(gaps.stream().limit(2).toList())).append('.');
        }

        if (score >= 85) {
            builder.append(" On the evidence in your profile, this is worth a close look.");
        } else if (score >= 70) {
            builder.append(" Worth reading in full before deciding.");
        }
        return builder.toString();
    }

    private String buildPrompt(MatchScorer.Scorecard scorecard, Job job, CandidateSnapshot candidate) {
        int score = scorecard.isAvailable() ? scorecard.compatibility() : 0;
        String strengths = labels(scorecard.components(), ComponentKind.STRENGTH).stream()
                .limit(8).collect(Collectors.joining(", "));
        String gaps = labels(scorecard.components(), ComponentKind.GAP).stream()
                .limit(5).collect(Collectors.joining(", "));

        return """
                Job title: %s
                Company: %s
                Location: %s

                Candidate target role: %s
                Overall fit: %d out of 100 (skills %d, experience %d, role %d, location %d, seniority %d)

                Strengths: %s
                Gaps: %s
                """.formatted(
                nullSafe(job.getTitle()),
                job.getCompany() == null ? "Not stated" : job.getCompany().getName(),
                nullSafe(job.getLocationRaw()),
                candidate.targetRoles().isEmpty()
                        ? nullSafe(candidate.primaryRole())
                        : String.join(", ", candidate.targetRoles()),
                score, scorecard.score(MatchDimension.SKILLS), scorecard.score(MatchDimension.EXPERIENCE),
                scorecard.score(MatchDimension.ROLE), scorecard.score(MatchDimension.LOCATION), scorecard.score(MatchDimension.SENIORITY),
                strengths.isEmpty() ? "none identified" : strengths,
                gaps.isEmpty() ? "none identified" : gaps);
    }

    /**
     * The highest-scoring dimension. Skills are checked first so that a tie —
     * common, because several dimensions cap at 100 — resolves to the one a
     * candidate finds most useful to hear about.
     */
    private MatchDimension strongestDimension(MatchScorer.Scorecard scorecard) {
        int best = Math.max(scorecard.score(MatchDimension.SKILLS),
                Math.max(scorecard.score(MatchDimension.EXPERIENCE),
                        Math.max(scorecard.score(MatchDimension.ROLE),
                                Math.max(scorecard.score(MatchDimension.LOCATION), scorecard.score(MatchDimension.SENIORITY)))));
        if (best == scorecard.score(MatchDimension.SKILLS)) {
            return MatchDimension.SKILLS;
        }
        if (best == scorecard.score(MatchDimension.ROLE)) {
            return MatchDimension.ROLE;
        }
        if (best == scorecard.score(MatchDimension.EXPERIENCE)) {
            return MatchDimension.EXPERIENCE;
        }
        if (best == scorecard.score(MatchDimension.LOCATION)) {
            return MatchDimension.LOCATION;
        }
        return MatchDimension.SENIORITY;
    }

    private String describe(MatchDimension dimension) {
        return switch (dimension) {
            case SKILLS -> "on skills";
            case ROLE -> "on the role itself";
            case EXPERIENCE -> "on experience";
            case LOCATION -> "on location";
            case SENIORITY -> "on seniority";
            case WORK_MODE -> "on how the role is worked";
            case CONTEXT -> "on the details";
        };
    }

    private List<String> labels(List<MatchComponent> components, ComponentKind kind) {
        return components.stream()
                .filter(component -> component.getKind() == kind)
                .map(MatchComponent::getLabel)
                .toList();
    }

    private String joinNaturally(List<String> values) {
        if (values.isEmpty()) {
            return "";
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        return String.join(", ", values.subList(0, values.size() - 1))
                + " and " + values.get(values.size() - 1);
    }

    private String nullSafe(String value) {
        return value == null ? "Not stated" : value;
    }

    public record Narrative(String text, String engine) {
    }
}
