package com.careerflux.ingestion.pipeline;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.careerflux.common.TextUtils;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobObservation;
import com.careerflux.job.repository.JobObservationRepository;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.source.domain.Company;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether an incoming posting is a job CareerFlux already knows about.
 *
 * <p>Title equality is nowhere near enough — the same opening is routinely listed
 * as "Backend Engineer", "Backend Engineer (Remote)" and "Backend Engineer II" on
 * three different boards. The decision uses several signals in descending order
 * of confidence:
 *
 * <ol>
 *   <li><b>Same source, same external id.</b> Certain: it is the same posting
 *       being seen again.</li>
 *   <li><b>Same requisition id at the same company.</b> Near-certain, but only
 *       when the field actually holds an identifier — see
 *       {@link #isUsableRequisitionId(String)}, because plenty of employers put
 *       something else there entirely.</li>
 *   <li><b>Same apply URL.</b> Two listings that send the candidate to the same
 *       application form are the same opening.</li>
 *   <li><b>Canonical key</b> — company plus normalized title plus city.</li>
 *   <li><b>Fuzzy fallback</b>: same company, same city, and description overlap
 *       above a threshold.</li>
 * </ol>
 *
 * <p>Nothing is ever deleted as a duplicate. A match produces another
 * {@link JobObservation} against the existing job, so the provenance of every
 * sighting survives.
 */
@Component
public class JobDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(JobDeduplicator.class);

    /**
     * Description overlap needed to call two same-company, same-city postings the
     * same job. Set high on purpose: merging two genuinely different openings is a
     * worse failure than showing a candidate a near-duplicate.
     */
    private static final double SIMILARITY_THRESHOLD = 0.72;

    private final JobRepository jobRepository;
    private final JobObservationRepository observationRepository;

    public JobDeduplicator(JobRepository jobRepository, JobObservationRepository observationRepository) {
        this.jobRepository = jobRepository;
        this.observationRepository = observationRepository;
    }

    /**
     * Builds the canonical key for a posting. Deliberately coarse enough to merge
     * cosmetic title variants and precise enough not to merge distinct roles.
     */
    public String canonicalKey(String companySlug, String normalizedTitle, String city) {
        String key = String.join("::",
                companySlug == null ? "unknown" : companySlug,
                normalizedTitle == null ? "untitled" : normalizedTitle,
                city == null ? "any" : city.toLowerCase(Locale.ROOT).strip());
        return TextUtils.truncate(TextUtils.slugify(key), 255);
    }

    public Resolution resolve(NormalizedJob incoming, Company company, java.util.UUID sourceId) {
        // 1. The same posting from the same source.
        Optional<JobObservation> known = observationRepository
                .findBySourceIdAndExternalJobId(sourceId, incoming.externalId());
        if (known.isPresent()) {
            return new Resolution(known.get().getJob(), known.get(), MatchSignal.SAME_SOURCE_EXTERNAL_ID);
        }

        if (company == null) {
            return new Resolution(null, null, MatchSignal.NONE);
        }

        // 2. Requisition id: the employer's own identifier for the opening. Searched
        // across the whole company, because the same requisition is frequently
        // syndicated under different titles.
        if (isUsableRequisitionId(incoming.requisitionId())) {
            List<Job> byRequisition = jobRepository.findByCompanyAndRequisitionId(
                    company.getId(), incoming.requisitionId().strip());
            if (!byRequisition.isEmpty()) {
                return new Resolution(byRequisition.get(0), null, MatchSignal.REQUISITION_ID);
            }
        }

        // 3. Same application destination, also company-wide. Two listings that send
        // the candidate to one application form are one opening whatever they are called.
        String applyKey = normalizeUrl(incoming.applyUrl());
        if (!applyKey.isEmpty()) {
            List<Job> byApplyUrl = jobRepository.findByCompanyAndApplyUrlKey(company.getId(), applyKey);
            if (!byApplyUrl.isEmpty()) {
                return new Resolution(byApplyUrl.get(0), null, MatchSignal.APPLY_URL);
            }
        }

        // 4. Canonical key: company, normalized title and city.
        String key = canonicalKey(company.getSlug(), incoming.normalizedTitle(), incoming.city());
        Optional<Job> byKey = jobRepository.findByCanonicalKey(key);
        if (byKey.isPresent()) {
            return new Resolution(byKey.get(), null, MatchSignal.CANONICAL_KEY);
        }

        // 5. Fuzzy: same company and city, with strongly overlapping descriptions.
        List<Job> candidates = jobRepository.findDuplicateCandidates(
                company.getId(), incoming.normalizedTitle());
        for (Job candidate : candidates) {
            if (!sameCity(candidate.getCity(), incoming.city())) {
                continue;
            }
            double similarity = TextUtils.tokenSimilarity(candidate.getDescription(), incoming.description());
            if (similarity >= SIMILARITY_THRESHOLD) {
                log.debug("Merging '{}' into job {} on description similarity {}",
                        incoming.title(), candidate.getId(), String.format("%.2f", similarity));
                return new Resolution(candidate, null, MatchSignal.DESCRIPTION_SIMILARITY);
            }
        }

        return new Resolution(null, null, MatchSignal.NONE);
    }

    /**
     * Whether a requisition id is actually an identifier.
     *
     * <p>Employers fill this field with whatever they like, and a surprising
     * number put something in it that identifies nothing. Observed on real
     * boards: Stripe sends the literal text "See Opening ID" on all 569 of its
     * postings, and Airbnb sends "ONE" or "MULTI" — a count of how many
     * locations the role covers. Trusting those merged an employer's entire
     * board into a single job, because every posting matched the first one.
     *
     * <p>The test is deliberately shape-based rather than a list of known bad
     * values, since the next board will invent its own placeholder. A real
     * requisition id is a code: it contains a digit and has no spaces.
     * "R11508" and "CSQ127R318" pass; "See Opening ID", "ONE" and "MULTI" do not.
     *
     * <p>Rejecting a genuine id costs little — deduplication simply falls
     * through to the apply URL, which is a stronger signal anyway. Accepting a
     * placeholder costs an entire employer's postings. The rule is tuned for
     * that asymmetry.
     */
    static boolean isUsableRequisitionId(String requisitionId) {
        if (!TextUtils.hasText(requisitionId)) {
            return false;
        }
        String value = requisitionId.strip();
        if (value.length() < 2 || value.length() > 64) {
            return false;
        }
        boolean hasDigit = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)) {
                return false;
            }
            hasDigit |= Character.isDigit(c);
        }
        return hasDigit;
    }

    private boolean sameCity(String left, String right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.strip().equalsIgnoreCase(right.strip());
    }

    /**
     * The stored form of an apply URL. Public so the ingestion service writes the
     * same value it will later be matched against.
     *
     * @see UrlNormalizer for why the query string is filtered rather than dropped
     */
    public String normalizeUrl(String url) {
        return UrlNormalizer.normalize(url);
    }

    /** Which signal produced a match, recorded so a merge decision can be explained. */
    public enum MatchSignal {
        SAME_SOURCE_EXTERNAL_ID,
        REQUISITION_ID,
        APPLY_URL,
        CANONICAL_KEY,
        DESCRIPTION_SIMILARITY,
        NONE
    }

    /**
     * @param job         the canonical job this posting belongs to, or null when it is new
     * @param observation the existing observation, when this exact posting was seen before
     */
    public record Resolution(Job job, JobObservation observation, MatchSignal signal) {

        public boolean isNewJob() {
            return job == null;
        }

        public boolean isRepeatObservation() {
            return observation != null;
        }
    }
}
