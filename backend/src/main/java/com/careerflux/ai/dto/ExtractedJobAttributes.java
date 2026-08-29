package com.careerflux.ai.dto;

import java.util.List;

/**
 * Structured attributes read out of a free-text job description.
 *
 * <p>Enrichment only ever <em>fills gaps</em>. If the source feed already stated
 * the employment type or the location, the feed wins: a model guess must never
 * overwrite a fact the publisher gave us.
 */
public record ExtractedJobAttributes(
        String normalizedTitle,
        String seniority,
        String employmentType,
        String workMode,
        Double minExperienceYears,
        Double maxExperienceYears,
        List<String> requiredSkills,
        List<String> preferredSkills,
        String responsibilitiesSummary,
        String requirementsSummary) {
}
