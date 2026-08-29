package com.careerflux.matching.role;

import java.math.BigDecimal;
import java.util.List;

import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;

/**
 * Something a candidate can be scored against.
 *
 * <p>CareerFlux compares people to roles in two directions. A student is
 * matched against the ingested job corpus; a company's requirement is matched
 * against the college's students. The arithmetic is identical and must stay
 * identical — two scorers would drift, and the second one would quietly stop
 * meaning what the first one means.
 *
 * <p>This interface is exactly the surface {@code MatchScorer} already read
 * from {@code Job}: eleven accessors, no more. Nothing job-specific is here —
 * no source, no provenance, no dedup key — because a company requirement is not
 * a scraped posting and should not have to pretend to be one.
 *
 * <p>Everything except {@link #title()} and {@link #skills()} may be absent.
 * The scorer already treats an unknown dimension as unknown rather than as a
 * zero, so a requirement that states no work mode simply has that dimension
 * excluded from its confidence, exactly as a posting that states none does.
 */
public interface ScorableRole {

    String title();

    /** A cleaned title where one exists, else null; the scorer falls back to {@link #title()}. */
    String normalizedTitle();

    Seniority seniority();

    WorkMode workMode();

    String locationRaw();

    /** A resolved city where one exists. Ingestion derives this; an authored requirement has none. */
    String city();

    EmploymentType employmentType();

    BigDecimal minExperienceYears();

    BigDecimal maxExperienceYears();

    List<RoleSkill> skills();

    /**
     * What to call this role in the explanations shown to a person.
     *
     * <p>A job is a "posting"; a company requirement is a "requirement". The
     * scorer writes sentences like "This posting does not list any skills", and
     * telling a placement officer that a brief they typed is a posting would be
     * wrong. Only the noun differs — no score, weight, threshold or tier reads
     * this.
     */
    String descriptor();
}
