package com.careerflux.consent;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.audit.AuditService;
import com.careerflux.common.error.ConflictException;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A student's consent, per purpose, derived from an append-only history.
 *
 * <p>Every method takes the user id from the caller, and the only caller that
 * reaches it over HTTP passes the signed-in student's own id. There is no route
 * through which one account records, withdraws or reads another's consent, and
 * none through which staff act for a student.
 */
@Service
public class ConsentService {

    public record NoticeView(UUID id, String purpose, String version, Instant effectiveFrom, String checksum,
                             boolean placeholder, String body) {

        static NoticeView of(NoticeVersion notice) {
            return new NoticeView(notice.getId(), notice.getKind().name(), notice.getVersion(),
                    notice.getEffectiveFrom(), notice.getChecksum(), notice.isPlaceholder(), notice.getBody());
        }
    }

    /** One entry in a student's consent history. {@code action} is ACCEPTED or WITHDRAWN. */
    public record ConsentEvent(UUID id, String purpose, String action, UUID noticeVersionId,
                               String noticeVersion, String source, Instant at) {

        static ConsentEvent of(ConsentRecord record) {
            return new ConsentEvent(record.getId(), record.getPurpose().name(),
                    record.isAcceptance() ? "ACCEPTED" : "WITHDRAWN",
                    record.getNoticeVersion().getId(), record.getNoticeVersion().getVersion(),
                    record.getSource().name(), record.getCreatedAt());
        }
    }

    /**
     * @param active            whether this purpose's consent is in force now
     * @param reconsentRequired the student accepted an older notice that is no
     *                          longer current, so the newer text needs agreeing to
     */
    public record PurposeState(String purpose, NoticeView currentNotice, boolean active,
                               boolean reconsentRequired, ConsentEvent latest) {
    }

    public record ConsentOverview(List<PurposeState> purposes, List<ConsentEvent> history) {
    }

    private final ConsentRecordRepository records;
    private final NoticeRegistry notices;
    private final ConsentProperties properties;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public ConsentService(ConsentRecordRepository records, NoticeRegistry notices, ConsentProperties properties,
                          AuditService audit, ApplicationEventPublisher events) {
        this.records = records;
        this.notices = notices;
        this.properties = properties;
        this.audit = audit;
        this.events = events;
    }

    /** Whether this student's consent for the purpose is in force right now. */
    @Transactional(readOnly = true)
    public boolean hasActive(UUID userId, ConsentPurpose purpose) {
        Optional<ConsentRecord> latest = records.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, purpose);
        if (latest.isEmpty() || !latest.get().isAcceptance()) {
            return false;
        }
        return !properties.requireCurrentNoticeVersion()
                || latest.get().getNoticeVersion().getId().equals(notices.current(purpose).getId());
    }

    @Transactional(readOnly = true)
    public ConsentOverview overview(UUID userId) {
        List<PurposeState> states = new ArrayList<>();
        for (ConsentPurpose purpose : ConsentPurpose.values()) {
            NoticeVersion current = notices.current(purpose);
            Optional<ConsentRecord> latest = records.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, purpose);
            boolean accepted = latest.isPresent() && latest.get().isAcceptance();
            boolean onCurrentText = accepted && latest.get().getNoticeVersion().getId().equals(current.getId());
            boolean active = accepted && (onCurrentText || !properties.requireCurrentNoticeVersion());
            states.add(new PurposeState(purpose.name(), NoticeView.of(current), active,
                    accepted && !onCurrentText && properties.requireCurrentNoticeVersion(),
                    latest.map(ConsentEvent::of).orElse(null)));
        }
        return new ConsentOverview(states, history(userId));
    }

    @Transactional(readOnly = true)
    public List<ConsentEvent> history(UUID userId) {
        return records.findByUserIdOrderByCreatedAtDesc(userId).stream().map(ConsentEvent::of).toList();
    }

    /**
     * Records agreement to the current notice for a purpose.
     *
     * <p>The student names the version they read. If it is no longer the current
     * one, nothing is recorded: agreeing to text other than what is in force would
     * be a record of the wrong thing. Agreeing again to a version already in force
     * adds nothing.
     */
    @Transactional
    public ConsentOverview accept(UUID userId, ConsentPurpose purpose, UUID noticeVersionId, ConsentSource source) {
        NoticeVersion current = notices.current(purpose);
        if (noticeVersionId == null || !current.getId().equals(noticeVersionId)) {
            throw new ConflictException("This notice has changed since it was shown. Read the current version "
                    + "before agreeing to it.");
        }
        Optional<ConsentRecord> latest = records.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, purpose);
        boolean alreadyInForce = latest.isPresent() && latest.get().isAcceptance()
                && latest.get().getNoticeVersion().getId().equals(current.getId());
        if (!alreadyInForce) {
            records.saveAndFlush(ConsentRecord.acceptance(userId, purpose, current, source, nextInstant(latest)));
            audit.record("CONSENT_ACCEPTED", "User", userId,
                    "purpose=" + purpose + " notice=" + current.getVersion() + " source=" + source);
        }
        return overview(userId);
    }

    /**
     * Records that the student withdraws a purpose's consent.
     *
     * <p>A new row, never a change to the acceptance it withdraws. Withdrawing
     * something not in force records nothing. Work that depends on the consent
     * hears about it in this same transaction.
     */
    @Transactional
    public ConsentOverview withdraw(UUID userId, ConsentPurpose purpose, ConsentSource source) {
        Optional<ConsentRecord> latest = records.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, purpose);
        if (latest.isPresent() && latest.get().isAcceptance()) {
            records.saveAndFlush(ConsentRecord.withdrawal(userId, purpose, latest.get().getNoticeVersion(), source,
                    nextInstant(latest)));
            audit.record("CONSENT_WITHDRAWN", "User", userId, "purpose=" + purpose + " source=" + source);
            events.publishEvent(new ConsentWithdrawnEvent(userId, purpose));
        }
        return overview(userId);
    }

    /**
     * Now, or just after the previous event when the clock has not moved on.
     *
     * <p>The newest row is the current state, so two events recorded in the same
     * instant would leave the answer to an ordering the database does not
     * promise. Stored to the microsecond, which both databases keep.
     */
    private static Instant nextInstant(Optional<ConsentRecord> latest) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (latest.isPresent() && !now.isAfter(latest.get().getCreatedAt())) {
            return latest.get().getCreatedAt().plus(1, ChronoUnit.MICROS);
        }
        return now;
    }
}
