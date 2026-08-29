package com.careerflux.security.access;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.careerflux.institution.domain.ScopeType;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.security.AuthenticatedUser;
import com.careerflux.user.UserRole;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a principal into the {@link AccessScope} their requests are evaluated
 * against.
 *
 * <p>Scope grants live in the database because they are per-person and change as
 * staff move between departments; roles do not, because they are a fixed product
 * decision. This is the seam between the two.
 */
@Component
public class AccessScopeResolver {

    private final StaffScopeRepository staffScopeRepository;

    public AccessScopeResolver(StaffScopeRepository staffScopeRepository) {
        this.staffScopeRepository = staffScopeRepository;
    }

    @Transactional(readOnly = true)
    public AccessScope resolve(AuthenticatedUser principal) {
        UserRole role = principal.getRole();

        if (role == UserRole.PLATFORM_ADMIN) {
            return AccessScope.platform(principal.getUserId());
        }
        if (!role.isStaff()) {
            return AccessScope.student(principal.getUserId(), principal.getInstitutionId());
        }

        // A placement officer runs the whole institution; the scope table only
        // ever narrows a coordinator. Granting this by role rather than by row
        // means an officer cannot be accidentally locked out of their own college
        // by a missing grant.
        if (role == UserRole.PLACEMENT_OFFICER || role == UserRole.COLLEGE_ADMIN) {
            return new AccessScope(principal.getUserId(), principal.getInstitutionId(), role,
                    true, Set.of(), Set.of());
        }

        List<StaffScope> grants = staffScopeRepository.findByUserId(principal.getUserId());
        boolean institutionWide = false;
        Set<UUID> departments = new HashSet<>();
        Set<UUID> batches = new HashSet<>();

        for (StaffScope grant : grants) {
            if (grant.getScopeType() == ScopeType.INSTITUTION) {
                institutionWide = true;
            } else if (grant.getScopeType() == ScopeType.DEPARTMENT && grant.getDepartment() != null) {
                departments.add(grant.getDepartment().getId());
            } else if (grant.getScopeType() == ScopeType.BATCH && grant.getBatch() != null) {
                batches.add(grant.getBatch().getId());
            }
        }

        // A coordinator with no grants sees nobody. That is the safe default: an
        // unconfigured account should be useless, not omniscient.
        return new AccessScope(principal.getUserId(), principal.getInstitutionId(), role,
                institutionWide, departments, batches);
    }
}
