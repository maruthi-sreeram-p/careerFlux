package com.careerflux.consent;

import java.util.Locale;

/**
 * What a student is agreeing to.
 *
 * <p>Each purpose is accepted, and withdrawn, on its own, against a notice of the
 * same kind. Only {@link #AI_PROCESSING} gates anything today: it is what a
 * student's data needs before it may be sent to an AI provider. Whether the other
 * two gate registration or resume upload is an open decision, so the ledger
 * records them without enforcing them.
 */
public enum ConsentPurpose {

    PRIVACY_NOTICE,
    RESUME_PROCESSING,
    AI_PROCESSING;

    /** The purpose as it appears in a URL or a resource path, e.g. {@code ai-processing}. */
    public String slug() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Parses either form: {@code AI_PROCESSING} or {@code ai-processing}. Null when neither. */
    public static ConsentPurpose parse(String raw) {
        if (raw == null) {
            return null;
        }
        String normalised = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ConsentPurpose purpose : values()) {
            if (purpose.name().equals(normalised)) {
                return purpose;
            }
        }
        return null;
    }
}
