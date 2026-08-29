package com.careerflux.ingestion.pipeline;

import java.math.BigDecimal;
import java.time.Instant;

import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;

/**
 * A posting after normalization: the same information as {@link
 * com.careerflux.source.adapter.RawJobPosting}, but with every field in
 * CareerFlux's own vocabulary rather than the source's.
 */
public record NormalizedJob(
        String externalId,
        String requisitionId,
        String title,
        String normalizedTitle,
        String companyName,
        String description,
        String responsibilities,
        String requirements,
        String locationRaw,
        String city,
        String region,
        String country,
        WorkMode workMode,
        EmploymentType employmentType,
        Seniority seniority,
        BigDecimal minExperienceYears,
        BigDecimal maxExperienceYears,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String salaryCurrency,
        String salaryPeriod,
        String applyUrl,
        String sourceUrl,
        Instant postedAt,
        String contentHash,
        String rawPayload) {
}
