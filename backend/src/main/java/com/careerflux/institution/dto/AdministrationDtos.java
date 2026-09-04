package com.careerflux.institution.dto;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What a college administrator states when configuring their own college.
 *
 * <p>None of these carry an institution id. The tenant is taken from the
 * authenticated session, so an administrator can only ever build inside their
 * own college — there is no field to point somewhere else with.
 */
public final class AdministrationDtos {

    private AdministrationDtos() {
    }

    public record CreateDepartmentRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 32) String code) {
    }

    public record CreateBatchRequest(
            @NotBlank @Size(max = 120) String name,
            /**
             * Bounded to something a college could plausibly be graduating.
             * Not a business rule so much as a guard against a typo becoming a
             * batch nobody can explain later.
             */
            @Min(1950) @Max(2100) int graduationYear) {
    }

    /**
     * A member of placement staff.
     *
     * <p>The password is set here for the same reason it is set when a college
     * is onboarded: this system has no invitation or email-delivery path, and
     * inventing one for this endpoint alone would be a larger change than
     * configuring a college needs. It is hashed on arrival and never returned.
     *
     * <p>{@code departmentCode} scopes a coordinator to one department. It is
     * meaningless for the other roles and refused there rather than ignored,
     * because a scope silently dropped is a coordinator who can see the whole
     * college.
     */
    public record CreateStaffRequest(
            @NotBlank @Size(max = 160) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 10, max = 128, message = "Use at least 10 characters") String password,
            @NotBlank String role,
            @Size(max = 32) String departmentCode) {
    }

    /** Where a student sits in the college. Either may be null to clear it. */
    public record EnrolmentRequest(UUID departmentId, UUID batchId) {
    }

    public record StaffCreated(UUID id, String fullName, String email, String role, String scope) {
    }

    public record EnrolmentView(UUID userId, String fullName, String departmentName, String batchName) {
    }
}
