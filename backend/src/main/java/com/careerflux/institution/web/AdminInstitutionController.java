package com.careerflux.institution.web;

import java.util.List;

import com.careerflux.institution.dto.ProvisioningDtos.InstitutionSummary;
import com.careerflux.institution.dto.ProvisioningDtos.ProvisionInstitutionRequest;
import com.careerflux.institution.dto.ProvisioningDtos.ProvisionedInstitution;
import com.careerflux.institution.service.InstitutionProvisioningService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Onboarding a college. Platform operators only.
 *
 * <p>Under {@code /api/admin} rather than {@code /api/platform} because that is
 * where this codebase already puts platform-scoped operations — sources and ops
 * both live there. Both prefixes are gated to the platform administrator in
 * SecurityConfig, so the choice is about consistency rather than access.
 *
 * <p>The URL gate is the coarse one. The real check is
 * {@code INSTITUTION_PROVISION} on the method: a capability rather than a role
 * name, so it keeps working if roles are ever renamed or rearranged, and it says
 * in one word what the endpoint actually requires.
 */
@RestController
@RequestMapping("/api/admin/institutions")
@Tag(name = "Institution onboarding")
public class AdminInstitutionController {

    private final InstitutionProvisioningService provisioning;

    public AdminInstitutionController(InstitutionProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    /**
     * The colleges on this deployment.
     *
     * <p>Name, identifier, claimed domains and status — what an operator needs
     * to see that onboarding worked and to spot a domain typed twice. No users,
     * no student counts, nothing belonging to any tenant.
     */
    @GetMapping
    @Operation(summary = "Colleges on this deployment")
    @PreAuthorize("hasAuthority('INSTITUTION_PROVISION')")
    public List<InstitutionSummary> list() {
        return provisioning.list();
    }

    /**
     * Creates a college, and optionally its first administrator.
     *
     * <p>Returns what was created. It does not return a session, and it does not
     * switch the operator into the new tenant: a platform administrator
     * onboarding a college is not thereby a member of it, and quietly
     * impersonating its administrator would put a CareerFlux operator inside a
     * college's data with no record of a handover.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Onboard a college")
    @PreAuthorize("hasAuthority('INSTITUTION_PROVISION')")
    public ProvisionedInstitution create(@Valid @RequestBody ProvisionInstitutionRequest request) {
        return provisioning.provision(request);
    }
}
