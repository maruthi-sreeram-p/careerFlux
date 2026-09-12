package com.careerflux.institution.domain;

import java.time.Instant;
import java.util.UUID;

import com.careerflux.user.User;

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

/**
 * One grant of visibility to a member of placement staff.
 *
 * <p>This is what stops a department coordinator for CSE from reading the
 * mechanical students' profiles. Scopes are additive and are resolved into a
 * {@link com.careerflux.security.access.AccessScope} on every request.
 *
 * <p>Only DEPARTMENT and BATCH grants mean anything. A placement coordinator
 * covers the institution by role, and an INSTITUTION grant is never honoured for
 * a department coordinator, so there is no factory for one: the type survives
 * only because the database constraint still names it and old rows may carry it.
 */
@Entity
@Table(name = "staff_scopes")
public class StaffScope {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "institution_id", nullable = false)
    private Institution institution;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 24)
    private ScopeType scopeType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private Batch batch;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public static StaffScope forDepartment(User user, Institution institution, Department department) {
        StaffScope scope = new StaffScope();
        scope.user = user;
        scope.institution = institution;
        scope.scopeType = ScopeType.DEPARTMENT;
        scope.department = department;
        return scope;
    }

    public static StaffScope forBatch(User user, Institution institution, Batch batch) {
        StaffScope scope = new StaffScope();
        scope.user = user;
        scope.institution = institution;
        scope.scopeType = ScopeType.BATCH;
        scope.batch = batch;
        return scope;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Institution getInstitution() {
        return institution;
    }

    public void setInstitution(Institution institution) {
        this.institution = institution;
    }

    public ScopeType getScopeType() {
        return scopeType;
    }

    public void setScopeType(ScopeType scopeType) {
        this.scopeType = scopeType;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
