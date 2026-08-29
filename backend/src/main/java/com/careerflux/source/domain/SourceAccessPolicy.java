package com.careerflux.source.domain;

import java.time.Instant;

import com.careerflux.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * The recorded answer to "are we allowed to read this, and on what terms?".
 *
 * <p>Every field is either measured (robots.txt, authentication probes) or set by
 * a named human at a recorded time (terms review, final decision). Nothing here
 * is inferred by a model, because these are the facts that would have to be
 * defended if a site operator ever asked why CareerFlux was reading their pages.
 */
@Entity
@Table(name = "source_access_policies")
public class SourceAccessPolicy extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false, unique = true)
    private JobSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "robots_status", nullable = false, length = 32)
    private RobotsStatus robotsStatus = RobotsStatus.NOT_CHECKED;

    @Column(name = "robots_url", length = 500)
    private String robotsUrl;

    /** The specific directive that decided the outcome, quoted verbatim. */
    @Column(name = "robots_rule", length = 500)
    private String robotsRule;

    @Column(name = "robots_checked_at")
    private Instant robotsCheckedAt;

    @Column(name = "crawl_delay_seconds")
    private Integer crawlDelaySeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "tos_status", nullable = false, length = 32)
    private TosStatus tosStatus = TosStatus.NOT_REVIEWED;

    @Column(name = "tos_url", length = 500)
    private String tosUrl;

    @Column(name = "tos_notes", length = 1000)
    private String tosNotes;

    @Column(name = "tos_reviewed_at")
    private Instant tosReviewedAt;

    @Column(name = "tos_reviewed_by", length = 160)
    private String tosReviewedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_policy", nullable = false, length = 32)
    private AccessPolicyType accessPolicy = AccessPolicyType.NOT_DETERMINED;

    @Column(name = "requires_authentication", nullable = false)
    private boolean requiresAuthentication;

    @Column(name = "requires_captcha", nullable = false)
    private boolean requiresCaptcha;

    @Column(name = "has_anti_bot", nullable = false)
    private boolean hasAntiBot;

    @Column(name = "is_paywalled", nullable = false)
    private boolean paywalled;

    /** Which fields CareerFlux stores from this source, recorded for data minimisation. */
    @Column(name = "allowed_fields", length = 600)
    private String allowedFields;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 24)
    private PolicyDecision decision = PolicyDecision.PENDING;

    @Column(name = "decision_reason", length = 1000)
    private String decisionReason;

    @Column(name = "decided_by", length = 160)
    private String decidedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** True only when nothing about this source requires bypassing an access control. */
    public boolean isTechnicallyOpen() {
        return !requiresAuthentication && !requiresCaptcha && !hasAntiBot && !paywalled;
    }

    public JobSource getSource() {
        return source;
    }

    public void setSource(JobSource source) {
        this.source = source;
    }

    public RobotsStatus getRobotsStatus() {
        return robotsStatus;
    }

    public void setRobotsStatus(RobotsStatus robotsStatus) {
        this.robotsStatus = robotsStatus;
    }

    public String getRobotsUrl() {
        return robotsUrl;
    }

    public void setRobotsUrl(String robotsUrl) {
        this.robotsUrl = robotsUrl;
    }

    public String getRobotsRule() {
        return robotsRule;
    }

    public void setRobotsRule(String robotsRule) {
        this.robotsRule = robotsRule;
    }

    public Instant getRobotsCheckedAt() {
        return robotsCheckedAt;
    }

    public void setRobotsCheckedAt(Instant robotsCheckedAt) {
        this.robotsCheckedAt = robotsCheckedAt;
    }

    public Integer getCrawlDelaySeconds() {
        return crawlDelaySeconds;
    }

    public void setCrawlDelaySeconds(Integer crawlDelaySeconds) {
        this.crawlDelaySeconds = crawlDelaySeconds;
    }

    public TosStatus getTosStatus() {
        return tosStatus;
    }

    public void setTosStatus(TosStatus tosStatus) {
        this.tosStatus = tosStatus;
    }

    public String getTosUrl() {
        return tosUrl;
    }

    public void setTosUrl(String tosUrl) {
        this.tosUrl = tosUrl;
    }

    public String getTosNotes() {
        return tosNotes;
    }

    public void setTosNotes(String tosNotes) {
        this.tosNotes = tosNotes;
    }

    public Instant getTosReviewedAt() {
        return tosReviewedAt;
    }

    public void setTosReviewedAt(Instant tosReviewedAt) {
        this.tosReviewedAt = tosReviewedAt;
    }

    public String getTosReviewedBy() {
        return tosReviewedBy;
    }

    public void setTosReviewedBy(String tosReviewedBy) {
        this.tosReviewedBy = tosReviewedBy;
    }

    public AccessPolicyType getAccessPolicy() {
        return accessPolicy;
    }

    public void setAccessPolicy(AccessPolicyType accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    public boolean isRequiresAuthentication() {
        return requiresAuthentication;
    }

    public void setRequiresAuthentication(boolean requiresAuthentication) {
        this.requiresAuthentication = requiresAuthentication;
    }

    public boolean isRequiresCaptcha() {
        return requiresCaptcha;
    }

    public void setRequiresCaptcha(boolean requiresCaptcha) {
        this.requiresCaptcha = requiresCaptcha;
    }

    public boolean isHasAntiBot() {
        return hasAntiBot;
    }

    public void setHasAntiBot(boolean hasAntiBot) {
        this.hasAntiBot = hasAntiBot;
    }

    public boolean isPaywalled() {
        return paywalled;
    }

    public void setPaywalled(boolean paywalled) {
        this.paywalled = paywalled;
    }

    public String getAllowedFields() {
        return allowedFields;
    }

    public void setAllowedFields(String allowedFields) {
        this.allowedFields = allowedFields;
    }

    public PolicyDecision getDecision() {
        return decision;
    }

    public void setDecision(PolicyDecision decision) {
        this.decision = decision;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }
}
