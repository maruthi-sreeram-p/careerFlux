package com.careerflux.shortlist.web;

import java.util.UUID;
import org.springframework.web.bind.annotation.PatchMapping;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import com.careerflux.shortlist.service.PlacementWorkflowService;
import com.careerflux.shortlist.domain.ShortlistEntry;
import com.careerflux.shortlist.domain.PlacementStageChange;
import com.careerflux.shortlist.domain.PlacementStage;
import com.careerflux.common.error.BadRequestException;
import java.util.List;
import java.time.Instant;

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
 * <p>Writing is gated on {@code PLACEMENT_SHORTLIST_MANAGE} and reading on
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
    private final PlacementWorkflowService placement;
    private final CandidateDiscoveryService discovery;

    public ShortlistController(ShortlistService shortlists, CandidateDiscoveryService discovery,
                               PlacementWorkflowService placement) {
        this.placement = placement;
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
    @PreAuthorize("hasAuthority('PLACEMENT_SHORTLIST_MANAGE')")
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
    @PreAuthorize("hasAuthority('PLACEMENT_SHORTLIST_MANAGE')")
    public ShortlistedView remove(@PathVariable UUID requirementId,
                                  @PathVariable UUID candidateId) {
        shortlists.remove(requirementId, candidateId);
        return new ShortlistedView(candidateId, false, shortlists.count(requirementId));
    }

    /**
     * Moves a candidate on in the drive.
     *
     * <p>PATCH rather than a verb per transition. The stage is a property of the
     * placement record and the set of legal moves is decided by the server from
     * where the candidate currently is, so {@code /invite}, {@code /select} and
     * {@code /reject} would be three doors into one rule. The rule lives in
     * {@code PlacementStage}; this endpoint carries the request to it.
     *
     * <p>Gated on the same permission as shortlisting, for the same reason: this
     * is deciding about candidates on a drive. Authoring the drive stays behind
     * {@code PLACEMENT_DRIVE_MANAGE}, so a coordinator can do this and still not
     * write the requirement.
     */
    @PatchMapping("/{candidateId}/stage")
    @Operation(summary = "Move a shortlisted candidate to another placement stage")
    @PreAuthorize("hasAuthority('PLACEMENT_SHORTLIST_MANAGE')")
    public PlacementView moveStage(@PathVariable UUID requirementId,
                                   @PathVariable UUID candidateId,
                                   @Valid @RequestBody StageChangeRequest request) {
        ShortlistEntry entry = placement.moveByStaff(requirementId, candidateId,
                parseStage(request.stage()), request.note());
        return PlacementView.of(entry);
    }

    /**
     * How this candidate got to where they are.
     *
     * <p>Read from the transactional history rather than reconstructed, so it
     * shows what happened rather than what the current stage implies.
     */
    @GetMapping("/{candidateId}/history")
    @Operation(summary = "Every placement decision made about this candidate on this drive")
    @PreAuthorize("hasAuthority('PLACEMENT_DRIVE_VIEW')")
    public List<StageChangeView> history(@PathVariable UUID requirementId,
                                         @PathVariable UUID candidateId) {
        return placement.historyForStaff(requirementId, candidateId).stream()
                .map(StageChangeView::of)
                .toList();
    }

    /**
     * Turns the requested stage into the enum, or refuses it.
     *
     * <p>An unknown name is a bad request rather than a 500: the client asked
     * for something that is not a stage, which is worth saying plainly.
     */
    private static PlacementStage parseStage(String raw) {
        try {
            return PlacementStage.valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("\"" + raw + "\" is not a placement stage.");
        }
    }

    public record StageChangeRequest(
            @NotBlank String stage,
            @Size(max = 1000) String note) {
    }

    /** What a stage change returns: where the candidate is now, and since when. */
    public record PlacementView(UUID candidateId, String stage, String stageLabel,
                                Instant stageChangedAt, boolean terminal,
                                boolean awaitingStudent) {
        static PlacementView of(ShortlistEntry entry) {
            PlacementStage stage = entry.getStage();
            return new PlacementView(entry.getCandidate().getId(), stage.name(), stage.label(),
                    entry.getStageChangedAt(), stage.isTerminal(), stage.awaitsStudentResponse());
        }
    }

    /**
     * One line of the history.
     *
     * <p>Carries who acted and which kind of actor they were. The stage alone
     * cannot say whether the college stopped considering somebody or the student
     * withdrew, and that is usually the question being asked.
     */
    /**
     * One line of the trail.
     *
     * <p>{@code actorLabel} is the name as it was when the decision was made and
     * is null once the account is gone — a screen must render the line without
     * it rather than printing "null invited them". The actor's user id is
     * deliberately not exposed: the trail is for reading, not for looking people
     * up, and a candidate's history should not hand out staff identifiers.
     */
    public record StageChangeView(UUID id, String fromStage, String toStage, String toStageLabel,
                                  String actorLabel, String actorKind, String note,
                                  Instant occurredAt) {
        static StageChangeView of(PlacementStageChange change) {
            return new StageChangeView(
                    change.getId(),
                    change.getFromStage() == null ? null : change.getFromStage().name(),
                    change.getToStage().name(),
                    change.getToStage().label(),
                    change.getActorLabel(),
                    change.getActorKind().name(),
                    change.getNote(),
                    change.getOccurredAt());
        }
    }

    /** @param shortlistedCount read from the table, never accumulated client-side */
    public record ShortlistedView(UUID candidateId, boolean shortlisted, long shortlistedCount) {
    }
}
