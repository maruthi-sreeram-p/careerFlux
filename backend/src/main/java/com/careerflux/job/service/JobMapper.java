package com.careerflux.job.service;

import java.util.List;
import java.util.Map;

import com.careerflux.common.ApplyUrl;
import com.careerflux.engagement.domain.InteractionType;
import com.careerflux.engagement.domain.JobInteraction;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobChange;
import com.careerflux.job.domain.JobObservation;
import com.careerflux.job.domain.JobSkill;
import com.careerflux.job.dto.JobDtos.ChangeEntry;
import com.careerflux.job.dto.JobDtos.CompanyRef;
import com.careerflux.job.dto.JobDtos.ExperienceRange;
import com.careerflux.job.dto.JobDtos.InteractionState;
import com.careerflux.job.dto.JobDtos.JobDetail;
import com.careerflux.job.dto.JobDtos.JobSummary;
import com.careerflux.job.dto.JobDtos.MatchAnalysis;
import com.careerflux.job.dto.JobDtos.MatchComponentView;
import com.careerflux.job.dto.JobDtos.ProvenanceEntry;
import com.careerflux.job.dto.JobDtos.SalaryRange;
import com.careerflux.job.dto.JobDtos.SkillRef;
import com.careerflux.matching.domain.ComponentKind;
import com.careerflux.matching.domain.JobMatch;
import com.careerflux.matching.domain.MatchComponent;
import com.careerflux.source.domain.Company;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.SourceType;

import org.springframework.stereotype.Component;

/** Entity to DTO translation for jobs, matches and provenance. */
@Component
public class JobMapper {

    private static final int TOP_SKILLS = 6;

    public JobSummary toSummary(Job job,
                                List<JobSkill> skills,
                                List<JobObservation> observations,
                                JobMatch match,
                                List<MatchComponent> components,
                                List<JobInteraction> interactions,
                                int recentChangeCount) {
        JobObservation primary = observations.isEmpty() ? null : observations.get(0);
        JobSource primarySource = primary == null ? null : primary.getSource();

        return new JobSummary(
                job.getId(),
                job.getTitle(),
                toCompanyRef(job.getCompany()),
                job.getLocationRaw(),
                job.getCity(),
                job.getWorkMode().name(),
                job.getEmploymentType().name(),
                job.getSeniority().name(),
                new ExperienceRange(job.getMinExperienceYears(), job.getMaxExperienceYears()),
                toSalary(job),
                job.getStatus().name(),
                job.getPostedAt(),
                job.getFirstObservedAt(),
                job.getLastObservedAt(),
                (int) observations.stream().filter(JobObservation::isActive).count(),
                primarySource == null ? null : primarySource.getName(),
                primarySource == null ? null : primarySource.getSourceType().name(),
                isSampleData(observations),
                skills.stream().limit(TOP_SKILLS).map(this::toSkillRef).toList(),
                toMatchAnalysis(match, components),
                toInteractionState(interactions),
                recentChangeCount);
    }

    public JobDetail toDetail(JobSummary summary,
                              Job job,
                              List<JobSkill> skills,
                              List<JobObservation> observations,
                              List<JobChange> changes) {
        return new JobDetail(
                summary,
                job.getDescription(),
                job.getResponsibilities(),
                job.getRequirements(),
                // Rows stored before apply links were validated are still in
                // the table, so the read path guards too. An unusable link is
                // reported as absent rather than handed to the browser.
                ApplyUrl.sanitize(job.getApplyUrl()),
                skills.stream().map(this::toSkillRef).toList(),
                observations.stream().map(this::toProvenance).toList(),
                changes.stream().map(this::toChangeEntry).toList());
    }

    public CompanyRef toCompanyRef(Company company) {
        if (company == null) {
            return null;
        }
        return new CompanyRef(company.getId(), company.getName(), company.getLogoUrl(),
                company.getWebsite(), company.getIndustry());
    }

