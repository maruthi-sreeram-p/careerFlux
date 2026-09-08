package com.careerflux.ai.proposal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.ai.ResumeExtractionService;
import com.careerflux.ai.proposal.dto.ProposalDtos.ApprovalRequest;
import com.careerflux.ai.proposal.dto.ProposalDtos.Decision;
import com.careerflux.ai.proposal.dto.ProposalDtos.DecisionAction;
import com.careerflux.ai.proposal.dto.ProposalDtos.ProposalPayload;
import com.careerflux.ai.proposal.dto.ProposalDtos.ProposalSummary;
import com.careerflux.ai.proposal.dto.ProposalDtos.ProposalView;
import com.careerflux.ai.proposal.dto.ProposalDtos.ProposedItem;
import com.careerflux.ai.proposal.dto.ProposalDtos.ReviewResult;
import com.careerflux.audit.AuditService;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.Resume;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.common.TextUtils;
import com.careerflux.common.error.BadRequestException;
import com.careerflux.common.error.ConflictException;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.common.logging.CorrelationId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pause between a model reading a resume and the profile changing.
 *
 * <p>Nothing in this class calls a model. Extraction has already happened by the
 * time a proposal exists, and approval is a database operation on a document
 * that was written earlier — which is what keeps the network call out of the
 * transaction that writes the profile, and what makes approving a resume reading
 * as fast as any other form submission.
 *
 * <p><b>Ownership.</b> Every method starts from the signed-in user's own profile
 * and refuses anything that does not belong to it, as a 404 rather than a 403.
 * A 403 would confirm that a proposal with that id exists and belongs to
 * somebody; a 404 says only that this student has no such proposal, which is
 * the same answer whether the id is another student's or invented. The
 * endpoints sit behind the authority the rest of the candidate API already
 * uses; there is no second permission model here.
 */
@Service
public class ProfileProposalService {

    private static final Logger log = LoggerFactory.getLogger(ProfileProposalService.class);

