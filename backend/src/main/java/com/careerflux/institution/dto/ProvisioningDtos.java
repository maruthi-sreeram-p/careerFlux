package com.careerflux.institution.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Onboarding a college onto CareerFlux.
 *
 * <p>Separate from {@link InstitutionDtos} because these are the platform
 * operator's shapes, not a college's: they are the only payloads in the product
 * that create a tenant.
 */
public final class ProvisioningDtos {

    private ProvisioningDtos() {
    }

    /**
     * What a platform operator may state when creating a college.
     *
     * <p>Notice what is absent. There is no institution id — the identity of a
     * new tenant is not the client's to choose. There is no slug: it is derived
     * from the name, so two requests naming the same college collide on the
     * database's unique index rather than quietly becoming two tenants. There is
     * no {@code createdBy}: the actor comes from the authenticated session, and
     * accepting it from the body would let the audit trail be written by
     * whoever sent the request.
     */
    public record ProvisionInstitutionRequest(
            @NotBlank @Size(max = 200) String name,

            /**
             * Comma-separated, and optional. A college that hands out a
             * registration code instead is a supported way to onboard, so an
             * empty claim is not an error — it just means nobody is recognised
             * by their address alone.
             */
            @Size(max = 500) String emailDomains,

            @Size(max = 60) String shortName,
            @Size(max = 120) String city,
            @Size(max = 40) String registrationCode,

            /** Optional. Omit to create the college and add its administrator later. */
            @Valid InitialAdmin initialAdmin) {
    }

    /**
     * The college's first administrator.
     *
     * <p>The password is set here because that is the only account-creation
     * mechanism this system has — there is no invitation or email-delivery path
     * to reuse, and inventing one would be a larger change than onboarding
     * needs. It is hashed with the same encoder as every other account and is
     * never returned, logged, or readable back.
     */
    public record InitialAdmin(
            @NotBlank @Size(max = 160) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 10, max = 128, message = "Use at least 10 characters") String password) {
    }

    /**
     * What creation returns.
     *
     * <p>Enough to confirm what was made and to hand over to the college, and
     * nothing else: no password, no token, no session, and no data belonging to
     * any other tenant.
     */
    public record ProvisionedInstitution(
            UUID id,
            String name,
            String slug,
            String emailDomains,
            String registrationCode,
            String status,
            Instant createdAt,
            /** Null when no administrator was created alongside the college. */
            UUID collegeAdminId,
            String collegeAdminEmail) {
    }

    /** A row in the platform operator's list of colleges. */
    public record InstitutionSummary(
            UUID id,
            String name,
            String slug,
            String emailDomains,
            String status,
            Instant createdAt) {
    }
}
