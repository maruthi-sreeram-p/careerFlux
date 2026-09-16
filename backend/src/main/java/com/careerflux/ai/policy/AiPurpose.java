package com.careerflux.ai.policy;

import com.careerflux.consent.ConsentPurpose;

/**
 * Why a student's data would be sent to an AI provider.
 *
 * <p>Each purpose names the consent it needs. Both current purposes need
 * {@link ConsentPurpose#AI_PROCESSING}: reading a resume sends its content, and
 * writing a match narrative sends what was derived from it. Public job postings
 * are not a student's data and have no purpose here.
 */
public enum AiPurpose {

    RESUME_EXTRACTION(ConsentPurpose.AI_PROCESSING),
    MATCH_NARRATIVE(ConsentPurpose.AI_PROCESSING);

    private final ConsentPurpose requiredConsent;

    AiPurpose(ConsentPurpose requiredConsent) {
        this.requiredConsent = requiredConsent;
    }

    public ConsentPurpose requiredConsent() {
        return requiredConsent;
    }
}
