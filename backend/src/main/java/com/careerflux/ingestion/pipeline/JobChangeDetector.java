package com.careerflux.ingestion.pipeline;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.careerflux.common.TextUtils;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobChange;
import com.careerflux.job.domain.JobChangeType;
import com.careerflux.job.domain.JobObservation;
import com.careerflux.job.repository.JobChangeRepository;

import org.springframework.stereotype.Component;

/**
 * Compares a job against the version CareerFlux already held and records what
 * actually changed.
 *
 * <p>This is what makes the product more than a job board: a candidate can see
 * that a posting added a Spring Boot requirement, moved city, or reopened after
 * being closed. Every change carries a one-line summary written to be readable on
 * its own, so the UI never has to construct prose from field names.
 */
@Component
public class JobChangeDetector {

    private final JobChangeRepository changeRepository;

    public JobChangeDetector(JobChangeRepository changeRepository) {
        this.changeRepository = changeRepository;
    }

    /**
     * Detects and persists the differences between the stored job and an incoming
     * observation of it. Returns the changes recorded, which may be empty.
     */
    public List<JobChange> detect(Job existing, NormalizedJob incoming, JobObservation observation) {
        List<JobChange> changes = new ArrayList<>();

        if (!Objects.equals(existing.getTitle(), incoming.title())
                && TextUtils.hasText(incoming.title())) {
            changes.add(build(existing, observation, JobChangeType.TITLE_CHANGED, "title",
                    existing.getTitle(), incoming.title(),
                    "Title changed to " + incoming.title()));
        }

        if (!equalsIgnoreCaseNullSafe(existing.getLocationRaw(), incoming.locationRaw())
                && TextUtils.hasText(incoming.locationRaw())) {
            changes.add(build(existing, observation, JobChangeType.LOCATION_CHANGED, "location",
                    existing.getLocationRaw(), incoming.locationRaw(),
                    "Location changed from " + describe(existing.getLocationRaw())
                            + " to " + incoming.locationRaw()));
        }

        if (existing.getWorkMode() != incoming.workMode()
                && incoming.workMode() != com.careerflux.common.taxonomy.WorkMode.UNSPECIFIED) {
            changes.add(build(existing, observation, JobChangeType.WORK_MODE_CHANGED, "workMode",
                    existing.getWorkMode().name(), incoming.workMode().name(),
                    "Work mode changed to " + friendly(incoming.workMode().name())));
        }

        if (salaryChanged(existing.getSalaryMin(), incoming.salaryMin())
                || salaryChanged(existing.getSalaryMax(), incoming.salaryMax())) {
            changes.add(build(existing, observation, JobChangeType.SALARY_CHANGED, "salary",
                    formatRange(existing.getSalaryMin(), existing.getSalaryMax()),
                    formatRange(incoming.salaryMin(), incoming.salaryMax()),
                    "Salary range updated"));
        }

        // The description hash is the reliable signal that requirements moved; the
        // text itself is too long and too noisy to diff line by line.
        if (TextUtils.hasText(existing.getContentHash())
                && !Objects.equals(existing.getContentHash(), incoming.contentHash())
                && changes.isEmpty()) {
            changes.add(build(existing, observation, JobChangeType.REQUIREMENTS_CHANGED, "description",
                    null, null, "The posting was edited"));
        }

        return changes.isEmpty() ? List.of() : changeRepository.saveAll(changes);
    }

    public JobChange recordCreated(Job job, JobObservation observation, String sourceName) {
        return changeRepository.save(build(job, observation, JobChangeType.CREATED, null, null, null,
                "First seen on " + sourceName));
    }

    public JobChange recordNewSource(Job job, JobObservation observation, String sourceName) {
        return changeRepository.save(build(job, observation, JobChangeType.NEW_SOURCE_OBSERVED, null, null, null,
                "Also listed on " + sourceName));
    }

    public JobChange recordClosed(Job job, String reason) {
        return changeRepository.save(build(job, null, JobChangeType.CLOSED, null, null, null, reason));
    }

    public JobChange recordReopened(Job job, String sourceName) {
        return changeRepository.save(build(job, null, JobChangeType.REOPENED, null, null, null,
                "Reopened; listed again on " + sourceName));
    }

    private JobChange build(Job job, JobObservation observation, JobChangeType type, String field,
                            String previous, String next, String summary) {
        JobChange change = new JobChange();
        change.setJob(job);
        change.setObservation(observation);
        change.setChangeType(type);
        change.setFieldName(field);
        change.setPreviousValue(TextUtils.truncate(previous, 2000));
        change.setNewValue(TextUtils.truncate(next, 2000));
        change.setSummary(TextUtils.truncate(summary, 600));
        return change;
    }

    private boolean salaryChanged(BigDecimal existing, BigDecimal incoming) {
        if (existing == null && incoming == null) {
            return false;
        }
        // A source dropping a salary it used to publish is not a salary change.
        if (incoming == null) {
            return false;
        }
        return existing == null || existing.compareTo(incoming) != 0;
    }

    private boolean equalsIgnoreCaseNullSafe(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.strip().equalsIgnoreCase(right.strip());
    }

    private String formatRange(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return null;
        }
        return (min == null ? "?" : min.toPlainString()) + " - " + (max == null ? "?" : max.toPlainString());
    }

    private String describe(String value) {
        return TextUtils.hasText(value) ? value : "unspecified";
    }

    private String friendly(String enumName) {
        String lower = enumName.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
