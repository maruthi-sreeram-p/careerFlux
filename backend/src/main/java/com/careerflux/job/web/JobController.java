package com.careerflux.job.web;

import java.util.List;
import java.util.UUID;

import com.careerflux.engagement.domain.InteractionType;
import com.careerflux.engagement.service.EngagementService;
import com.careerflux.job.dto.JobDtos.InteractionRequest;
import com.careerflux.job.dto.JobDtos.JobDetail;
import com.careerflux.job.dto.JobDtos.JobPage;
import com.careerflux.job.dto.JobDtos.JobSummary;
import com.careerflux.job.service.JobQueryService;
import com.careerflux.job.service.JobQueryService.JobFilter;
import com.careerflux.matching.service.MatchingService;
import com.careerflux.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Jobs")
public class JobController {

    private final JobQueryService jobQueryService;
    private final EngagementService engagementService;
    private final MatchingService matchingService;
    private final CurrentUser currentUser;

    public JobController(JobQueryService jobQueryService,
                         EngagementService engagementService,
                         MatchingService matchingService,
                         CurrentUser currentUser) {
        this.jobQueryService = jobQueryService;
        this.engagementService = engagementService;
        this.matchingService = matchingService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Search and filter jobs")
    public JobPage search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) List<String> workMode,
            @RequestParam(required = false) List<String> employmentType,
            @RequestParam(required = false) List<String> seniority,
            @RequestParam(required = false) Double maxExperience,
            @RequestParam(required = false) Integer postedWithinDays,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID sourceId,
            @RequestParam(required = false) String skill,
            @RequestParam(required = false) Integer minMatch,
            @RequestParam(defaultValue = "false") boolean includeClosed,
            @RequestParam(defaultValue = "true") boolean hideDismissed,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        JobFilter filter = new JobFilter(q, location, workMode, employmentType, seniority,
                maxExperience, postedWithinDays, companyId, sourceId, skill, minMatch,
                includeClosed, hideDismissed);
        return jobQueryService.search(currentUser.requireId(), filter, page, size, sort);
    }

    @GetMapping("/recommended")
    @Operation(summary = "Matches above the visibility floor, best first")
    public List<JobSummary> recommended(@RequestParam(defaultValue = "12") int limit) {
        return jobQueryService.recommendations(currentUser.requireId(), limit);
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "Full job detail with match analysis, provenance and change history")
    public JobDetail detail(@PathVariable UUID jobId) {
        UUID userId = currentUser.requireId();
        engagementService.recordView(userId, jobId);
        return jobQueryService.detail(userId, jobId);
    }

    @PostMapping("/{jobId}/save")
    @PreAuthorize("hasAuthority('SELF_JOBS_MANAGE')")
    public ResponseEntity<Void> save(@PathVariable UUID jobId,
                                     @RequestBody(required = false) InteractionRequest request) {
        engagementService.save(currentUser.requireId(), jobId, request == null ? null : request.note());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{jobId}/save")
    @PreAuthorize("hasAuthority('SELF_JOBS_MANAGE')")
    public ResponseEntity<Void> unsave(@PathVariable UUID jobId) {
        engagementService.unsave(currentUser.requireId(), jobId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobId}/dismiss")
    @PreAuthorize("hasAuthority('SELF_JOBS_MANAGE')")
    public ResponseEntity<Void> dismiss(@PathVariable UUID jobId,
                                        @RequestBody(required = false) InteractionRequest request) {
        engagementService.dismiss(currentUser.requireId(), jobId, request == null ? null : request.note());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{jobId}/dismiss")
    @PreAuthorize("hasAuthority('SELF_JOBS_MANAGE')")
    public ResponseEntity<Void> undismiss(@PathVariable UUID jobId) {
        engagementService.undismiss(currentUser.requireId(), jobId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobId}/applied")
    @Operation(summary = "Record that you applied. CareerFlux never applies on your behalf.")
    @PreAuthorize("hasAuthority('SELF_JOBS_MANAGE')")
    public ResponseEntity<Void> markApplied(@PathVariable UUID jobId,
                                            @RequestBody(required = false) InteractionRequest request) {
        engagementService.markApplied(currentUser.requireId(), jobId,
                request == null ? null : request.note(),
                request == null ? null : request.applicationStatus());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/saved")
    @PreAuthorize("hasAuthority('SELF_JOBS_MANAGE')")
    public List<JobSummary> saved() {
        return interactionSummaries(InteractionType.SAVED);
    }

    @GetMapping("/applied")
    @PreAuthorize("hasAuthority('SELF_JOBS_MANAGE')")
    public List<JobSummary> applied() {
        return interactionSummaries(InteractionType.APPLIED);
    }

    @GetMapping("/dismissed")
    @PreAuthorize("hasAuthority('SELF_JOBS_MANAGE')")
    public List<JobSummary> dismissed() {
        return interactionSummaries(InteractionType.DISMISSED);
    }

    @PostMapping("/rematch")
    @Operation(summary = "Queue a recompute of every match for the signed-in candidate")
    @PreAuthorize("hasAuthority('SELF_JOBS_MANAGE')")
    public RematchResponse rematch() {
        // Queued rather than computed here. Scoring the corpus for one candidate
        // takes minutes; doing it on this thread timed the caller out while the
        // work continued behind them, so the response was a lie in both
        // directions — an error to the caller, a completed run in the database.
        return matchingService.requestRematch(currentUser.requireId());
    }

    /**
     * The state of a queued recompute, not its results.
     *
     * @param status   PENDING, RUNNING, COMPLETED or FAILED
     * @param queued   true when there is outstanding work, so the UI can show
     *                 that matches are still being worked out
     */
    public record RematchResponse(String status, boolean queued, String notice) {
    }

    private List<JobSummary> interactionSummaries(InteractionType type) {
        UUID userId = currentUser.requireId();
        List<UUID> jobIds = engagementService.list(userId, type).stream()
                .map(interaction -> interaction.getJob().getId())
                .toList();
        return jobQueryService.summariesFor(userId, jobIds);
    }
}
