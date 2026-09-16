package com.careerflux.consent;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/**
 * One consent event: a student accepting a notice, or withdrawing that acceptance.
 *
 * <p>The ledger is append-only. A withdrawal is a new row with
 * {@code withdrawn_at} set; it never touches the acceptance before it, so what
 * was agreed, to which text and when survives every later change of mind.
 * Re-consenting is another acceptance row. The current state of a purpose is
 * whatever the newest row for it says.
 *
 * <p>Exactly one of {@code accepted_at} and {@code withdrawn_at} is set, which the
 * database checks as well.
 */
@Entity
@Immutable
@Table(name = "consent_records")
public class ConsentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32, updatable = false)
    private ConsentPurpose purpose;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_version_id", nullable = false, updatable = false)
    private NoticeVersion noticeVersion;

    @Column(name = "accepted_at", updatable = false)
    private Instant acceptedAt;

    @Column(name = "withdrawn_at", updatable = false)
    private Instant withdrawnAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32, updatable = false)
    private ConsentSource source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConsentRecord() {
    }

    public static ConsentRecord acceptance(UUID userId, ConsentPurpose purpose, NoticeVersion notice,
                                           ConsentSource source, Instant at) {
        ConsentRecord record = base(userId, purpose, notice, source, at);
        record.acceptedAt = at;
        return record;
    }

    public static ConsentRecord withdrawal(UUID userId, ConsentPurpose purpose, NoticeVersion notice,
                                           ConsentSource source, Instant at) {
        ConsentRecord record = base(userId, purpose, notice, source, at);
        record.withdrawnAt = at;
        return record;
    }

    private static ConsentRecord base(UUID userId, ConsentPurpose purpose, NoticeVersion notice,
                                      ConsentSource source, Instant at) {
        ConsentRecord record = new ConsentRecord();
        record.userId = userId;
        record.purpose = purpose;
        record.noticeVersion = notice;
        record.source = source;
        record.createdAt = at;
        return record;
    }

    public boolean isAcceptance() {
        return acceptedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public ConsentPurpose getPurpose() {
        return purpose;
    }

    public NoticeVersion getNoticeVersion() {
        return noticeVersion;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getWithdrawnAt() {
        return withdrawnAt;
    }

    public ConsentSource getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
