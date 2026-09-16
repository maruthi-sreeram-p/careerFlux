package com.careerflux.consent;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which notice version is current for each purpose, and whether a newer version
 * asks students to agree again.
 *
 * <p>Versions name classpath resources under {@code notices/<purpose>/<version>.md}.
 * Publishing approved wording means adding a new file and pointing the purpose at
 * it here; an existing version's text is never edited.
 *
 * @param noticeVersions              the current version per purpose; any purpose
 *                                    not listed uses the engineering placeholder
 * @param requireCurrentNoticeVersion when true, consent given to an older version
 *                                    no longer counts once a newer one is current,
 *                                    so AI processing stops until the student
 *                                    agrees to the new text. The conservative
 *                                    default; whether re-consent is required is
 *                                    a decision for whoever publishes the wording
 */
@ConfigurationProperties(prefix = "careerflux.consent")
public record ConsentProperties(
        Map<ConsentPurpose, String> noticeVersions,
        @DefaultValue("true") boolean requireCurrentNoticeVersion) {

    /** The version name of the engineering placeholder text shipped with the code. */
    public static final String ENGINEERING_PLACEHOLDER = "engineering-placeholder-1";

    public ConsentProperties {
        noticeVersions = noticeVersions == null ? Map.of() : Map.copyOf(new EnumMap<>(noticeVersions));
    }

    public String noticeVersionFor(ConsentPurpose purpose) {
        String configured = noticeVersions.get(purpose);
        return configured == null || configured.isBlank() ? ENGINEERING_PLACEHOLDER : configured.strip();
    }
}
