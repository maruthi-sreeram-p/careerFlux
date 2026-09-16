package com.careerflux.ai.policy;

import java.util.UUID;

import com.careerflux.consent.ConsentService;

import org.springframework.stereotype.Component;

/**
 * The one server-side answer to "may this student's data go to an AI provider now?"
 *
 * <p>Asked at the moment of use, never earlier and never cached: before AI quota
 * is charged and before the provider is called. Work that was queued, or is
 * being retried, asks again when it runs, so a student who withdrew consent
 * after the work was scheduled is not processed. Nothing on the client decides
 * this.
 *
 * <p>No consent on record means no. That is the default for every student,
 * including those who registered before consent was recorded at all.
 */
@Component
public class AiProcessingPolicy {

    private final ConsentService consents;

    public AiProcessingPolicy(ConsentService consents) {
        this.consents = consents;
    }

    public boolean mayProcess(UUID userId, AiPurpose purpose) {
        if (userId == null || purpose == null) {
            return false;
        }
        return consents.hasActive(userId, purpose.requiredConsent());
    }
}
