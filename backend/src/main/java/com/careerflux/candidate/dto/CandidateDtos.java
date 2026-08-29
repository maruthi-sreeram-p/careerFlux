package com.careerflux.candidate.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response shapes for the candidate profile, resume and preferences. */
public final class CandidateDtos {

    private CandidateDtos() {
    }

    public record SkillItem(
            UUID id,
            @NotBlank @Size(max = 60) String name,
            String slug,
            String category,
            String proficiency,
            BigDecimal years,
            String origin,
            String evidence) {
    }

    public record ExperienceItem(
            UUID id,
            @NotBlank @Size(max = 200) String companyName,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 160) String location,
            LocalDate startDate,
            LocalDate endDate,
            boolean current,
            String description) {
    }

    public record EducationItem(
            UUID id,
            @NotBlank @Size(max = 200) String institution,
            @Size(max = 160) String degree,
            @Size(max = 160) String fieldOfStudy,
            Integer startYear,
            Integer endYear,
            @Size(max = 60) String grade) {
    }

    public record PreferencesPayload(
            List<String> targetRoles,
            List<String> industries,
            List<String> locations,
            List<String> workModes,
            List<String> employmentTypes,
            List<String> preferredCompanies,
            BigDecimal salaryMin,
            BigDecimal salaryMax,
            String salaryCurrency,
            String salaryPeriod,
            boolean openToRelocation,
            BigDecimal minExperienceYears,
            BigDecimal maxExperienceYears,
            boolean immediateAlerts,
            boolean dailyDigest) {
    }

    /** Full profile as the candidate sees it. */
    public record CandidateProfileResponse(
            UUID id,
            String fullName,
            String email,
            String headline,
            String summary,
            String location,
            String phone,
            String linkedinUrl,
            String githubUrl,
            String portfolioUrl,
            String primaryRole,
            String seniority,
            BigDecimal yearsExperience,
            String onboardingStage,
            int profileCompleteness,
            List<SkillItem> skills,
            List<ExperienceItem> experiences,
            List<EducationItem> education,
            PreferencesPayload preferences,
            ResumeSummary resume) {
    }

    public record ProfileUpdateRequest(
            @Size(max = 200) String headline,
            @Size(max = 4000) String summary,
            @Size(max = 160) String location,
            @Size(max = 40) String phone,
            @Size(max = 300) String linkedinUrl,
            @Size(max = 300) String githubUrl,
            @Size(max = 300) String portfolioUrl,
            @Size(max = 120) String primaryRole,
            String seniority,
            BigDecimal yearsExperience,
            @Valid List<SkillItem> skills,
            @Valid List<ExperienceItem> experiences,
            @Valid List<EducationItem> education) {
    }

    public record ResumeSummary(
            UUID id,
            String originalFilename,
            String contentType,
            Long sizeBytes,
            String parseStatus,
            String parseEngine,
            String parseError,
            Instant uploadedAt,
            Instant parsedAt) {
    }

    /**
     * What the resume parser produced, before the candidate confirms it. Returned
     * from the upload endpoint so the onboarding flow can show the extraction for
     * review rather than silently overwriting the profile.
     */
    public record ResumeParseResult(
            ResumeSummary resume,
            String engine,
            boolean aiAssisted,
            String notice,
            CandidateProfileResponse profile) {
    }
}
