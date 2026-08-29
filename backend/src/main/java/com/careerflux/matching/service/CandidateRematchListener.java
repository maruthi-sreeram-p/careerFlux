package com.careerflux.matching.service;

import com.careerflux.candidate.CandidateProfileChangedEvent;
import com.careerflux.matching.rematch.RematchQueue;
import com.careerflux.matching.rematch.RematchReason;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Queues a rescoring run after a candidate's profile or preferences change.
 *
 * <p>Runs after the originating transaction commits, so the work is queued
 * against data that was actually saved rather than a dirty read.
 *
 * <p>This used to score inline on an async thread. That was fine at 400 jobs and
 * wrong at 1,900: a single run takes minutes, several profile edits in a row
 * started several overlapping runs, and nothing could tell the candidate their
 * matches were still being worked out. Queueing makes the work durable,
 * collapsible, retryable, and visible.
 */
@Component
public class CandidateRematchListener {

    private static final Logger log = LoggerFactory.getLogger(CandidateRematchListener.class);

    private final RematchQueue rematchQueue;

    public CandidateRematchListener(RematchQueue rematchQueue) {
        this.rematchQueue = rematchQueue;
    }

    @TransactionalEventListener
    public void onProfileChanged(CandidateProfileChangedEvent event) {
        rematchQueue.enqueue(event.candidateId(), RematchReason.PROFILE_CHANGED);
        log.debug("Queued rematch for candidate {} after {}", event.candidateId(), event.reason());
    }
}
