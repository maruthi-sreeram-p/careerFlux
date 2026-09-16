package com.careerflux.consent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.careerflux.common.TextUtils;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/**
 * One published version of a notice, exactly as a student was shown it.
 *
 * <p>Immutable once written. Hibernate never issues an UPDATE for this entity,
 * and every column is marked non-updatable as well, so the text a consent refers
 * to is the text that was shown. Changed wording is a new version with its own
 * row, never an edit of an old one; the checksum is what lets that be verified.
 *
 * <p>{@code placeholder} marks engineering text that exists so the mechanism can
 * be built. It is not approved copy and must be replaced by a new version before
 * real students rely on it.
 */
@Entity
@Immutable
@Table(name = "notice_versions")
public class NoticeVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32, updatable = false)
    private ConsentPurpose kind;

    @Column(name = "version", nullable = false, length = 64, updatable = false)
    private String version;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private Instant effectiveFrom;

    @Column(name = "checksum", nullable = false, length = 64, updatable = false)
    private String checksum;

    @Column(name = "body", nullable = false, columnDefinition = "text", updatable = false)
    private String body;

    @Column(name = "placeholder", nullable = false, updatable = false)
    private boolean placeholder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NoticeVersion() {
    }

    public static NoticeVersion publish(ConsentPurpose kind, String version, String body, boolean placeholder,
                                        Instant at) {
        NoticeVersion notice = new NoticeVersion();
        notice.kind = kind;
        notice.version = version;
        notice.body = body;
        notice.checksum = checksumOf(body);
        notice.placeholder = placeholder;
        notice.effectiveFrom = at;
        notice.createdAt = at;
        return notice;
    }

    public static String checksumOf(String body) {
        return TextUtils.sha256(body.getBytes(StandardCharsets.UTF_8));
    }

    public UUID getId() {
        return id;
    }

    public ConsentPurpose getKind() {
        return kind;
    }

    public String getVersion() {
        return version;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public String getChecksum() {
        return checksum;
    }

    public String getBody() {
        return body;
    }

    public boolean isPlaceholder() {
        return placeholder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
