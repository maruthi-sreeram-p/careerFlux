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

    /**
     * One skill on a profile.
     *
     * <p>{@code slug} and {@code category} are null for a skill the shared
     * dictionary does not know: those are kept on the student's own profile only
     * and are never matched. On the way in only the name is read, so a client
     * that round-trips the list keeps both kinds.
     */
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
            ResumeSummary resume,
            /* The figure the student entered themselves. Null when they have not. */
            BigDecimal reportedCgpa,
            /* The college's record, and the only CGPA eligibility reads. Null when none. */
            BigDecimal verifiedCgpa,
            BigDecimal cgpaScale,
            boolean cgpaVerified) {
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

    /**
     * A CGPA being recorded.
     *
     * <p>Bounds are checked against the institution's own scale rather than
     * annotated with a hardcoded ten, so a college on a different scale is not
     * refused by a constant. A null value clears the record, which is different
     * from recording a zero.
     */
    public record AcademicUpdateRequest(BigDecimal cgpa) {
    }

    /**
     * A student's academic record: their own figure and the college's, never one
     * standing in for the other.
     *
     * @param reportedCgpa   what the student entered for themselves; shown as
     *                       theirs and never used to decide eligibility
     * @param verifiedCgpa   what the college recorded; the only figure eligibility
     *                       reads
     * @param verified       whether a verified figure exists
     * @param verifiedByName who at the college recorded it, when one exists
     */
    public record AcademicRecord(
            BigDecimal cgpaScale,
            BigDecimal reportedCgpa,
            Instant reportedAt,
            BigDecimal verifiedCgpa,
            String verifiedByName,
            Instant verifiedAt,
            boolean verified) {
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
            CandidateProfileResponse profile,
            /**
             * The reading waiting for the student, when there is one. Null when
             * the resume produced nothing worth asking about — an empty review
             * screen would be worse than none.
             */
            UUID proposalId) {
    }
}
