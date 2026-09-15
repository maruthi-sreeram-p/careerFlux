package com.careerflux.institution.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the institution surface returns.
 *
 * <p>These are deliberately thinner than the student's own view of themselves.
 * A coordinator looking at a class list needs to know who is ready and who is
 * stuck; they do not need the student's phone number, and so it is not here.
 */
public final class InstitutionDtos {

    private InstitutionDtos() {
    }

    public record DepartmentView(UUID id, String name, String code, long studentCount) {
    }

    public record BatchView(UUID id, String name, int graduationYear, long studentCount) {
    }

    /**
     * One student, as staff see them.
     *
     * <p>Contains no contact details beyond the institutional email and no
     * resume content. Whether the caller may open the resume is a separate
     * permission, answered on a separate route.
     *
     * <p>Two CGPA figures, never merged: the college's record and the student's
     * own. Only the first is verified, and only the first is what filters, sorts
     * and eligibility compare.
     */
    public record StudentRow(
            UUID userId,
            UUID candidateId,
            String fullName,
            String email,
            String rollNumber,
            String departmentName,
            String batchName,
            String primaryRole,
            String onboardingStage,
            int profileCompleteness,
            boolean resumeUploaded,
            /** As the college recorded it, on its own scale. Null when it has not. */
            BigDecimal verifiedCgpa,
            /** What the student entered for themselves. Not verified. */
            BigDecimal reportedCgpa,
            BigDecimal cgpaScale,
            /** The verified figure on a ten-point scale, which is what filters compare. */
            BigDecimal normalisedCgpa,
            Instant joinedAt) {
    }

    public record StudentPage(List<StudentRow> content, int page, int size, long totalElements, int totalPages) {
    }

    /** One of a student's stored skills, named as the taxonomy names it. */
    public record StudentSkillRef(UUID id, String name, String slug) {
    }

    /**
     * Where a student stands with one company requirement.
     *
     * <p>Per requirement, deliberately. A student can be SELECTED by one company
     * and SHORTLISTED by another at the same time, and flattening that into a
     * single "placement status" would have to choose one of them to discard.
     */
    public record StudentPlacement(
            UUID requirementId,
            String companyName,
            String roleTitle,
            String stage,
            Instant stageChangedAt) {
    }

    /**
     * One dated, placement-relevant thing that happened.
     *
     * <p>Composed from records that already exist — resumes and shortlist stage
     * changes — rather than from an audit log. What a student does with public
     * job postings is not here at all: which jobs they viewed is private, saves
     * are private until a product decision says otherwise, and CareerFlux cannot
     * tell an Apply click from a confirmed application, so none of the three
     * ever reaches staff.
     */
    public record StudentActivityEntry(Instant at, String type, String summary) {
    }

    /**
     * A student as a placement officer needs to see them.
     *
     * <p>Wraps the directory row rather than repeating it, the same way JobDetail
     * wraps JobSummary. Still no phone number, no resume content, and no
     * credentials of any kind.
     */
    public record StudentDetail(
            StudentRow summary,
            String headline,
            String location,
            BigDecimal verifiedCgpa,
            BigDecimal reportedCgpa,
            BigDecimal cgpaScale,
            BigDecimal normalisedCgpa,
            List<StudentSkillRef> skills,
            List<String> preferences,
            List<StudentPlacement> placements,
            List<StudentActivityEntry> activity) {
    }

    /** What the caller is allowed to see, so the UI can say so plainly. */
    public record ScopeView(
            UUID institutionId,
            String institutionName,
            String role,
            boolean institutionWide,
            List<String> departments,
            List<String> batches,
            List<String> permissions) {
    }

    public record StaffRow(
            UUID userId,
            String fullName,
            String email,
            String role,
            boolean institutionWide,
            List<String> scopeLabels) {
    }
}
