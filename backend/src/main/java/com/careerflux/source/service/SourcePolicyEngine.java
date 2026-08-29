package com.careerflux.source.service;

import java.util.ArrayList;
import java.util.List;

import com.careerflux.common.TextUtils;
import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.PolicyDecision;
import com.careerflux.source.domain.RobotsStatus;
import com.careerflux.source.domain.SourceAccessPolicy;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.domain.TosStatus;

import org.springframework.stereotype.Service;

/**
 * The gate every source must pass before it can be read from.
 *
 * <p>This is deliberately deterministic and deliberately conservative. It is
 * never asked to guess: a fact that has not been established counts against the
 * source, not for it. No model is consulted here, and no caller may skip it —
 * {@link SourceLifecycleService} refuses to move a source into ACTIVE without a
 * passing verdict.
 *
 * <p>The rules, in the order a reviewer would apply them:
 * <ol>
 *   <li>Nothing that would require bypassing an access control is ever eligible.
 *       Login walls, CAPTCHAs, anti-bot protection and paywalls are absolute
 *       disqualifiers, not obstacles to work around.</li>
 *   <li>robots.txt must permit the exact path we would fetch. Unreachable
 *       robots.txt is treated as a refusal until it can be read.</li>
 *   <li>Terms of service must have been reviewed by a named person and must not
 *       prohibit automated access.</li>
 *   <li>The access route must be one of the permitted kinds: a public API, a
 *       syndication feed, or a public page.</li>
 *   <li>An adapter must exist, otherwise "active" would be a lie.</li>
 * </ol>
 */
@Service
public class SourcePolicyEngine {

    /**
     * Evaluates a source against every gate and returns all failures at once, so a
     * reviewer sees the full picture rather than fixing one blocker at a time.
     */
    public Verdict evaluate(JobSource source, boolean adapterAvailable) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        SourceAccessPolicy policy = source.getAccessPolicy();

        if (policy == null) {
            blockers.add("No access policy record exists for this source.");
            return new Verdict(false, blockers, warnings);
        }

        // 1. Access controls. These are absolute.
        if (policy.isRequiresAuthentication()) {
            blockers.add("Access requires authentication. CareerFlux does not sign in to third-party sites.");
        }
        if (policy.isRequiresCaptcha()) {
            blockers.add("Access is protected by a CAPTCHA, which must never be bypassed.");
        }
        if (policy.isHasAntiBot()) {
            blockers.add("The site uses anti-bot protection, which signals that automated access is unwanted.");
        }
        if (policy.isPaywalled()) {
            blockers.add("Content is behind a paywall.");
        }

        // 2. robots.txt.
        switch (policy.getRobotsStatus()) {
            case DISALLOWED -> blockers.add("robots.txt disallows this path"
                    + (TextUtils.hasText(policy.getRobotsRule()) ? ": " + policy.getRobotsRule() : "."));
            case NOT_CHECKED -> blockers.add("robots.txt has not been checked yet.");
            case UNAVAILABLE -> blockers.add(
                    "robots.txt could not be read. Access stays blocked until it can be verified.");
            case NOT_PUBLISHED -> warnings.add(
                    "No robots.txt is published. The standard treats that as unrestricted, "
                            + "but there is no explicit permission either.");
            case ALLOWED -> {
                // Permitted.
            }
        }

        // 3. Terms of service.
        switch (policy.getTosStatus()) {
            case PROHIBITED -> blockers.add("Terms of service prohibit automated access.");
            case UNCLEAR -> blockers.add(
                    "Terms of service were reviewed but are ambiguous. Ambiguity is treated as a refusal.");
            case NOT_REVIEWED -> blockers.add("Terms of service have not been reviewed.");
            case RESTRICTED -> warnings.add("Terms permit access with conditions"
                    + (TextUtils.hasText(policy.getTosNotes()) ? ": " + policy.getTosNotes() : "."));
            case PERMITTED -> {
                // Permitted.
            }
        }
        if (policy.getTosStatus() != TosStatus.NOT_REVIEWED && !TextUtils.hasText(policy.getTosReviewedBy())) {
            blockers.add("The terms review is not attributed to a reviewer.");
        }

        // 4. Access route.
        AccessPolicyType route = policy.getAccessPolicy();
        if (route == AccessPolicyType.RESTRICTED || route == AccessPolicyType.FORBIDDEN) {
            blockers.add("The access route is classified as " + route.name().toLowerCase() + ".");
        } else if (route == AccessPolicyType.NOT_DETERMINED) {
            blockers.add("The access route has not been classified.");
        }

        // 5. An adapter has to exist for the source to actually work.
        if (!adapterAvailable) {
            blockers.add("No adapter is registered for this source type.");
        }
        if (source.getSourceType() == SourceType.UNKNOWN) {
            blockers.add("The source type has not been classified.");
        }

        // 6. Rate limiting must be configured; unlimited polling is not acceptable use.
        if (source.getRateLimitPerMinute() <= 0) {
            blockers.add("No rate limit is configured for this source.");
        }
        if (policy.getCrawlDelaySeconds() != null && policy.getCrawlDelaySeconds() > 0) {
            int impliedCeiling = Math.max(1, 60 / policy.getCrawlDelaySeconds());
            if (source.getRateLimitPerMinute() > impliedCeiling) {
                blockers.add("The configured rate limit exceeds the crawl-delay of "
                        + policy.getCrawlDelaySeconds() + "s declared in robots.txt.");
            }
        }

        return new Verdict(blockers.isEmpty(), blockers, warnings);
    }

    /**
     * Applies a reviewer's decision to the policy record. Approval is only recorded
     * when the automated gate also passes, so a human cannot wave through a source
     * whose robots.txt forbids it.
     */
    public void recordDecision(SourceAccessPolicy policy, Verdict verdict, String reviewer, String reason) {
        policy.setDecision(verdict.passed() ? PolicyDecision.APPROVED : PolicyDecision.REJECTED);
        policy.setDecidedBy(reviewer);
        policy.setDecisionReason(TextUtils.truncate(
                verdict.passed() ? reason : String.join(" ", verdict.blockers()), 1000));
        policy.setVerifiedAt(java.time.Instant.now());
    }

    /** Whether an already-approved policy is still trustworthy, or has gone stale. */
    public boolean needsRevalidation(SourceAccessPolicy policy, java.time.Duration maxAge) {
        if (policy == null || policy.getVerifiedAt() == null) {
            return true;
        }
        if (policy.getRobotsStatus() == RobotsStatus.NOT_CHECKED) {
            return true;
        }
        return policy.getVerifiedAt().isBefore(java.time.Instant.now().minus(maxAge));
    }

    /**
     * The outcome of the gate. {@code blockers} are hard failures; {@code warnings}
     * are conditions a reviewer should know about but which do not prevent access.
     */
    public record Verdict(boolean passed, List<String> blockers, List<String> warnings) {

        public Verdict {
            blockers = List.copyOf(blockers);
            warnings = List.copyOf(warnings);
        }
    }
}
