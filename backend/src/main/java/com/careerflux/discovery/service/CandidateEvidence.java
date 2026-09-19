package com.careerflux.discovery.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.institution.domain.Batch;
import com.careerflux.user.User;

/**
 * What CareerFlux can stand behind about a student, for deciding whether they
 * meet a company's stated conditions.
 *
 * <p>Every field here is institution-owned. The college enrols the student into
 * a department and a batch, and the college records the verified CGPA through
 * its own screen. Nothing a student typed about themselves reaches this record:
 * their reported CGPA is not read, and their free-text degree and field of
 * study are not read, because neither has a verified source. A student cannot
 * make themselves eligible, and cannot make themselves ineligible either.
 *
 * <p>Null means "the college has not recorded it", which the evaluator answers
 * with UNKNOWN. It never means zero and never means a pass.
 *
 * @param institutionId  the college the student belongs to
 * @param departmentId   null when the student is not enrolled in one
 * @param graduationYear from the student's batch; null when they have none
 * @param verifiedCgpa   the college's own figure only; null when unrecorded
 */
public record CandidateEvidence(UUID institutionId,
                                UUID departmentId,
                                Integer graduationYear,
                                BigDecimal verifiedCgpa) {

    public static CandidateEvidence of(CandidateProfile profile) {
        User user = profile.getUser();
        UUID departmentId = user == null || user.getDepartment() == null
                ? null : user.getDepartment().getId();
        Batch batch = user == null ? null : user.getBatch();
        UUID institutionId = user == null ? profile.getInstitutionId() : user.getInstitutionId();
        return new CandidateEvidence(institutionId, departmentId,
                batch == null ? null : batch.getGraduationYear(),
                // The verified accessor, never the raw column: it returns null
                // unless an institution recorded the figure, which is what keeps
                // a student's own number out of a formal verdict.
                profile.getVerifiedCgpa());
    }
}
