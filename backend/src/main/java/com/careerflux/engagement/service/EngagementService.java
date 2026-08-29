package com.careerflux.engagement.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.common.TextUtils;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.engagement.domain.ApplicationStatus;
import com.careerflux.engagement.domain.InteractionType;
import com.careerflux.engagement.domain.JobInteraction;
import com.careerflux.engagement.repository.JobInteractionRepository;
import com.careerflux.job.domain.Job;
import com.careerflux.job.repository.JobRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saving, dismissing and applying.
 *
 * <p>Saving and dismissing are mutually exclusive: saving a dismissed job clears
 * the dismissal, and vice versa, because holding both would make the discovery
 * feed behave unpredictably.
 */
@Service
public class EngagementService {

    private final JobInteractionRepository interactionRepository;
    private final JobRepository jobRepository;
    private final CandidateProfileService profileService;

    public EngagementService(JobInteractionRepository interactionRepository,
                             JobRepository jobRepository,
                             CandidateProfileService profileService) {
        this.interactionRepository = interactionRepository;
        this.jobRepository = jobRepository;
        this.profileService = profileService;
    }

    @Transactional
    public JobInteraction save(UUID userId, UUID jobId, String note) {
        CandidateProfile profile = profileService.requireByUserId(userId);
        clear(profile.getId(), jobId, InteractionType.DISMISSED);
        return upsert(profile, jobId, InteractionType.SAVED, note, null);
    }

    @Transactional
    public void unsave(UUID userId, UUID jobId) {
        CandidateProfile profile = profileService.requireByUserId(userId);
        clear(profile.getId(), jobId, InteractionType.SAVED);
    }

    @Transactional
    public JobInteraction dismiss(UUID userId, UUID jobId, String note) {
        CandidateProfile profile = profileService.requireByUserId(userId);
        clear(profile.getId(), jobId, InteractionType.SAVED);
        return upsert(profile, jobId, InteractionType.DISMISSED, note, null);
    }

    @Transactional
    public void undismiss(UUID userId, UUID jobId) {
        CandidateProfile profile = profileService.requireByUserId(userId);
        clear(profile.getId(), jobId, InteractionType.DISMISSED);
    }

    /**
     * Records that the candidate applied. CareerFlux does not submit applications
     * on anyone's behalf; this is the candidate telling us what they did, so the
     * job stops competing for their attention.
     */
    @Transactional
    public JobInteraction markApplied(UUID userId, UUID jobId, String note, String status) {
        CandidateProfile profile = profileService.requireByUserId(userId);
        ApplicationStatus applicationStatus = parseStatus(status);
        clear(profile.getId(), jobId, InteractionType.DISMISSED);
        return upsert(profile, jobId, InteractionType.APPLIED, note, applicationStatus);
    }

    /**
     * Records that the signed-in candidate looked at a job.
     *
     * <p>Best effort by design. An account with no candidate profile — an
     * administrator browsing the corpus, for instance — simply has no view to
     * record, and that must not stop the page from loading. Returns empty in
     * that case rather than throwing.
     */
    @Transactional
    public Optional<JobInteraction> recordView(UUID userId, UUID jobId) {
        return profileService.findByUserId(userId)
                .map(profile -> upsert(profile, jobId, InteractionType.VIEWED, null, null));
    }

    /**
     * An account with no candidate profile has nothing saved. That is a truthful
     * empty list, not an error, so an administrator can browse the product
     * without every candidate-facing screen returning a 404.
     */
    @Transactional(readOnly = true)
    public List<JobInteraction> list(UUID userId, InteractionType type) {
        return profileService.findByUserId(userId)
                .map(profile -> interactionRepository
                        .findByCandidateIdAndInteractionTypeOrderByCreatedAtDesc(profile.getId(), type))
                .orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public Counts counts(UUID userId) {
        return profileService.findByUserId(userId)
                .map(profile -> new Counts(
                        interactionRepository.countByCandidateIdAndInteractionType(
                                profile.getId(), InteractionType.SAVED),
                        interactionRepository.countByCandidateIdAndInteractionType(
                                profile.getId(), InteractionType.APPLIED),
                        interactionRepository.countByCandidateIdAndInteractionType(
                                profile.getId(), InteractionType.DISMISSED)))
                .orElseGet(() -> new Counts(0, 0, 0));
    }

    private JobInteraction upsert(CandidateProfile profile, UUID jobId, InteractionType type,
                                  String note, ApplicationStatus status) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> NotFoundException.of("Job", jobId));

        JobInteraction interaction = interactionRepository
                .findByCandidateIdAndJobIdAndInteractionType(profile.getId(), jobId, type)
                .orElseGet(() -> {
                    JobInteraction created = new JobInteraction();
                    created.setCandidate(profile);
                    created.setJob(job);
                    created.setInteractionType(type);
                    return created;
                });

        if (TextUtils.hasText(note)) {
            interaction.setNote(TextUtils.truncate(note, 1000));
        }
        if (type == InteractionType.APPLIED) {
            interaction.setApplicationStatus(status == null ? ApplicationStatus.APPLIED : status);
            if (interaction.getAppliedAt() == null) {
                interaction.setAppliedAt(Instant.now());
            }
        }
        return interactionRepository.save(interaction);
    }

    private void clear(UUID candidateId, UUID jobId, InteractionType type) {
        interactionRepository.deleteByCandidateIdAndJobIdAndInteractionType(candidateId, jobId, type);
    }

    private ApplicationStatus parseStatus(String status) {
        if (!TextUtils.hasText(status)) {
            return ApplicationStatus.APPLIED;
        }
        try {
            return ApplicationStatus.valueOf(status.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException ex) {
            return ApplicationStatus.APPLIED;
        }
    }

    public record Counts(long saved, long applied, long dismissed) {
    }
}
