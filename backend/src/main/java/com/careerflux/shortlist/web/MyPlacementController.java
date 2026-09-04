package com.careerflux.shortlist.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.careerflux.common.error.BadRequestException;
import com.careerflux.requirement.domain.CompanyRequirement;
import com.careerflux.shortlist.domain.PlacementStage;
import com.careerflux.shortlist.domain.ShortlistEntry;
import com.careerflux.shortlist.service.PlacementWorkflowService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A student's own placement records.
 *
 * <p>Until now a student had no way to know they had been put forward for
 * anything: shortlisting was entirely staff-facing, and the person it was about
 * could not see it. This is the other half — what the college has asked them,
 * and their answer.
 *
 * <p><b>There is no id to tamper with.</b> Every method resolves the record
 * from the authenticated student's own profile; the path names a requirement,
 * never a candidate. A student asking about a drive they are not on is told
 * not-found, which is also what they are told about a drive that does not
 * exist — so the endpoint cannot be used to discover what the college is
 * running.
 *
 * <p>No permission gates any of this. Reading and answering are self-access,
 * not placement authority, and giving students a placement permission to let
 * them answer a question about themselves would be exactly the kind of
 * broadening the role model exists to prevent.
 */
@RestController
@RequestMapping("/api/candidate/placements")
@Tag(name = "My placements")
public class MyPlacementController {

    private final PlacementWorkflowService placement;

    public MyPlacementController(PlacementWorkflowService placement) {
        this.placement = placement;
    }

    /**
     * Every drive this student has been put forward for.
     *
     * <p>Company, role, where they have got to, and what — if anything — is
     * waiting on them. Deliberately not the candidate view staff see: match
     * scores, eligibility verdicts and skill gaps are the college's working
     * notes about a person, and handing them back to that person as a verdict
     * on themselves is a different product decision that nobody has made.
     */
    @GetMapping
    @Operation(summary = "Drives you have been put forward for")
    public List<MyPlacementView> mine() {
        return placement.myPlacements().stream().map(MyPlacementView::of).toList();
    }

    /** How this student's own record on one drive got to where it is. */
    @GetMapping("/{requirementId}/history")
    @Operation(summary = "What has happened on one of your placements")
    public List<ShortlistController.StageChangeView> history(@PathVariable UUID requirementId) {
        return placement.myHistory(requirementId).stream()
                .map(ShortlistController.StageChangeView::of)
                .toList();
    }

    /**
     * Answering an invitation.
     *
     * <p>Two answers, and only from an invitation. A student cannot shortlist
     * themselves, invite themselves or select themselves; the server checks
     * that against the stage as stored rather than against anything the client
     * believes.
     */
    @PatchMapping("/{requirementId}/response")
    @Operation(summary = "Say you are interested, or decline")
    public MyPlacementView respond(@PathVariable UUID requirementId,
                                   @Valid @RequestBody ResponseRequest request) {
        PlacementStage target = parseResponse(request.response());
        return MyPlacementView.of(placement.respondAsStudent(requirementId, target, request.note()));
    }

    /**
     * Accepts the two words a student can say, not the whole stage vocabulary.
     *
     * <p>The service refuses anything else anyway. Naming only the two here
     * keeps the API honest about what is on offer, so a client cannot read the
     * shape and conclude that "SELECTED" is something a student might send.
     */
    private static PlacementStage parseResponse(String raw) {
        return switch (raw.strip().toUpperCase(Locale.ROOT)) {
            case "INTERESTED" -> PlacementStage.INTERESTED;
            case "DECLINED" -> PlacementStage.DECLINED;
            default -> throw new BadRequestException(
                    "Answer with \"interested\" or \"declined\".");
        };
    }

    public record ResponseRequest(
            @NotBlank String response,
            @Size(max = 1000) String note) {
    }

    /**
     * One placement, as the student it is about sees it.
     *
     * <p>{@code awaitingYou} exists so a screen does not have to know the
     * workflow to say what matters: whether anything is waiting on them.
     */
    public record MyPlacementView(
            UUID requirementId,
            String companyName,
            String roleTitle,
            String location,
            String workMode,
            String requirementStatus,
            String stage,
            String stageLabel,
            boolean awaitingYou,
            boolean closed,
            Instant shortlistedAt,
            Instant stageChangedAt) {

        static MyPlacementView of(ShortlistEntry entry) {
            CompanyRequirement requirement = entry.getRequirement();
            PlacementStage stage = entry.getStage();
            return new MyPlacementView(
                    requirement.getId(),
                    requirement.getCompanyName(),
                    requirement.getRoleTitle(),
                    requirement.getLocationRaw(),
                    requirement.getWorkMode() == null ? null : requirement.getWorkMode().name(),
                    requirement.getStatus().name(),
                    stage.name(),
                    stage.label(),
                    stage.awaitsStudentResponse(),
                    stage.isTerminal(),
                    entry.getCreatedAt(),
                    entry.getStageChangedAt());
        }
    }
}
