package com.careerflux.candidate.service;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.careerflux.candidate.domain.CandidateEducation;
import com.careerflux.candidate.domain.CandidateExperience;
import com.careerflux.candidate.domain.CandidatePreferenceValue;
import com.careerflux.candidate.domain.CandidatePreferences;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.CandidateSkill;
import com.careerflux.candidate.domain.PreferenceType;
import com.careerflux.candidate.domain.Resume;
import com.careerflux.candidate.dto.CandidateDtos.CandidateProfileResponse;
import com.careerflux.candidate.dto.CandidateDtos.EducationItem;
import com.careerflux.candidate.dto.CandidateDtos.ExperienceItem;
import com.careerflux.candidate.dto.CandidateDtos.PreferencesPayload;
import com.careerflux.candidate.dto.CandidateDtos.ResumeSummary;
import com.careerflux.candidate.dto.CandidateDtos.SkillItem;

import org.springframework.stereotype.Component;

/** Entity to DTO translation for the candidate aggregate. */
@Component
public class CandidateMapper {

    public CandidateProfileResponse toResponse(CandidateProfile profile,
                                               List<CandidateSkill> skills,
                                               List<CandidatePreferenceValue> preferenceValues,
                                               Resume resume) {
        return new CandidateProfileResponse(
                profile.getId(),
                profile.getUser().getFullName(),
                profile.getUser().getEmail(),
                profile.getHeadline(),
                profile.getSummary(),
                profile.getLocation(),
                profile.getPhone(),
                profile.getLinkedinUrl(),
                profile.getGithubUrl(),
                profile.getPortfolioUrl(),
                profile.getPrimaryRole(),
                profile.getSeniority() == null ? null : profile.getSeniority().name(),
                profile.getYearsExperience(),
                profile.getOnboardingStage().name(),
                profile.getProfileCompleteness(),
                skills.stream().map(this::toSkillItem).toList(),
                profile.getExperiences().stream()
                        .sorted(Comparator.comparingInt(CandidateExperience::getDisplayOrder))
                        .map(this::toExperienceItem).toList(),
                profile.getEducation().stream()
                        .sorted(Comparator.comparingInt(CandidateEducation::getDisplayOrder))
                        .map(this::toEducationItem).toList(),
                toPreferences(profile.getPreferences(), preferenceValues),
                resume == null ? null : toResumeSummary(resume),
                // Null when nobody has recorded one. The screen says "not
                // provided" rather than showing a zero that looks measured.
                profile.getCgpa(),
                profile.getCgpaScale(),
                profile.getCgpaSource() == null ? null : profile.getCgpaSource().name(),
                profile.getVerifiedCgpa() != null);
    }

    public SkillItem toSkillItem(CandidateSkill skill) {
        return new SkillItem(
                skill.getId(),
                skill.getSkill().getCanonicalName(),
                skill.getSkill().getSlug(),
                skill.getSkill().getCategory().name(),
                skill.getProficiency(),
                skill.getYears(),
                skill.getOrigin().name(),
                skill.getEvidence());
    }

    public ExperienceItem toExperienceItem(CandidateExperience experience) {
        return new ExperienceItem(
                experience.getId(),
                experience.getCompanyName(),
                experience.getTitle(),
                experience.getLocation(),
                experience.getStartDate(),
                experience.getEndDate(),
                experience.isCurrent(),
                experience.getDescription());
    }

    public EducationItem toEducationItem(CandidateEducation education) {
        return new EducationItem(
                education.getId(),
                education.getInstitution(),
                education.getDegree(),
                education.getFieldOfStudy(),
                education.getStartYear(),
                education.getEndYear(),
                education.getGrade());
    }

    public ResumeSummary toResumeSummary(Resume resume) {
        return new ResumeSummary(
                resume.getId(),
                resume.getOriginalFilename(),
                resume.getContentType(),
                resume.getSizeBytes(),
                resume.getParseStatus().name(),
                resume.getParseEngine(),
                resume.getParseError(),
                resume.getUploadedAt(),
                resume.getParsedAt());
    }

    public PreferencesPayload toPreferences(CandidatePreferences preferences,
                                            List<CandidatePreferenceValue> values) {
        Map<PreferenceType, List<String>> grouped = new EnumMap<>(PreferenceType.class);
        for (PreferenceType type : PreferenceType.values()) {
            grouped.put(type, List.of());
        }
        if (values != null) {
            for (PreferenceType type : PreferenceType.values()) {
                grouped.put(type, values.stream()
                        .filter(v -> v.getValueType() == type)
                        .sorted(Comparator.comparingInt(CandidatePreferenceValue::getDisplayOrder))
                        .map(CandidatePreferenceValue::getValue)
                        .toList());
            }
        }
        return new PreferencesPayload(
                grouped.get(PreferenceType.TARGET_ROLE),
                grouped.get(PreferenceType.INDUSTRY),
                grouped.get(PreferenceType.LOCATION),
                grouped.get(PreferenceType.WORK_MODE),
                grouped.get(PreferenceType.EMPLOYMENT_TYPE),
                grouped.get(PreferenceType.PREFERRED_COMPANY),
                preferences == null ? null : preferences.getSalaryMin(),
                preferences == null ? null : preferences.getSalaryMax(),
                preferences == null ? null : preferences.getSalaryCurrency(),
                preferences == null ? null : preferences.getSalaryPeriod(),
                preferences != null && preferences.isOpenToRelocation(),
                preferences == null ? null : preferences.getMinExperienceYears(),
                preferences == null ? null : preferences.getMaxExperienceYears(),
                preferences == null || preferences.isImmediateAlerts(),
                preferences == null || preferences.isDailyDigest());
    }
}
