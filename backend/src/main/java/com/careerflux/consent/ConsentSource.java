package com.careerflux.consent;

import java.util.Locale;

/**
 * Where in the product a consent decision was made.
 *
 * <p>Descriptive only. It says which screen the student was on, so a later reader
 * can tell a decision made while uploading a resume from one made in settings.
 * It never grants anything: who the decision belongs to always comes from the
 * session.
 */
public enum ConsentSource {

    REGISTRATION,
    ONBOARDING,
    RESUME_UPLOAD,
    SETTINGS;

    /** Unknown or absent values are recorded as {@link #SETTINGS}. */
    public static ConsentSource parseOrSettings(String raw) {
        if (raw == null || raw.isBlank()) {
            return SETTINGS;
        }
        String normalised = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ConsentSource source : values()) {
            if (source.name().equals(normalised)) {
                return source;
            }
        }
        return SETTINGS;
    }
}
