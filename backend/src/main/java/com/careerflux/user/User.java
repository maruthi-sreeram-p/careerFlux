package com.careerflux.user;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.careerflux.common.BaseEntity;
import com.careerflux.institution.domain.Batch;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.InstitutionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", length = 160)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private UserRole role = UserRole.STUDENT;

    /**
     * The tenant this account belongs to. Null only for a portal administrator,
     * who operates CareerFlux rather than belonging to a college; the database
     * enforces that rule with a check constraint.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "institution_id")
    private Institution institution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private Batch batch;

    @Column(name = "roll_number", length = 60)
    private String rollNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    /**
     * A SHA-256 digest of the outstanding reset token, never the token itself.
     * The raw value exists only in the reset link, so a copy of this table cannot
     * be used to take over an account.
     */
    @Column(name = "password_reset_token", length = 128)
    private String passwordResetToken;

    @Column(name = "password_reset_expires_at")
    private Instant passwordResetExpiresAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /**
     * The session watermark. A token issued before this instant is refused, which
     * is how a password reset or change ends every session that existed before
     * it. Null means nothing has ever been revoked.
     */
    @Column(name = "sessions_valid_after")
    private Instant sessionsValidAfter;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public String getPasswordResetToken() {
        return passwordResetToken;
    }

    public void setPasswordResetToken(String passwordResetToken) {
        this.passwordResetToken = passwordResetToken;
    }

    public Instant getPasswordResetExpiresAt() {
        return passwordResetExpiresAt;
    }

    public void setPasswordResetExpiresAt(Instant passwordResetExpiresAt) {
        this.passwordResetExpiresAt = passwordResetExpiresAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public Instant getSessionsValidAfter() {
        return sessionsValidAfter;
    }

    public void setSessionsValidAfter(Instant sessionsValidAfter) {
        this.sessionsValidAfter = sessionsValidAfter;
    }

    /**
     * Ends every session issued up to now.
     *
     * <p>Truncated to the millisecond because that is the precision a token
     * records its issue time in. Left finer, a session started in the same
     * millisecond as the revocation — typically the sign-in that follows a reset
     * — would compare as older than the watermark and be refused.
     */
    public void revokeSessions() {
        this.sessionsValidAfter = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /** Whether a token issued at this instant is still honoured for this account. */
    public boolean acceptsSessionIssuedAt(Instant issuedAt) {
        if (sessionsValidAfter == null) {
            return true;
        }
        return issuedAt != null && !issuedAt.isBefore(sessionsValidAfter);
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    /**
     * Whether this account may sign in or keep using a session: it is active, and
     * the college it belongs to is not suspended. A portal administrator belongs to
     * no college, so only their own status applies.
     */
    public boolean canHoldSession() {
        return isActive() && (institution == null || institution.getStatus() != InstitutionStatus.SUSPENDED);
    }

    /** Null for a portal administrator; every other account has one. */
    public java.util.UUID getInstitutionId() {
        return institution == null ? null : institution.getId();
    }

    public Institution getInstitution() {
        return institution;
    }

    public void setInstitution(Institution institution) {
        this.institution = institution;
    }

    public Department getDepartment() {
        return department;
    }

    public void setDepartment(Department department) {
        this.department = department;
    }

    public Batch getBatch() {
        return batch;
    }

    public void setBatch(Batch batch) {
        this.batch = batch;
    }

    public String getRollNumber() {
        return rollNumber;
    }

    public void setRollNumber(String rollNumber) {
        this.rollNumber = rollNumber;
    }
}
