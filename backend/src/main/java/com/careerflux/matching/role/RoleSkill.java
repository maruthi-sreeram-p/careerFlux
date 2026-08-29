package com.careerflux.matching.role;

import com.careerflux.job.domain.SkillRequirement;

/**
 * One skill a role asks for, at a stated tier.
 *
 * <p>The flattened form of {@code JobSkill} and {@code CompanyRequirementSkill}.
 * Both store a foreign key into the same canonical {@code skills} table, so the
 * slug here is the same identifier a candidate's skills carry — which is what
 * makes matching an identity check rather than a string comparison, and why
 * "Springboot" on a resume already resolves to "Spring Boot" before it ever
 * reaches the scorer.
 */
public record RoleSkill(String slug, String canonicalName, SkillRequirement tier) {
}
