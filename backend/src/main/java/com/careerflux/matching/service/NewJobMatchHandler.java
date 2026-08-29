package com.careerflux.matching.service;

import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.ingestion.event.PipelineEventHandler;
import com.careerflux.ingestion.event.PipelineTopics;
import com.careerflux.job.domain.Job;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.matching.domain.JobMatch;
import com.careerflux.matching.domain.MatchTier;
import com.careerflux.notification.service.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code job.classified} and scores the new job against every onboarded
 * candidate, notifying the ones it actually fits.
 *
 * <p>This is where the ingestion pipeline meets candidate intelligence: a job
 * finishing the pipeline is the trigger for matching, rather than matching being
 * a batch job that runs hours later and leaves the feed stale.
 *
 * <p>Idempotent by construction — scoring the same job twice produces the same
 * upserted match, and {@link NotificationService} refuses to notify twice about
 * the same job.
 */
@Component
public class NewJobMatchHandler implements PipelineEventHandler {

    private static final Logger log = LoggerFactory.getLogger(NewJobMatchHandler.class);

    private final ObjectMapper objectMapper;
    private final JobRepository jobRepository;
    private final CandidateProfileRepository profileRepository;
    private final MatchingService matchingService;
    private final NotificationService notificationService;

    public NewJobMatchHandler(ObjectMapper objectMapper,
                              JobRepository jobRepository,
                              CandidateProfileRepository profileRepository,
                              MatchingService matchingService,
                              NotificationService notificationService) {
        this.objectMapper = objectMapper;
        this.jobRepository = jobRepository;
        this.profileRepository = profileRepository;
        this.matchingService = matchingService;
        this.notificationService = notificationService;
    }

    @Override
    public String topic() {
        return PipelineTopics.JOB_CLASSIFIED;
    }

    @Override
    @Transactional
    public void handle(String eventKey, String payload) {
        UUID jobId = readJobId(eventKey, payload);
        if (jobId == null) {
            return;
        }
        Job job = jobRepository.findFullById(jobId).orElse(null);
        if (job == null) {
            // The job was removed between publish and delivery. Nothing to do.
            return;
        }

        int notified = 0;
        for (CandidateProfile profile : profileRepository.findAllOnboarded()) {
            CandidateSnapshot snapshot = matchingService.snapshot(profile);
            if (!snapshot.isScorable()) {
                continue;
            }
            JobMatch match = matchingService.scoreAndStore(profile, snapshot, job);
            if (match.getTier() == MatchTier.HIDDEN) {
                continue;
            }
            if (notificationService.notifyMatch(profile.getUser(), match, snapshot.immediateAlerts())
                    .isPresent()) {
                notified++;
            }
        }
        if (notified > 0) {
            log.info("Job {} produced {} candidate notifications", jobId, notified);
        }
    }

    private UUID readJobId(String eventKey, String payload) {
        try {
            return UUID.fromString(eventKey);
        } catch (IllegalArgumentException ignored) {
            // Fall back to the payload when the key is not the job id.
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            String value = node.path("jobId").asText(null);
            return value == null ? null : UUID.fromString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
            log.warn("Could not read a job id from event {}: {}", eventKey, ex.getMessage());
            return null;
        }
    }
}
