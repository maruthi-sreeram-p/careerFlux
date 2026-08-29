package com.careerflux.institution.dto;

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
            Instant joinedAt) {
    }

    public record StudentPage(List<StudentRow> content, int page, int size, long totalElements, int totalPages) {
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
