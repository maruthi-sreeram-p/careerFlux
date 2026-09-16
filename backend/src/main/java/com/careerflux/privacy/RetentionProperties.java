package com.careerflux.privacy;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long student data is kept, as configuration rather than numbers scattered
 * through the code.
 *
 * <p>The defaults are the product and engineering policy values chosen for the
 * pilot. They are not a statement of what any law requires; changing them is a
 * configuration change, not a code change.
 *
 * <p><b>{@code dryRun} defaults to true.</b> The scheduled sweeps report what they
 * would remove and remove nothing until an operator has checked these values
 * against the deployment and set it to false. Removals a student asks for
 * directly, such as deleting a resume, do not wait for this switch.
 *
 * @param dryRun                       report only; the scheduled sweeps delete nothing
 * @param extractedTextMaxAge          the most a resume's extracted text is kept when its
 *                                     proposal is never resolved
 * @param supersededResumeVersionsKept previous resumes kept besides the active one
 * @param erasureGracePeriod           how long an erasure request can be cancelled before
 *                                     it is carried out
 * @param orphanFileMinAge             how old an unreferenced file must be before it is
 *                                     treated as orphaned, so an upload still being
 *                                     written is never mistaken for one
 * @param batchSize                    the most rows or files one sweep run removes per kind
 */
@ConfigurationProperties(prefix = "careerflux.retention")
public record RetentionProperties(
        @DefaultValue("true") boolean dryRun,
        @DefaultValue("P30D") Duration extractedTextMaxAge,
        @DefaultValue("2") int supersededResumeVersionsKept,
        @DefaultValue("P30D") Duration erasureGracePeriod,
        @DefaultValue("PT24H") Duration orphanFileMinAge,
        @DefaultValue("200") int batchSize) {

    public RetentionProperties {
        requirePositive(extractedTextMaxAge, "extracted-text-max-age");
        requirePositive(erasureGracePeriod, "erasure-grace-period");
        requirePositive(orphanFileMinAge, "orphan-file-min-age");
        if (supersededResumeVersionsKept < 0) {
            throw new IllegalArgumentException("careerflux.retention.superseded-resume-versions-kept cannot be negative");
        }
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("careerflux.retention.batch-size must be between 1 and 10000");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("careerflux.retention." + name + " must be a positive duration");
        }
    }
}
