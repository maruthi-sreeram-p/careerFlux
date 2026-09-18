package com.careerflux.shortlist.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.common.error.BadRequestException;
import com.careerflux.common.error.ConflictException;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.discovery.service.DiscoveryScope;
import com.careerflux.requirement.domain.CompanyRequirement;
import com.careerflux.requirement.domain.RequirementStatus;
import com.careerflux.requirement.service.RequirementAccess;
import com.careerflux.audit.AuditService;
import com.careerflux.security.access.AccessGuard;
import com.careerflux.security.access.AccessScope;
import com.careerflux.shortlist.domain.PlacementStage;
import com.careerflux.shortlist.domain.ShortlistEntry;
import com.careerflux.shortlist.repository.PlacementStageChangeRepository;
import com.careerflux.shortlist.repository.ShortlistRepository;
import com.careerflux.user.Permission;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recording, and undoing, the placement team's decision about who to put
 * forward.
 *
 * <p>Nothing in CareerFlux calls into this except a person. There is no
 * threshold that adds a candidate, no rule that removes one when a score moves,
 * and no path from a model to a row in this table. Discovery describes; a human
 * decides; this writes it down.
 *
 * <p>That has a consequence worth stating plainly: <b>a student who does not
 * meet a company's stated condition can still be shortlisted.</b> The officer
 * can see the condition and the reason it failed, and may decide the student is
 * worth putting forward anyway or worth asking the company about. Refusing that
 * would turn a piece of information into an automatic rejection, which is the
 * exact failure the product exists to prevent.
 *
 * <p>What is refused is acting outside the caller's authority. Every write
 * checks, in order: the permission, the requirement's ownership and visibility
 * to this caller, its lifecycle state, the candidate's presence in the caller's
 * own discovery scope, and finally whether the row already exists.
 *
 * <p>The other thing refused is losing a drive's record. Undoing a shortlisting
 * is only undoing while nothing has happened; once a candidate has been moved,
 * the college's account of the drive exists and this service will not delete
 * it. See {@link #remove(UUID, UUID)}.
 *
 * <p>The permission is {@code PLACEMENT_SHORTLIST_MANAGE} rather than
 * {@code PLACEMENT_DRIVE_MANAGE}, and the difference is what lets a coordinator
 * use this at all. Authoring a requirement is unscoped; shortlisting is scoped
 * by the check below, so the narrow permission can be granted narrowly without
 * also handing out the ability to write and publish requirements for the whole
 * college. The scope check is unchanged and is what actually confines a
 * coordinator to their own department.
 */
@Service
public class ShortlistService {

    private static final Logger log = LoggerFactory.getLogger(ShortlistService.class);

    private static final String CANDIDATE = "Candidate";

    private final ShortlistRepository shortlists;
    private final PlacementStageChangeRepository stageChanges;
    private final RequirementAccess requirementAccess;
    private final CandidateProfileRepository candidates;
    private final UserRepository users;
    private final DiscoveryScope discoveryScope;
    private final AccessGuard accessGuard;
    private final AuditService audit;

    public ShortlistService(ShortlistRepository shortlists,
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

    /**
     * Puts a candidate on the shortlist.
     *
     * @throws ConflictException when they are already on it — reported rather
     *                           than silently ignored, because the caller
     *                           believed they were adding somebody
     */
    @Transactional
    public ShortlistEntry add(UUID requirementId, UUID candidateId) {
        accessGuard.requirePermission(Permission.PLACEMENT_SHORTLIST_MANAGE);
        CompanyRequirement requirement = openRequirement(requirementId);
        CandidateProfile candidate = candidateInScope(requirement, candidateId);
        // An erased account keeps its placement history, but it is nobody who can
        // be put forward for a drive. Not found, as for anyone else out of reach.
        if (candidate.getUser() != null && candidate.getUser().getStatus() == UserStatus.ERASED) {
            throw NotFoundException.of("Candidate", candidateId);
        }

        if (shortlists.findByRequirementIdAndCandidateId(requirementId, candidateId).isPresent()) {
            throw new ConflictException("This candidate is already on the shortlist.");
        }

        ShortlistEntry entry = new ShortlistEntry();
        entry.setRequirement(requirement);
        entry.setCandidate(candidate);
        entry.setCreatedBy(users.findById(accessGuard.currentUserId()).orElse(null));

        try {
            ShortlistEntry saved = shortlists.saveAndFlush(entry);
            audit.record("SHORTLIST_ADDED", "ShortlistEntry", saved.getId(),
                    "requirement=" + requirementId + " candidate=" + candidateId);
            log.info("Candidate {} shortlisted for requirement {}", candidateId, requirementId);
            return saved;
        } catch (DataIntegrityViolationException duplicate) {
            // Two requests raced past the check above. The unique constraint is
            // what actually guarantees one row; this turns the collision into
            // the same answer the loser would have got a moment earlier.
            throw new ConflictException("This candidate is already on the shortlist.");
        }
    }

    /**
     * Takes a candidate off the shortlist, while nothing has happened to them
     * yet.
     *
     * <p>Removes the relationship and nothing else. The student, their profile,
     * their resume, their skills and every match they have keep existing.
     *
     * <p><b>Only an untouched entry can be removed.</b> Shortlisting writes no
     * stage change, so a candidate nobody has moved has no history to lose and
     * taking them off the list undoes exactly the one decision that was made.
     * The moment somebody is invited, answers, or is selected, the drive has a
     * record, and {@code placement_stage_changes} cascades from this row — so
     * deleting it would destroy that record with no trace. Past that point the
     * outcome belongs to the workflow: {@code NOT_PROCEEDING} says the college
     * decided against a candidate and keeps why and when.
     *
     * @throws BadRequestException when the candidate has already moved, or when
     *                             a history row exists for any other reason
     */
    @Transactional
    public void remove(UUID requirementId, UUID candidateId) {
        accessGuard.requirePermission(Permission.PLACEMENT_SHORTLIST_MANAGE);
        CompanyRequirement requirement = openRequirement(requirementId);
        candidateInScope(requirement, candidateId);

        ShortlistEntry entry = shortlists
                .findByRequirementIdAndCandidateId(requirementId, candidateId)
                .orElseThrow(() -> NotFoundException.of("Shortlist entry", candidateId));

        requireNothingRecorded(entry);

        try {
            shortlists.delete(entry);
            // Flushed here so a stage change that landed a moment ago collides
            // now, inside this transaction, rather than at commit. The version
            // check is what stops the delete racing past a history row that was
            // written after the check above.
            shortlists.flush();
        } catch (ObjectOptimisticLockingFailureException raced) {
            throw new ConflictException(
                    "Somebody else moved this candidate at the same moment. "
                            + "Reload to see where they are now.");
        }

        audit.record("SHORTLIST_REMOVED", "ShortlistEntry", entry.getId(),
                "requirement=" + requirementId + " candidate=" + candidateId);
        log.info("Candidate {} removed from the shortlist for requirement {}",
                candidateId, requirementId);
    }

    /**
     * Refuses to delete a row the drive has a record against.
     *
     * <p>Two conditions rather than one. The stage is the readable reason, and
     * the history count is the one that actually guarantees the invariant: no
     * transition returns a candidate to {@code SHORTLISTED} today, but a rule
     * that holds only because of a separate enum is one change away from not
     * holding, and what must never happen here is a silent cascade.
     */
    private void requireNothingRecorded(ShortlistEntry entry) {
        if (entry.getStage() != PlacementStage.SHORTLISTED) {
            throw new BadRequestException("This candidate is already "
                    + entry.getStage().label().toLowerCase(Locale.ROOT)
                    + ", so the drive has a record of them that removing would destroy. "
                    + "Move them to \"" + PlacementStage.NOT_PROCEEDING.label().toLowerCase(Locale.ROOT)
                    + "\" instead, which keeps what happened.");
        }
        if (stageChanges.countByShortlistId(entry.getId()) > 0) {
            throw new BadRequestException("This candidate already has a recorded history on this "
                    + "drive, so they can no longer be taken off the list. Move them to \""
                    + PlacementStage.NOT_PROCEEDING.label().toLowerCase(Locale.ROOT)
                    + "\" instead, which keeps what happened.");
        }
    }

    /**
     * The shortlist itself.
     *
     * <p>Readable whatever the requirement's state. A closed drive's shortlist
     * is a record of what the college did, and destroying or hiding it when the
     * requirement closes would lose real work.
     */
    @Transactional(readOnly = true)
    public List<ShortlistEntry> list(UUID requirementId) {
        accessGuard.requirePermission(Permission.PLACEMENT_DRIVE_VIEW);
        readableRequirement(requirementId);
        return shortlists.findForRequirement(requirementId);
    }

    /** The candidate ids on a shortlist, for marking up a discovery page. */
    @Transactional(readOnly = true)
    public List<UUID> shortlistedCandidateIds(UUID requirementId) {
        return shortlists.findCandidateIds(requirementId);
    }

    public long count(UUID requirementId) {
        return shortlists.countByRequirementId(requirementId);
    }

    // --------------------------------------------------------------- guards

    /**
     * A requirement this caller may see at all.
     *
     * <p>Loaded by id <em>and</em> institution, so another college's
     * requirement is never in memory, and refused to a coordinator outside the
     * departments it targets — the same rule its own page applies. Not-found
     * rather than forbidden: a 403 would confirm the requirement exists.
     */
    private CompanyRequirement readableRequirement(UUID requirementId) {
        return requirementAccess.visible(requirementId);
    }

    /** The same, and open for business. Matches the Phase 3 lifecycle exactly. */
    private CompanyRequirement openRequirement(UUID requirementId) {
        CompanyRequirement requirement = readableRequirement(requirementId);
        if (requirement.getStatus() != RequirementStatus.OPEN) {
            throw new BadRequestException("The shortlist can only be changed while the requirement "
                    + "is open. This one is "
                    + requirement.getStatus().name().toLowerCase(Locale.ROOT)
                    + ", and its existing shortlist is still viewable.");
        }
        return requirement;
    }

    /**
     * A candidate the caller may act on for this requirement.
     *
     * <p>Membership is decided by the same scope discovery uses, not by the
     * request. A candidate id arriving here did not necessarily come from a
     * page this caller was shown — it may have been typed — so belonging to the
     * right college is not enough: they must also be inside the departments and
     * batch the requirement targets, intersected with the caller's own grant.
     */
    private CandidateProfile candidateInScope(CompanyRequirement requirement, UUID candidateId) {
        UUID institutionId = accessGuard.requireInstitutionId();
        AccessScope scope = accessGuard.scope();

        CandidateProfile candidate = candidates.findById(candidateId)
                .orElseThrow(() -> NotFoundException.of(CANDIDATE, candidateId));

        // Cross-tenant first, and answered as not-found so nothing is confirmed
        // about a candidate in another college.
        if (candidate.getUser() == null
                || candidate.getUser().getInstitutionId() == null
                || !candidate.getUser().getInstitutionId().equals(institutionId)) {
            log.warn("Cross-tenant shortlist blocked: candidate {} is not in institution {}",
                    candidateId, institutionId);
            throw NotFoundException.of(CANDIDATE, candidateId);
        }

        if (!discoveryScope.covers(requirement, institutionId, scope,
                candidate.getUser().getId())) {
            log.warn("Out-of-scope shortlist blocked: candidate {} is outside the scope for "
                    + "requirement {}", candidateId, requirement.getId());
            throw NotFoundException.of(CANDIDATE, candidateId);
        }
        return candidate;
    }
}
