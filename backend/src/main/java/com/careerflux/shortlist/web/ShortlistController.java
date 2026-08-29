package com.careerflux.shortlist.web;

import java.util.UUID;

import com.careerflux.discovery.dto.DiscoveryDtos.CandidatePage;
import com.careerflux.discovery.service.CandidateDiscoveryService;
import com.careerflux.shortlist.service.ShortlistService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The students the placement team has chosen to put forward.
 *
 * <p>Writing is gated on {@code PLACEMENT_DRIVE_MANAGE} and reading on
 * {@code PLACEMENT_DRIVE_VIEW}, which is the split the role model already
 * draws between running a drive and following one. Neither permission was
 * created or moved for this feature.
 *
 * <p>The annotation is the coarse gate only. The service decides the rest:
 * which college the requirement belongs to, whether it is open, and whether
 * the candidate is inside the caller's own discovery scope. A candidate id in
 * a request body proves nothing — it may never have appeared on a page this
 * caller was shown.
 */
@RestController
@RequestMapping("/api/requirements/{requirementId}/shortlist")
@Tag(name = "Candidate shortlist")
public class ShortlistController {

    private final ShortlistService shortlists;
    private final CandidateDiscoveryService discovery;

    public ShortlistController(ShortlistService shortlists, CandidateDiscoveryService discovery) {
        this.shortlists = shortlists;
        this.discovery = discovery;
    }

    /** The candidate to put forward. A body rather than a path, since it creates a relationship. */
    public record ShortlistRequest(@NotNull UUID candidateId) {
    }

    /**
     * The shortlist, with each candidate's current figures.
     *
     * <p>Readable whatever the requirement's state — a closed drive's shortlist
     * is a record of real work and stays viewable. Reuses the discovery
     * candidate shape rather than defining a second one.
     */
    @GetMapping
    @Operation(summary = "Shortlisted candidates, scored as they stand today")
    @PreAuthorize("hasAuthority('PLACEMENT_DRIVE_VIEW')")
    public CandidatePage list(@PathVariable UUID requirementId) {
        return discovery.shortlist(requirementId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Put a candidate forward. Only ever called by a person.")
    @PreAuthorize("hasAuthority('PLACEMENT_DRIVE_MANAGE')")
    public ShortlistedView add(@PathVariable UUID requirementId,
                               @RequestBody ShortlistRequest request) {
        var entry = shortlists.add(requirementId, request.candidateId());
        return new ShortlistedView(entry.getCandidate().getId(), true,
                shortlists.count(requirementId));
    }

    /**
     * Takes a candidate off the shortlist.
     *
     * <p>Removes the relationship and nothing else — the student, their
     * profile, their resume and every match they hold are untouched.
     */
    @DeleteMapping("/{candidateId}")
    @Operation(summary = "Withdraw a candidate from the shortlist")
    @PreAuthorize("hasAuthority('PLACEMENT_DRIVE_MANAGE')")
    public ShortlistedView remove(@PathVariable UUID requirementId,
                                  @PathVariable UUID candidateId) {
        shortlists.remove(requirementId, candidateId);
        return new ShortlistedView(candidateId, false, shortlists.count(requirementId));
    }

    /** @param shortlistedCount read from the table, never accumulated client-side */
    public record ShortlistedView(UUID candidateId, boolean shortlisted, long shortlistedCount) {
    }
}