    public SkillRef toSkillRef(JobSkill jobSkill) {
        return new SkillRef(
                jobSkill.getSkill().getCanonicalName(),
                jobSkill.getSkill().getSlug(),
                jobSkill.getSkill().getCategory().name(),
                jobSkill.getRequirement().name(),
                jobSkill.getExtractedBy().name());
    }

    public ProvenanceEntry toProvenance(JobObservation observation) {
        JobSource source = observation.getSource();
        var policy = source.getAccessPolicy();
        return new ProvenanceEntry(
                source.getId(),
                source.getName(),
                source.getSourceType().name(),
                source.getAtsProvider().name(),
                source.getDiscoveryMethod().name(),
                observation.getSourceUrl(),
                source.getState().name(),
                source.getHealthStatus().name(),
                policy == null ? null : policy.getAccessPolicy().name(),
                policy == null ? null : policy.getVerifiedAt(),
                observation.getFirstObservedAt(),
                observation.getLastObservedAt(),
                observation.getObservationCount(),
                observation.isActive(),
                source.getSourceType() == SourceType.LOCAL_FIXTURE);
    }

    public ChangeEntry toChangeEntry(JobChange change) {
        return new ChangeEntry(
                change.getChangeType().name(),
                change.getSummary(),
                change.getFieldName(),
                change.getPreviousValue(),
                change.getNewValue(),
                change.getDetectedAt());
    }

    /** A match is never returned as a bare number; the components always travel with it. */
    public MatchAnalysis toMatchAnalysis(JobMatch match, List<MatchComponent> components) {
        if (match == null) {
            return null;
        }
        List<MatchComponent> source = components == null ? match.getComponents() : components;
        return new MatchAnalysis(
                match.getOverallScore(),
                match.getTier().name(),
                match.getSkillScore(),
                match.getExperienceScore(),
                match.getRoleScore(),
                match.getLocationScore(),
                match.getSeniorityScore(),
                viewsOf(source, ComponentKind.STRENGTH),
                viewsOf(source, ComponentKind.GAP),
                viewsOf(source, ComponentKind.NEUTRAL),
                match.getNarrative(),
                match.getNarrativeEngine(),
                match.getScorerVersion(),
                match.getComputedAt());
    }

    public InteractionState toInteractionState(List<JobInteraction> interactions) {
        if (interactions == null || interactions.isEmpty()) {
            return new InteractionState(false, false, false, null);
        }
        Map<InteractionType, JobInteraction> byType = interactions.stream()
                .collect(java.util.stream.Collectors.toMap(JobInteraction::getInteractionType,
                        interaction -> interaction, (first, second) -> first));
        JobInteraction applied = byType.get(InteractionType.APPLIED);
        return new InteractionState(
                byType.containsKey(InteractionType.SAVED),
                byType.containsKey(InteractionType.DISMISSED),
                applied != null,
                applied == null || applied.getApplicationStatus() == null
                        ? null : applied.getApplicationStatus().name());
    }

    private List<MatchComponentView> viewsOf(List<MatchComponent> components, ComponentKind kind) {
        return components.stream()
                .filter(component -> component.getKind() == kind)
                .sorted(java.util.Comparator.comparingInt(MatchComponent::getDisplayOrder))
                .map(component -> new MatchComponentView(
                        component.getKind().name(),
                        component.getDimension().name(),
                        component.getLabel(),
                        component.getDetail()))
                .toList();
    }

    private SalaryRange toSalary(Job job) {
        if (job.getSalaryMin() == null && job.getSalaryMax() == null) {
            return null;
        }
        return new SalaryRange(job.getSalaryMin(), job.getSalaryMax(),
                job.getSalaryCurrency(), job.getSalaryPeriod());
    }

    /** True when every sighting of this job came from bundled sample data. */
    private boolean isSampleData(List<JobObservation> observations) {
        return !observations.isEmpty() && observations.stream()
                .allMatch(observation -> observation.getSource().getSourceType() == SourceType.LOCAL_FIXTURE);
    }
}
