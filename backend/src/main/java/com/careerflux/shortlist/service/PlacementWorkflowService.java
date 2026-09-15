package com.careerflux.shortlist.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.careerflux.audit.AuditService;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.common.error.BadRequestException;
import com.careerflux.common.error.ConflictException;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.discovery.service.DiscoveryScope;
import com.careerflux.requirement.domain.CompanyRequirement;
import com.careerflux.requirement.domain.RequirementStatus;
import com.careerflux.requirement.service.RequirementAccess;
import com.careerflux.security.access.AccessGuard;
import com.careerflux.security.access.AccessScope;
import com.careerflux.shortlist.domain.PlacementStage;
import com.careerflux.shortlist.domain.PlacementStage.ActorKind;
import com.careerflux.shortlist.domain.PlacementStageChange;
import com.careerflux.shortlist.domain.ShortlistEntry;
import com.careerflux.shortlist.repository.PlacementStageChangeRepository;
import com.careerflux.shortlist.repository.ShortlistRepository;
import com.careerflux.user.Permission;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moving a shortlisted candidate through a drive.
 *
 * <p>Everything here is somebody deciding. No score advances a candidate, no
 * threshold selects one, and nothing in this class consults a match at all —
 * the scores exist so a person can decide well, not so the system can decide
 * for them. What the code enforces is that the person is allowed to decide, and
 * that the decision they are making is one the workflow permits from where the
 * candidate currently is.
 *
 * <p><b>Two questions, both asked, in this order.</b> The permission answers
 * "may this role make placement decisions at all"; the scope answers "is this
 * particular student theirs to decide about". A coordinator holds the first for
 * their whole college and the second only for their department, which is why
 * both are checked and why the scope check is the one that produces not-found
 * rather than forbidden. The requirement itself must be visible to them first,
 * by the same rule its own page applies.
 *
 * <p><b>The stage and its history move together.</b> One transaction writes
 * both, so a rolled-back decision leaves no trace of having happened and a
 * failed history write takes the stage with it. That is why the history is not
 * {@code audit_events}: the audit service is deliberately {@code REQUIRES_NEW}
 * and commits on its own. Both are written — the audit trail answers "what has
 * anybody been doing lately", the history answers "what happened to this
 * candidate" — and only one of them is allowed to be authoritative.
 */
