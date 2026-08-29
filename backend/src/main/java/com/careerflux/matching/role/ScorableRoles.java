package com.careerflux.matching.role;

import java.math.BigDecimal;
import java.util.List;

import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.job.domain.Job;
import com.careerflux.requirement.domain.CompanyRequirement;

/**
 * Adapts the two things CareerFlux scores candidates against.
 *
 * <p>Adapters rather than making the entities implement the interface
 * themselves. {@code Job} is an ingestion concern and {@code CompanyRequirement}
 * an institutional one; neither should grow a matching interface to satisfy the
 * scorer, and keeping the mapping here means the flattening of skill rows lives
 * in one obvious place.
 */
public final class ScorableRoles {

    private ScorableRoles() {
    }

    /** An ingested market posting. */
    public static ScorableRole of(Job job) {
        return new JobRole(job);
    }

    /** A hiring brief a placement officer recorded. */
    public static ScorableRole of(CompanyRequirement requirement) {
        return new RequirementRole(requirement);
    }

    private record JobRole(Job job) implements ScorableRole {

        @Override
        public String title() {
            return job.getTitle();
        }

        @Override
        public String normalizedTitle() {
            return job.getNormalizedTitle();
        }

        @Override
        public Seniority seniority() {
            return job.getSeniority();
        }

        @Override
        public WorkMode workMode() {
            return job.getWorkMode();
        }

        @Override
        public String locationRaw() {
            return job.getLocationRaw();
        }

        @Override
        public String city() {
            return job.getCity();
        }

        @Override
        public EmploymentType employmentType() {
            return job.getEmploymentType();
        }

        @Override
        public BigDecimal minExperienceYears() {
            return job.getMinExperienceYears();
        }

        @Override
        public BigDecimal maxExperienceYears() {
            return job.getMaxExperienceYears();
        }

        @Override
        public List<RoleSkill> skills() {
            return job.getSkills().stream()
                    .map(skill -> new RoleSkill(skill.getSkill().getSlug(),
                            skill.getSkill().getCanonicalName(), skill.getRequirement()))
                    .toList();
        }

        @Override
        public String descriptor() {
            return "posting";
        }
    }

    private record RequirementRole(CompanyRequirement requirement) implements ScorableRole {

        @Override
        public String title() {
            return requirement.getRoleTitle();
        }

        /** Authored rather than scraped, so there is no normalized form to fall back from. */
        @Override
        public String normalizedTitle() {
            return null;
        }

        /**
         * A requirement states an experience band rather than a level, and the
         * scorer already treats an unspecified level as unknown rather than as
         * a mismatch. Inventing a seniority from the band would be guessing.
         */
        @Override
        public Seniority seniority() {
            return Seniority.UNSPECIFIED;
        }

        @Override
        public WorkMode workMode() {
            return requirement.getWorkMode();
        }

        @Override
        public String locationRaw() {
            return requirement.getLocationRaw();
        }

        /** No ingestion step resolved a city; the raw location carries it. */
        @Override
        public String city() {
            return null;
        }

        /** Not modelled on a requirement, and not inferred from anything. */
        @Override
        public EmploymentType employmentType() {
            return EmploymentType.UNSPECIFIED;
        }

        @Override
        public BigDecimal minExperienceYears() {
            return requirement.getMinExperienceYears();
        }

        @Override
        public BigDecimal maxExperienceYears() {
            return requirement.getMaxExperienceYears();
        }

        @Override
        public List<RoleSkill> skills() {
            return requirement.getSkills().stream()
                    .map(skill -> new RoleSkill(skill.getSkill().getSlug(),
                            skill.getSkill().getCanonicalName(), skill.getTier()))
                    .toList();
        }

        @Override
        public String descriptor() {
            return "requirement";
        }
    }
}
