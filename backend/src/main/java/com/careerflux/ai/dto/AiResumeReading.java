package com.careerflux.ai.dto;

import java.util.List;

/**
 * What a model is asked to return when reading a resume.
 *
 * <p>Deliberately without a name, email, phone, location or profile link. The
 * text the model reads has had those removed ({@link com.careerflux.ai.ResumeRedactor}),
 * and asking for fields that cannot be in the input would only invite the model
 * to invent them. The contact details a student's profile does use are read on
 * this server, from the original document, by the local parser.
 */
public record AiResumeReading(
        String headline,
        String summary,
        String primaryRole,
        String seniority,
        Double yearsExperience,
        List<String> skills,
        List<ExtractedResume.ExtractedExperience> experiences,
        List<ExtractedResume.ExtractedEducation> education) {
}
