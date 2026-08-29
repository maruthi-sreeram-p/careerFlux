package com.careerflux.ai.dto;

import java.util.List;

/**
 * The structured shape the model is asked to return when reading a resume.
 *
 * <p>Nothing here is written to the profile without the candidate seeing it
 * first: the onboarding flow shows the extraction for review and correction.
 * Field names are deliberately plain, because they become the JSON schema the
 * model is given.
 */
public record ExtractedResume(
        String fullName,
        String email,
        String phone,
        String location,
        String headline,
        String summary,
        String primaryRole,
        String seniority,
        Double yearsExperience,
        String linkedinUrl,
        String githubUrl,
        String portfolioUrl,
        List<String> skills,
        List<ExtractedExperience> experiences,
        List<ExtractedEducation> education) {

    public record ExtractedExperience(
            String companyName,
            String title,
            String location,
            String startDate,
            String endDate,
            Boolean current,
            String description) {
    }

    public record ExtractedEducation(
            String institution,
            String degree,
            String fieldOfStudy,
            Integer startYear,
            Integer endYear,
            String grade) {
    }
}
