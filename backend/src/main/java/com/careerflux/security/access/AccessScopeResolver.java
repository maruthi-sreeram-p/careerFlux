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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AccessScopeResolver.class);

    private final StaffScopeRepository staffScopeRepository;

    public AccessScopeResolver(StaffScopeRepository staffScopeRepository) {
        this.staffScopeRepository = staffScopeRepository;
    }

    @Transactional(readOnly = true)
    public AccessScope resolve(AuthenticatedUser principal) {
        UserRole role = principal.getRole();

        if (role == UserRole.PORTAL_ADMIN) {
            return AccessScope.platform(principal.getUserId());
        }
        if (!role.isStaff()) {
            return AccessScope.student(principal.getUserId(), principal.getInstitutionId());
        }

        // The placement coordinator runs the whole institution; the scope table
        // only ever narrows a department coordinator. Granting this by role rather
        // than by row means a placement coordinator cannot be locked out of their
        // own college by a missing grant.
        if (role == UserRole.PLACEMENT_COORDINATOR) {
            return new AccessScope(principal.getUserId(), principal.getInstitutionId(), role,
                    true, Set.of(), Set.of());
        }

        // A department coordinator sees exactly the departments and batches they
        // have been granted.
        List<StaffScope> grants = staffScopeRepository.findByUserId(principal.getUserId());
        Set<UUID> departments = new HashSet<>();
        Set<UUID> batches = new HashSet<>();

        for (StaffScope grant : grants) {
            if (grant.getScopeType() == ScopeType.DEPARTMENT && grant.getDepartment() != null) {
                departments.add(grant.getDepartment().getId());
            } else if (grant.getScopeType() == ScopeType.BATCH && grant.getBatch() != null) {
                batches.add(grant.getBatch().getId());
            } else if (grant.getScopeType() == ScopeType.INSTITUTION) {
                // Never honoured for a department coordinator. Institution-wide
                // reach is what the placement coordinator role is for; a grant row
                // that handed it to a department role would be a second, unaudited
                // way of becoming one. The row is reported and left alone, not
                // obeyed.
                log.warn("Ignoring an INSTITUTION scope grant held by department coordinator {}",
                        principal.getUserId());
            }
        }

        // A department coordinator with no grants sees nobody. That is the safe
        // default: an unconfigured account should be useless, not omniscient.
        return new AccessScope(principal.getUserId(), principal.getInstitutionId(), role,
                false, departments, batches);
    }
}
