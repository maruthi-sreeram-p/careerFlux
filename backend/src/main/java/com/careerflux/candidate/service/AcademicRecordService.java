package com.careerflux.candidate.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.careerflux.candidate.domain.Cgpa;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.CgpaSource;
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
 * <p>Two ways in, and they do not mean the same thing. A student may write
 * their own figure onto their own profile; placement staff may record the
 * institution's. Only the second is verified, and only the verified one is ever
 * compared against a company's stated minimum — otherwise a student would be
 * answering a question about their own eligibility for a drive.
 *
 * <p>Nothing here parses, infers or converts. There is no percentage-to-CGPA
 * formula, no reading of {@code CandidateEducation.grade}, and no derivation
 * from marks. A number arrives from a person who is entitled to state it, or
 * the field stays empty and eligibility stays UNKNOWN.
 *
 * <p>An empty field is not a zero. Clearing a CGPA removes the value and its
 * provenance together, and returns the student to unknown rather than to
 * failing.
 */
@Service
public class AcademicRecordService {

    private static final Logger log = LoggerFactory.getLogger(AcademicRecordService.class);

    private final CandidateProfileRepository profiles;
    private final UserRepository users;
    private final AccessGuard accessGuard;

    public AcademicRecordService(CandidateProfileRepository profiles,
                                 UserRepository users,
                                 AccessGuard accessGuard) {
        this.profiles = profiles;
        this.users = users;
        this.accessGuard = accessGuard;
    }

    /**
     * A student recording their own CGPA.
     *
     * <p>The student is taken from the authenticated principal and never from
     * the request, so there is no id to tamper with. Stored as
     * {@link CgpaSource#STUDENT}: shown back to them, and not used to judge them
     * against a company's requirement.
     */
    @Transactional
    public AcademicRecord updateOwn(BigDecimal cgpa) {
        accessGuard.requirePermission(Permission.SELF_PROFILE_MANAGE);
        UUID userId = accessGuard.currentUserId();

        CandidateProfile profile = profiles.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No candidate profile for this account."));

        BigDecimal checked = Cgpa.validate(cgpa, profile.getCgpaScale());
        profile.recordCgpa(checked, CgpaSource.STUDENT, profile.getUser());
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
     * Stored as {@link CgpaSource#INSTITUTION}, which is what "verified" means.
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
        User recordedBy = users.findById(accessGuard.currentUserId()).orElse(null);
        profile.recordCgpa(checked, CgpaSource.INSTITUTION, recordedBy);
        profiles.save(profile);

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

    private static AcademicRecord toRecord(CandidateProfile profile) {
        return new AcademicRecord(
                profile.getCgpa(),
                profile.getCgpaScale(),
                profile.getCgpaSource() == null ? null : profile.getCgpaSource().name(),
                profile.getVerifiedCgpa() != null,
                profile.getCgpaRecordedBy() == null ? null : profile.getCgpaRecordedBy().getFullName(),
                profile.getCgpaRecordedAt());
    }
}
