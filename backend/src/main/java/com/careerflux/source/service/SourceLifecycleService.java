package com.careerflux.source.service;

import java.time.Instant;
import java.util.List;

import com.careerflux.audit.AuditService;
import com.careerflux.common.TextUtils;
import com.careerflux.common.error.IllegalStateTransitionException;
import com.careerflux.common.error.SourcePolicyException;
import com.careerflux.source.adapter.AdapterRegistry;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.SourceLifecycleEvent;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.source.repository.SourceLifecycleEventRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way a source changes state.
 *
 * <p>Two rules are enforced here and nowhere else, which is what makes them
 * reliable: the transition must be legal according to {@link SourceState}, and
 * entering {@link SourceState#ACTIVE} additionally requires a passing verdict
 * from {@link SourcePolicyEngine}. Every transition is recorded with its reason
 * and its actor, so the console can show a source's whole history.
 */
@Service
public class SourceLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SourceLifecycleService.class);

    private final JobSourceRepository sourceRepository;
    private final SourceLifecycleEventRepository eventRepository;
    private final SourcePolicyEngine policyEngine;
    private final AdapterRegistry adapterRegistry;
    private final AuditService auditService;

    public SourceLifecycleService(JobSourceRepository sourceRepository,
                                  SourceLifecycleEventRepository eventRepository,
                                  SourcePolicyEngine policyEngine,
                                  AdapterRegistry adapterRegistry,
                                  AuditService auditService) {
        this.sourceRepository = sourceRepository;
        this.eventRepository = eventRepository;
        this.policyEngine = policyEngine;
        this.adapterRegistry = adapterRegistry;
        this.auditService = auditService;
    }

    /**
     * Moves a source to a new state.
     *
     * @throws IllegalStateTransitionException when the move is not legal
     * @throws SourcePolicyException when activating a source the policy gate rejects
     */
    @Transactional
    public JobSource transition(JobSource source, SourceState target, String actor, String reason) {
        SourceState current = source.getState();
        if (current == target) {
            return source;
        }
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(
                    "A source cannot move from " + current + " to " + target + ".");
        }

        if (target == SourceState.ACTIVE) {
            SourcePolicyEngine.Verdict verdict = evaluatePolicy(source);
            if (!verdict.passed()) {
                throw new SourcePolicyException(
                        "This source cannot be activated until its access policy is satisfied.",
                        verdict.blockers());
            }
        }

        source.setState(target);
        source.setStateChangedAt(Instant.now());
        if (target == SourceState.ACTIVE) {
            // A newly activated source is due for its first sync immediately.
            source.setNextReviewAt(null);
        }
        sourceRepository.save(source);
        recordEvent(source, current, target, actor, reason);

        // Who moved it is on the lifecycle event and the audit row; the actor is
        // an address when a person did it, and the log is not the place for one.
        log.info("Source {} moved {} -> {} ({})", source.getId(), current, target, reason);
        auditService.recordSystem(actor, "SOURCE_STATE_CHANGED", "JobSource", source.getId(),
                current + " -> " + target + (TextUtils.hasText(reason) ? ": " + reason : ""));
        return source;
    }

    /** Evaluates the policy gate without changing anything. */
    @Transactional(readOnly = true)
    public SourcePolicyEngine.Verdict evaluatePolicy(JobSource source) {
        boolean adapterAvailable = adapterRegistry.has(source.getAdapterKey());
        return policyEngine.evaluate(source, adapterAvailable);
    }

    /**
     * Pulls a source out of service because something changed underneath it.
     * Used by health monitoring and by policy revalidation, both of which run
     * unattended, so it never throws on an illegal transition — it logs and leaves
     * the source where it is.
     */
    @Transactional
    public void flagForReview(JobSource source, String actor, String reason) {
        if (!source.getState().canTransitionTo(SourceState.PENDING_REVIEW)) {
            log.debug("Source {} is in {} and cannot be flagged for review", source.getId(), source.getState());
            return;
        }
        transition(source, SourceState.PENDING_REVIEW, actor, reason);
    }

    /** Records a transition that has already been applied, for example by health monitoring. */
    @Transactional
    public void recordEvent(JobSource source, SourceState from, SourceState to, String actor, String reason) {
        SourceLifecycleEvent event = new SourceLifecycleEvent();
        event.setSource(source);
        event.setFromState(from);
        event.setToState(to);
        event.setActor(TextUtils.truncate(actor, 160));
        event.setReason(TextUtils.truncate(reason, 600));
        event.setOccurredAt(Instant.now());
        eventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public List<SourceLifecycleEvent> history(java.util.UUID sourceId) {
        return eventRepository.findBySourceIdOrderByOccurredAtAsc(sourceId);
    }
}
