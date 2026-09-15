package com.careerflux.candidate.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.careerflux.audit.AuditService;
import com.careerflux.candidate.domain.Cgpa;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.dto.CandidateDtos.AcademicRecord;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.security.access.AccessGuard;
import com.careerflux.user.Permission;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recording a student's CGPA.
 *
 * <p>Two figures, two owners, two fields. A student may write their own onto
 * their profile; placement staff record the college's. They are stored apart
 * (V19), so neither can replace the other: a student saving a figure leaves the
 * college's record exactly as it was. Only the college's is verified, and only
 * it is ever compared against a company's stated minimum — otherwise a student
 * would be answering a question about their own eligibility for a drive.
 *
 * <p>Nothing here parses, infers or converts. There is no percentage-to-CGPA
 * formula, no reading of {@code CandidateEducation.grade}, and no derivation
 * from marks. A number arrives from a person who is entitled to state it, or
 * the field stays empty and eligibility stays UNKNOWN.
 *
 * <p>An empty field is not a zero. Clearing a CGPA removes the value and its
 * provenance together, and returns the student to unknown rather than to
 * failing. Every change to the college's record is audited, with the figures
 * before and after, in that college's own audit trail.
 */
@Service
public class AcademicRecordService {

    private static final Logger log = LoggerFactory.getLogger(AcademicRecordService.class);

    private final CandidateProfileRepository profiles;
    private final UserRepository users;
    private final AccessGuard accessGuard;
    private final AuditService audit;

    public AcademicRecordService(CandidateProfileRepository profiles,
                                 UserRepository users,
                                 AccessGuard accessGuard,
                                 AuditService audit) {
        this.profiles = profiles;
        this.users = users;
        this.accessGuard = accessGuard;
        this.audit = audit;
    }

    /**
     * A student recording their own CGPA.
     *
     * <p>The student is taken from the authenticated principal and never from
     * the request, so there is no id to tamper with. It is written to the
     * student's own field only: shown back to them and to their placement
     * staff as theirs, never used to judge them against a company's requirement,
     * and unable to change the college's record.
     */
    @Transactional
    public AcademicRecord updateOwn(BigDecimal cgpa) {
        accessGuard.requirePermission(Permission.SELF_PROFILE_MANAGE);
        UUID userId = accessGuard.currentUserId();

        CandidateProfile profile = profiles.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No candidate profile for this account."));

        BigDecimal checked = Cgpa.validate(cgpa, profile.getCgpaScale());
        profile.recordReportedCgpa(checked);
        profiles.save(profile);

        log.info("Student {} recorded their own CGPA", userId);
        return toRecord(profile);
    }

    /**
     * Placement staff recording the institution's academic record for a student.
     *
     * <p>Gated on {@code PLACEMENT_ELIGIBILITY_MANAGE}, which the role model
     * already describes as deciding who is officially eligible for a drive, as
     * distinct from who matches. That is exactly this. No permission was created
     * for the feature.
     *
     * <p>The student is resolved from the database and checked against the
     * caller's own scope, so a user id in the path proves nothing on its own.
     * This is the only way a verified CGPA is ever written.
     */
    @Transactional
    public AcademicRecord updateForStudent(UUID studentUserId, BigDecimal cgpa) {
        accessGuard.requirePermission(Permission.PLACEMENT_ELIGIBILITY_MANAGE);

        CandidateProfile profile = profiles.findByUserId(studentUserId)
                .orElseThrow(() -> NotFoundException.of("Student", studentUserId));

        // Institution and department scope in one check, and not-found rather
        // than forbidden for anything outside it.
        accessGuard.requireCanReadCandidate(profile);

        BigDecimal checked = Cgpa.validate(cgpa, profile.getCgpaScale());
        BigDecimal previous = profile.getVerifiedCgpa();
        User recordedBy = users.findById(accessGuard.currentUserId()).orElse(null);
        profile.recordVerifiedCgpa(checked, recordedBy);
        profiles.save(profile);

        // The figures, which is what a dispute would ask about, and no name: the
        // row belongs to this college's own trail, keyed by the student's id.
        audit.record(checked == null ? "VERIFIED_CGPA_CLEARED" : "VERIFIED_CGPA_RECORDED", "User", studentUserId,
                "previous=" + plain(previous) + " new=" + plain(checked)
                        + " scale=" + plain(profile.getCgpaScale()));
        log.info("Institutional CGPA recorded for student {} by {}",
                studentUserId, accessGuard.currentUserId());
        return toRecord(profile);
    }

    /** One student's academic record, for staff who may read them. */
    @Transactional(readOnly = true)
    public AcademicRecord forStudent(UUID studentUserId) {
        accessGuard.requirePermission(Permission.STUDENT_READ_SCOPED);
        CandidateProfile profile = profiles.findByUserId(studentUserId)
                .orElseThrow(() -> NotFoundException.of("Student", studentUserId));
        accessGuard.requireCanReadCandidate(profile);
        return toRecord(profile);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "none" : value.stripTrailingZeros().toPlainString();
    }

    private static AcademicRecord toRecord(CandidateProfile profile) {
        BigDecimal verified = profile.getVerifiedCgpa();
        return new AcademicRecord(
                profile.getCgpaScale(),
                profile.getReportedCgpa(),
                profile.getReportedCgpaRecordedAt(),
                verified,
                verified == null || profile.getCgpaRecordedBy() == null
                        ? null : profile.getCgpaRecordedBy().getFullName(),
                verified == null ? null : profile.getCgpaRecordedAt(),
                verified != null);
    }
}