    private final AiProfileProposalRepository proposals;
    private final ProfileProposalBuilder builder;
    private final CandidateProfileService profileService;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public ProfileProposalService(AiProfileProposalRepository proposals,
                                  ProfileProposalBuilder builder,
                                  CandidateProfileService profileService,
                                  ObjectMapper objectMapper,
                                  AuditService auditService) {
        this.proposals = proposals;
        this.builder = builder;
        this.profileService = profileService;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    // -------------------------------------------------------------- creation

    /**
     * Records what a resume reading would change, for the student to answer.
     *
     * <p>Called from inside the short transaction that finishes a resume upload,
     * after the model call has already returned. It writes no profile data.
     *
     * @return the new proposal, or empty when the reading found nothing worth
     *         putting in front of anybody — an empty review screen is a worse
     *         outcome than no review screen
     */
    @Transactional
    public Optional<AiProfileProposal> createFor(CandidateProfile profile, Resume resume,
                                                 ResumeExtractionService.Extraction extraction) {
        List<ProposedItem> items = builder.build(profile, extraction.resume());
        boolean worthReviewing = items.stream()
                .anyMatch(item -> item.decidable() || item.state() == ProposalItemState.INFORMATION_FOUND);
        if (!worthReviewing) {
            log.info("Resume {} produced nothing to review for candidate {}",
                    resume.getId(), profile.getId());
            return Optional.empty();
        }

        // One open question at a time. A student who uploads a corrected CV is
        // answering about that one; leaving the previous proposal pending would
        // let them approve a reading of a document they have already replaced.
        Instant now = Instant.now();
        List<AiProfileProposal> pending =
                proposals.findByCandidateIdAndStatus(profile.getId(), ProposalStatus.PENDING);
        pending.forEach(previous -> previous.supersede(now));
        if (!pending.isEmpty()) {
            proposals.saveAll(pending);
        }

        AiProfileProposal proposal = new AiProfileProposal();
        proposal.setCandidate(profile);
        proposal.setResume(resume);
        proposal.setPayload(serialize(ProposalPayload.of(items)));
        proposal.setEngine(TextUtils.truncate(extraction.engine(), 64));
        proposal.setAiAssisted(extraction.aiAssisted());
        proposal.setCorrelationId(TextUtils.truncate(CorrelationId.current(), 64));
        AiProfileProposal saved = proposals.save(proposal);

        auditService.record("AI_PROPOSAL_CREATED", "AiProfileProposal", saved.getId(),
                "items=" + items.size() + " engine=" + extraction.engine()
                        + " superseded=" + pending.size());
        log.info("Recorded proposal {} with {} items for candidate {} from resume {}",
                saved.getId(), items.size(), profile.getId(), resume.getId());
        return Optional.of(saved);
    }

    // ---------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public List<ProposalSummary> list(UUID userId) {
        CandidateProfile profile = profileService.requireByUserId(userId);
        return proposals.findByCandidateIdOrderByCreatedAtDesc(profile.getId()).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProposalView get(UUID userId, UUID proposalId) {
        return toView(require(userId, proposalId));
    }

    /** The one still waiting, if there is one. What the profile screen asks for. */
    @Transactional(readOnly = true)
    public Optional<ProposalView> pending(UUID userId) {
        CandidateProfile profile = profileService.requireByUserId(userId);
        return proposals.findByCandidateIdAndStatus(profile.getId(), ProposalStatus.PENDING).stream()
                .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .map(this::toView);
    }

    // -------------------------------------------------------------- decisions

    /**
     * Applies the decisions the student made, in one transaction.
     *
     * <p>Everything happens here or nothing does. If writing the profile fails
     * halfway, the proposal is not marked approved either, so a student never
     * ends up with a proposal that says it was applied and a profile that
     * disagrees. There is no partial approval: an unanswered item is a
     * rejection of that item, not a deferral.
     */
    @Transactional
    public ReviewResult approve(UUID userId, UUID proposalId, ApprovalRequest request) {
        AiProfileProposal proposal = require(userId, proposalId);
        requireOpen(proposal);

        List<ProposedItem> items = parse(proposal.getPayload());
        List<ProposedItem> accepted = resolveDecisions(items, request);

        CandidateProfile profile = proposal.getCandidate();
        List<String> applied =
                profileService.applyAcceptedProposal(profile, accepted, proposal.isAiAssisted());

        proposal.reviewedAs(ProposalStatus.APPROVED, profile.getUser(), Instant.now());
        try {
            // Flushed here rather than at commit so a second approval arriving at
            // the same moment surfaces as a conflict the caller understands,
            // instead of an exception escaping from somewhere with no context.
            proposals.saveAndFlush(proposal);
        } catch (ObjectOptimisticLockingFailureException raced) {
            throw new ConflictException("This proposal was already reviewed a moment ago. "
                    + "Reload to see your profile as it now stands.");
        }

        // Keys, never values. An audit trail records which fields a student
        // changed, not what their phone number is.
        auditService.record("AI_PROPOSAL_APPROVED", "AiProfileProposal", proposal.getId(),
                "applied=" + applied.size() + " of=" + items.size()
                        + (applied.isEmpty() ? "" : " keys=" + String.join(",", applied)));
        log.info("Candidate {} approved {} of {} proposed items on proposal {}",
                profile.getId(), applied.size(), items.size(), proposal.getId());

        return new ReviewResult(toView(proposal), applied, profileService.toResponse(profile));
    }

    /** Records that the student looked and wants none of it. Writes nothing to the profile. */
    @Transactional
    public ReviewResult reject(UUID userId, UUID proposalId) {
        AiProfileProposal proposal = require(userId, proposalId);
        requireOpen(proposal);

        CandidateProfile profile = proposal.getCandidate();
        proposal.reviewedAs(ProposalStatus.REJECTED, profile.getUser(), Instant.now());
        try {
            proposals.saveAndFlush(proposal);
        } catch (ObjectOptimisticLockingFailureException raced) {
            throw new ConflictException("This proposal was already reviewed a moment ago.");
        }

        auditService.record("AI_PROPOSAL_REJECTED", "AiProfileProposal", proposal.getId(), null);
        log.info("Candidate {} rejected proposal {}", profile.getId(), proposal.getId());
        return new ReviewResult(toView(proposal), List.of(), profileService.toResponse(profile));
    }

    // ---------------------------------------------------------------- guards

    /**
     * Turns the student's answers into the exact values to write.
     *
     * <p>Anything not mentioned is left out. That is the safe default in a
     * workflow whose purpose is that nothing is written without being asked
     * for, and it means a truncated or partial request can under-apply but
     * never over-apply.
     */
    private static List<ProposedItem> resolveDecisions(List<ProposedItem> items,
                                                       ApprovalRequest request) {
        Map<String, ProposedItem> byKey = new LinkedHashMap<>();
        items.forEach(item -> byKey.put(item.key(), item));

        List<ProposedItem> accepted = new ArrayList<>();
        if (request == null || request.decisions() == null) {
            return accepted;
        }
        for (Decision decision : request.decisions()) {
            if (decision == null || decision.action() == null || decision.action() == DecisionAction.REJECT) {
                continue;
            }
            ProposedItem item = byKey.get(decision.key());
            if (item == null) {
                throw new BadRequestException("This proposal has no item called \""
                        + decision.key() + "\".");
            }
            if (!item.decidable()) {
                // Covers the read-only academic lines, and anything already on
                // the profile or absent from the resume. The interface does not
                // offer these; the rule lives here so that it holds anyway.
                throw new BadRequestException("\"" + item.label()
                        + "\" cannot be applied from a resume reading.");
            }
            String value = switch (decision.action()) {
                case ACCEPT -> item.proposedValue();
                case EDIT -> {
                    if (!item.editable()) {
                        throw new BadRequestException("\"" + item.label()
                                + "\" cannot be edited here. Accept it or change it on your profile.");
                    }
                    if (!TextUtils.hasText(decision.value())) {
                        throw new BadRequestException("Give a value for \"" + item.label()
                                + "\", or reject it instead.");
                    }
                    yield decision.value().strip();
                }
                default -> null;
            };
            if (value == null) {
                continue;
            }
            accepted.add(item.withValue(value));
        }
        return accepted;
    }

    /**
     * This student's proposal, or a 404.
     *
     * <p>The ownership check is the candidate id on the row against the profile
     * of the signed-in account. It is not a claim from the request.
     */
    private AiProfileProposal require(UUID userId, UUID proposalId) {
        CandidateProfile profile = profileService.requireByUserId(userId);
        AiProfileProposal proposal = proposals.findById(proposalId)
                .orElseThrow(() -> NotFoundException.of("Proposal", proposalId));
        if (!profile.getId().equals(proposal.getCandidate().getId())) {
            throw NotFoundException.of("Proposal", proposalId);
        }
        return proposal;
    }

    private static void requireOpen(AiProfileProposal proposal) {
        if (!proposal.getStatus().isOpen()) {
            throw new ConflictException("This proposal has already been "
                    + proposal.getStatus().name().toLowerCase(java.util.Locale.ROOT)
                    + " and cannot be reviewed again. Upload your resume again to get a new one.");
        }
    }

    // ------------------------------------------------------------ conversions

    private ProposalView toView(AiProfileProposal proposal) {
        return new ProposalView(
                proposal.getId(),
                proposal.getResume().getId(),
                proposal.getStatus().name(),
                proposal.getEngine(),
                proposal.isAiAssisted(),
                proposal.getCreatedAt(),
                proposal.getReviewedAt(),
                parse(proposal.getPayload()));
    }

    private ProposalSummary toSummary(AiProfileProposal proposal) {
        List<ProposedItem> items = parse(proposal.getPayload());
        return new ProposalSummary(
                proposal.getId(),
                proposal.getResume().getId(),
                proposal.getStatus().name(),
                proposal.getEngine(),
                proposal.isAiAssisted(),
                items.size(),
                (int) items.stream().filter(ProposedItem::decidable).count(),
                proposal.getCreatedAt(),
                proposal.getReviewedAt());
    }

    private String serialize(ProposalPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // The payload is built from records this application owns. Failing
            // to write it is a programming fault, not a runtime condition.
            throw new IllegalStateException("Could not record the proposal payload", e);
        }
    }

    private List<ProposedItem> parse(String payload) {
        try {
            ProposalPayload parsed = objectMapper.readValue(payload, ProposalPayload.class);
            return parsed.items() == null ? List.of() : parsed.items();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored proposal payload could not be read", e);
        }
    }
}
