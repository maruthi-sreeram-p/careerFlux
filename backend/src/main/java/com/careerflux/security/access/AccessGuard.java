package com.careerflux.security.access;

import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.common.error.ForbiddenException;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.security.AuthenticatedUser;
import com.careerflux.security.CurrentUser;
import com.careerflux.user.Permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single place resource-level authorization is decided.
 *
 * <p>Role checks answer "may this kind of user do this kind of thing?" and are
 * handled by {@code @PreAuthorize}. This class answers the harder question:
 * "may <em>this</em> user act on <em>this</em> record?" — which is where
 * multi-tenant systems actually leak.
 *
 * <p>Two conventions matter here:
 *
 * <p><b>Cross-tenant access is reported as not-found, never as forbidden.</b> A
 * 403 confirms the record exists, which is itself a disclosure: it tells a
 * probing caller that a given id is real and belongs to somebody. A caller with
 * no business knowing a record exists is told it does not.
 *
 * <p><b>Every denial is logged.</b> An authorization failure in an institutional
 * system is either a bug or an attempt, and both are worth seeing.
 */
@Component
public class AccessGuard {

    private static final Logger log = LoggerFactory.getLogger(AccessGuard.class);

    private final CurrentUser currentUser;
    private final AccessScopeResolver scopeResolver;

    public AccessGuard(CurrentUser currentUser, AccessScopeResolver scopeResolver) {
        this.currentUser = currentUser;
        this.scopeResolver = scopeResolver;
    }

    public AccessScope scope() {
        return scopeResolver.resolve(currentUser.require());
    }

    public UUID currentUserId() {
        return currentUser.requireId();
    }

    /**
     * The caller's institution, for scoping a query.
     *
     * @throws ForbiddenException when the caller has no institution, which means
     *         a portal administrator reached an institutional endpoint
     */
    public UUID requireInstitutionId() {
        AccessScope scope = scope();
        if (scope.institutionId() == null) {
            throw new ForbiddenException(
                    "This is an institutional endpoint and your account is not attached to an institution.");
        }
        return scope.institutionId();
    }

    public void requirePermission(Permission permission) {
        AuthenticatedUser principal = currentUser.require();
        if (!principal.hasPermission(permission)) {
            // The account id, never the address: a denial log is read by whoever
            // operates the platform, and a student's email is not theirs to read.
            log.warn("Denied {} to user {} (role {})", permission, principal.getUserId(), principal.getRole());
            throw new ForbiddenException("You do not have permission to do that.");
        }
    }

    /**
     * Confirms a record belongs to the caller's institution.
     *
     * <p>Nobody passes by role. A portal administrator belongs to no institution,
     * so no institutional record matches them: owning the platform is not a way
     * into a college's data. This used to wave the portal administrator through
     * on the grounds that they held no student-read permission anyway — true, but
     * a check that is safe only because of a different check is one refactor away
     * from not being safe at all.
     */
    public void requireSameInstitution(UUID resourceInstitutionId, String resourceLabel, Object resourceId) {
        AccessScope scope = scope();
        if (resourceInstitutionId == null || !resourceInstitutionId.equals(scope.institutionId())) {
            log.warn("Cross-tenant access blocked: user {} (institution {}) requested {} {} in institution {}",
                    scope.userId(), scope.institutionId(), resourceLabel, resourceId, resourceInstitutionId);
            throw NotFoundException.of(resourceLabel, resourceId);
        }
    }

    /**
     * Confirms the caller may read this candidate.
     *
     * <p>Passes when the candidate is the caller, or when the caller is staff
     * holding the relevant permission whose scope covers the student's department
     * or batch.
     */
    public void requireCanReadCandidate(CandidateProfile candidate) {
        AccessScope scope = scope();
        AuthenticatedUser principal = currentUser.require();

        // The student's own record.
        if (candidate.getUser() != null && scope.userId().equals(candidate.getUser().getId())) {
            return;
        }

        // Everything below is somebody else's record, so a permission is required.
        if (!principal.hasPermission(Permission.STUDENT_READ_SCOPED)) {
            log.warn("User {} attempted to read candidate {} without STUDENT_READ_SCOPED",
                    scope.userId(), candidate.getId());
            throw NotFoundException.of("Candidate", candidate.getId());
        }

        requireSameInstitution(candidate.getInstitutionId(), "Candidate", candidate.getId());

        UUID departmentId = candidate.getUser() == null || candidate.getUser().getDepartment() == null
                ? null : candidate.getUser().getDepartment().getId();
        UUID batchId = candidate.getUser() == null || candidate.getUser().getBatch() == null
                ? null : candidate.getUser().getBatch().getId();

        if (!scope.covers(departmentId, batchId)) {
            log.warn("User {} is out of scope for candidate {} (department {}, batch {})",
                    scope.userId(), candidate.getId(), departmentId, batchId);
            throw NotFoundException.of("Candidate", candidate.getId());
        }
    }

    /**
     * Confirms the caller may open this candidate's resume file.
     *
     * <p>Held apart from {@link #requireCanReadCandidate} on purpose. Seeing that
     * a student is ready and reading the document they wrote are different acts,
     * and a department coordinator is trusted with the first but not the second.
     */
    public void requireCanReadResume(CandidateProfile candidate) {
        AccessScope scope = scope();
        if (candidate.getUser() != null && scope.userId().equals(candidate.getUser().getId())) {
            return;
        }
        AuthenticatedUser principal = currentUser.require();
        if (!principal.hasPermission(Permission.STUDENT_RESUME_READ)) {
            log.warn("User {} attempted to read the resume of candidate {} without STUDENT_RESUME_READ",
                    scope.userId(), candidate.getId());
            throw NotFoundException.of("Resume", candidate.getId());
        }
        requireCanReadCandidate(candidate);
    }

    /** Only the owner may change their own profile. Staff never edit it for them. */
    public void requireIsSelf(UUID userId, String resourceLabel, Object resourceId) {
        if (!currentUser.requireId().equals(userId)) {
            log.warn("User {} attempted to modify {} {} belonging to {}",
                    currentUser.requireId(), resourceLabel, resourceId, userId);
            throw NotFoundException.of(resourceLabel, resourceId);
        }
    }
}
