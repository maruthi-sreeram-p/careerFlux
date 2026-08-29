package com.careerflux.requirement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The wire shape of a company requirement.
 *
 * <p>Validation lives on the request records rather than in the service so a
 * malformed requirement is refused before any of it is trusted. The bounds are
 * sanity checks, not policy: they catch a slipped decimal point or a pasted
 * essay, and leave the college free to describe whatever the company actually
 * asked for.
 */
public final class RequirementDtos {

    private RequirementDtos() {
    }

    /** A skill and the tier the company put it at. */
    public record SkillRequest(
            @NotBlank @Size(max = 120) String skill,
            @NotBlank @Size(max = 24) String tier) {
    }

    /**
     * @param departmentIds which departments may be considered; empty means the
     *                      whole college rather than nobody
     * @param minCgpa       recorded from the day the requirement is written.
     *                      Nothing evaluates it yet — student CGPA is not
     *                      modelled — so it is stated back to the officer and
     *                      never turned into an eligibility verdict.
     */
    public record CreateRequirement(
            @NotBlank @Size(max = 200) String companyName,
            @NotBlank @Size(max = 200) String roleTitle,
            @Size(max = 20000) String description,
            @DecimalMin("0.0") @DecimalMax("50.0") BigDecimal minExperienceYears,
            @DecimalMin("0.0") @DecimalMax("50.0") BigDecimal maxExperienceYears,
            @Min(1950) @Max(2100) Integer graduationYear,
            @DecimalMin("0.0") @DecimalMax("10.0") BigDecimal minCgpa,
            @Size(max = 24) String workMode,
            @Size(max = 300) String location,
            LocalDate driveDate,
            List<UUID> departmentIds,
            List<SkillRequest> skills) {
    }

    /** Every field optional: a PATCH changes what it names and leaves the rest. */
    public record UpdateRequirement(
            @Size(max = 200) String companyName,
            @Size(max = 200) String roleTitle,
            @Size(max = 20000) String description,
            @DecimalMin("0.0") @DecimalMax("50.0") BigDecimal minExperienceYears,
            @DecimalMin("0.0") @DecimalMax("50.0") BigDecimal maxExperienceYears,
            @Min(1950) @Max(2100) Integer graduationYear,
            @DecimalMin("0.0") @DecimalMax("10.0") BigDecimal minCgpa,
            @Size(max = 24) String workMode,
            @Size(max = 300) String location,
            LocalDate driveDate,
            @Size(max = 24) String status,
            List<UUID> departmentIds,
            List<SkillRequest> skills) {
    }

    public record SkillView(String skill, String slug, String tier) {
    }

    public record DepartmentView(UUID id, String name, String code) {
    }

    /**
     * @param skillsUnresolved skill names the dictionary did not recognise.
     *                         Reported rather than silently dropped: an officer
     *                         who typed a skill CareerFlux cannot match needs to
     *                         know it will not be searched on.
     */
    public record RequirementView(
            UUID id,
            String companyName,
            String roleTitle,
            String description,
            BigDecimal minExperienceYears,
            BigDecimal maxExperienceYears,
            Integer graduationYear,
            BigDecimal minCgpa,
            String workMode,
            String location,
            String status,
            LocalDate driveDate,
            String createdByName,
            Instant createdAt,
            Instant updatedAt,
            List<SkillView> requiredSkills,
            List<SkillView> preferredSkills,
            List<SkillView> optionalSkills,
            List<DepartmentView> departments,
            List<String> skillsUnresolved) {
    }

    public record RequirementPage(
            List<RequirementView> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }
}