@Service
public class PlacementWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(PlacementWorkflowService.class);

    private static final String CANDIDATE = "Candidate";
    private static final String PLACEMENT_RECORD = "Placement record";

    private final ShortlistRepository shortlists;
    private final PlacementStageChangeRepository stageChanges;
    private final RequirementAccess requirementAccess;
    private final CandidateProfileRepository candidates;
    private final UserRepository users;
    private final DiscoveryScope discoveryScope;
    private final AccessGuard accessGuard;
    private final AuditService audit;

    public PlacementWorkflowService(ShortlistRepository shortlists,
                                    PlacementStageChangeRepository stageChanges,
                                    RequirementAccess requirementAccess,
                                    CandidateProfileRepository candidates,
                                    UserRepository users,
                                    DiscoveryScope discoveryScope,
                                    AccessGuard accessGuard,
                                    AuditService audit) {
        this.shortlists = shortlists;
        this.stageChanges = stageChanges;
        this.requirementAccess = requirementAccess;
        this.candidates = candidates;
        this.users = users;
        this.discoveryScope = discoveryScope;
        this.accessGuard = accessGuard;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ staff

    /**
     * Placement staff moving a candidate on.
     *
     * <p>Reuses {@code PLACEMENT_SHORTLIST_MANAGE}: the permission already means
     * "decide about candidates on a drive, within your own scope", which is
     * exactly this. Requirement authoring stays behind its own permission, so a
     * coordinator can run their department's candidates through a drive without
     * being able to write the drive.
     */
    @Transactional
    public ShortlistEntry moveByStaff(UUID requirementId, UUID candidateId,
                                      PlacementStage target, String note) {
        accessGuard.requirePermission(Permission.PLACEMENT_SHORTLIST_MANAGE);
        CompanyRequirement requirement = openRequirement(requirementId);
        CandidateProfile candidate = candidateInScope(requirement, candidateId);

        ShortlistEntry entry = shortlists
                .findByRequirementIdAndCandidateId(requirementId, candidate.getId())
                .orElseThrow(() -> NotFoundException.of(PLACEMENT_RECORD, candidateId));

        return apply(entry, target, ActorKind.STAFF, note,
                users.findById(accessGuard.currentUserId()).orElse(null));
    }

    // ---------------------------------------------------------------- student

    /**
     * A student answering an invitation.
     *
     * <p>No placement permission is involved, and deliberately so: this is not
     * the college acting, it is a person answering a question about themselves.
     * The record is located from the authenticated student's own profile rather
     * than from anything the request said, so there is no id to tamper with —
     * a student cannot name somebody else's record because they never name a
     * record at all.
     */
    @Transactional
    public ShortlistEntry respondAsStudent(UUID requirementId, PlacementStage target, String note) {
        UUID userId = accessGuard.currentUserId();
        CandidateProfile profile = candidates.findByUserId(userId)
                .orElseThrow(() -> NotFoundException.of(PLACEMENT_RECORD, requirementId));

        ShortlistEntry entry = shortlists
                .findByRequirementIdAndCandidateId(requirementId, profile.getId())
                // Not-found rather than forbidden: a student must not be able to
                // discover which drives exist by watching which ids answer
                // differently.
                .orElseThrow(() -> NotFoundException.of(PLACEMENT_RECORD, requirementId));

        // The same freeze that stops staff moving anybody. A drive that has been
        // closed has been reported on, and an answer arriving afterwards would
        // change a placement record that somebody has already counted. The
        // student is told the drive closed rather than that they may not answer,
        // because the second is not true — they were simply asked too late.
        if (entry.getRequirement().getStatus() != RequirementStatus.OPEN) {
            throw new BadRequestException("This drive has closed, so answers are no longer being "
                    + "collected. Speak to your placement office.");
        }

        if (target != PlacementStage.INTERESTED && target != PlacementStage.DECLINED) {
            // Caught before the matrix so the message says something useful. A
            // student answering an invitation has exactly two answers.
            throw new BadRequestException(
                    "You can say you are interested, or decline. Anything else is the "
                            + "placement team's decision.");
        }

        return apply(entry, target, ActorKind.STUDENT, note,
                users.findById(userId).orElse(null));
    }

    // ------------------------------------------------------------------ reads

    /** One candidate's history on one drive, oldest first. */
    @Transactional(readOnly = true)
    public List<PlacementStageChange> historyForStaff(UUID requirementId, UUID candidateId) {
        accessGuard.requirePermission(Permission.PLACEMENT_DRIVE_VIEW);
        CompanyRequirement requirement = readableRequirement(requirementId);
        CandidateProfile candidate = candidateInScope(requirement, candidateId);
        ShortlistEntry entry = shortlists
                .findByRequirementIdAndCandidateId(requirementId, candidate.getId())
                .orElseThrow(() -> NotFoundException.of(PLACEMENT_RECORD, candidateId));
        return stageChanges.findByShortlistIdOrderByOccurredAtAsc(entry.getId());
    }

    /** Every drive the signed-in student has been put forward for. */
    @Transactional(readOnly = true)
    public List<ShortlistEntry> myPlacements() {
        CandidateProfile profile = candidates.findByUserId(accessGuard.currentUserId())
                .orElse(null);
        return profile == null ? List.of() : shortlists.findForCandidate(profile.getId());
    }

    /** The signed-in student's own history on one drive. */
    @Transactional(readOnly = true)
    public List<PlacementStageChange> myHistory(UUID requirementId) {
        CandidateProfile profile = candidates.findByUserId(accessGuard.currentUserId())
                .orElseThrow(() -> NotFoundException.of(PLACEMENT_RECORD, requirementId));
        ShortlistEntry entry = shortlists
                .findByRequirementIdAndCandidateId(requirementId, profile.getId())
                .orElseThrow(() -> NotFoundException.of(PLACEMENT_RECORD, requirementId));
        return stageChanges.findByShortlistIdOrderByOccurredAtAsc(entry.getId());
    }

    // --------------------------------------------------------------- the move

    /**
     * The one place a stage actually changes.
     *
     * <p>Both callers land here so the transition rules, the history row and the
     * conflict handling exist once. The order matters: validate against the
     * stage as stored, then write both the stage and the record of the move
     * inside the same transaction.
     */
    private ShortlistEntry apply(ShortlistEntry entry, PlacementStage target,
                                 ActorKind actorKind, String note, User actor) {
        PlacementStage current = entry.getStage();

        if (current == target) {
            throw new ConflictException("This candidate is already "
                    + target.label().toLowerCase(Locale.ROOT) + ".");
        }
        if (current.isTerminal()) {
            throw new BadRequestException("This candidate is already "
                    + current.label().toLowerCase(Locale.ROOT)
                    + ", which is where the workflow ends. Nothing moves out of it.");
        }
        if (!current.canMoveTo(target, actorKind)) {
            throw new BadRequestException(explain(current, target, actorKind));
        }

        entry.setStage(target);
        entry.setStageChangedAt(Instant.now());
        stageChanges.save(PlacementStageChange.of(entry, current, target, actor, actorKind, note));

        try {
            // Flushed here rather than at commit so an optimistic-lock collision
            // surfaces as a conflict the caller understands, instead of an
            // exception escaping from somewhere with no context.
            shortlists.saveAndFlush(entry);
        } catch (ObjectOptimisticLockingFailureException raced) {
            throw new ConflictException(
                    "Somebody else moved this candidate at the same moment. "
                            + "Reload to see where they are now.");
        }

        audit.record("PLACEMENT_STAGE_CHANGED", "ShortlistEntry", entry.getId(),
                current.name() + " -> " + target.name() + " by " + actorKind);
        log.info("Placement stage {} -> {} for candidate {} on requirement {} by {}",
                current, target, entry.getCandidate().getId(),
                entry.getRequirement().getId(), actorKind);
        return entry;
    }

    /** Why this particular move was refused, in terms of who was asking. */
    private static String explain(PlacementStage current, PlacementStage target, ActorKind actor) {
        if (actor == ActorKind.STAFF && target == PlacementStage.INTERESTED) {
            return "Only the student can say they are interested. Invite them and wait for an answer.";
        }
        if (actor == ActorKind.STAFF && target == PlacementStage.DECLINED) {
            return "Declining is the student's answer. Use \"not proceeding\" to record the "
                    + "college's decision.";
        }
        if (actor == ActorKind.STUDENT) {
            return "You can only respond to an invitation, and this record is "
                    + current.label().toLowerCase(Locale.ROOT) + ".";
        }
        return "A candidate who is " + current.label().toLowerCase(Locale.ROOT)
                + " cannot move straight to " + target.label().toLowerCase(Locale.ROOT) + ".";
    }

    // --------------------------------------------------------------- helpers
    //
    // Deliberately the same checks, in the same order, as ShortlistService uses
    // for adding and removing. A drive that is closed does not accept decisions,
    // a requirement the caller may not see is not found, and neither is a
    // candidate outside the caller's scope.

    private CompanyRequirement readableRequirement(UUID requirementId) {
        return requirementAccess.visible(requirementId);
    }

    private CompanyRequirement openRequirement(UUID requirementId) {
        CompanyRequirement requirement = readableRequirement(requirementId);
        if (requirement.getStatus() != RequirementStatus.OPEN) {
            throw new BadRequestException("Candidates can only be moved while the requirement is "
                    + "open. This one is "
                    + requirement.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }
        return requirement;
    }

    private CandidateProfile candidateInScope(CompanyRequirement requirement, UUID candidateId) {
        UUID institutionId = accessGuard.requireInstitutionId();
        AccessScope scope = accessGuard.scope();

        CandidateProfile candidate = candidates.findById(candidateId)
                .orElseThrow(() -> NotFoundException.of(CANDIDATE, candidateId));

        if (candidate.getUser() == null
                || candidate.getUser().getInstitutionId() == null
                || !candidate.getUser().getInstitutionId().equals(institutionId)) {
            log.warn("Cross-tenant placement move blocked: candidate {} is not in institution {}",
                    candidateId, institutionId);
            throw NotFoundException.of(CANDIDATE, candidateId);
        }
        if (!discoveryScope.covers(requirement, institutionId, scope, candidate.getUser().getId())) {
            log.warn("Out-of-scope placement move blocked: candidate {} is outside the scope for "
                    + "requirement {}", candidateId, requirement.getId());
            throw NotFoundException.of(CANDIDATE, candidateId);
        }
        return candidate;
    }
}
