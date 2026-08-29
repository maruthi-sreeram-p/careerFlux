package com.careerflux.discovery.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.careerflux.institution.domain.Department;
import com.careerflux.requirement.domain.CompanyRequirement;
import com.careerflux.security.access.AccessScope;
import com.careerflux.user.UserRepository;

import org.springframework.stereotype.Component;

/**
 * Which students a caller may consider for a requirement.
 *
 * <p>Extracted so discovery and shortlisting cannot disagree. Both need the
 * same answer — the one asks "who do I show?", the other "may I act on this
 * one?" — and two implementations of that rule would eventually drift, leaving
 * a candidate who could be shortlisted but never appeared, or worse, one who
 * appeared to a coordinator who was not entitled to act on them.
 *
 * <p>Three constraints, intersected:
 *
 * <ul>
 *   <li>the college the requirement belongs to;
 *   <li>the departments and batch the company said it will consider;
 *   <li>the departments the caller was granted.
 * </ul>
 *
 * <p>The intersection, never the union. A requirement naming Computer Science
 * and Information Technology widens what the <em>company</em> will look at; it
 * does not widen what a coordinator granted only Computer Science may touch.
 */
@Component
public class DiscoveryScope {

    private final UserRepository users;

    public DiscoveryScope(UserRepository users) {
        this.users = users;
    }

    /** Every student in scope. Empty when the caller has no grant at all. */
    public List<UUID> studentIds(CompanyRequirement requirement, UUID institutionId,
                                 AccessScope scope) {
        if (scope.isEmpty()) {
            return List.of();
        }

        Set<UUID> targetDepartments = requirement.getDepartments().stream()
                .map(Department::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<UUID> allowed;
        if (scope.seesWholeInstitution()) {
            allowed = targetDepartments;
        } else if (targetDepartments.isEmpty()) {
            // The requirement is open to the whole college, so the coordinator's
            // own departments are the limit.
            allowed = new LinkedHashSet<>(scope.departmentIds());
        } else {
            allowed = targetDepartments.stream()
                    .filter(scope.departmentIds()::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (allowed.isEmpty()) {
                // Their department is not among the ones this company will see.
                return List.of();
            }
        }

        Integer graduationYear = requirement.getGraduationYear();
        return users.findStudentsForDiscovery(
                institutionId,
                allowed.isEmpty(),
                allowed.isEmpty() ? List.of(new UUID(0, 0)) : allowed,
                graduationYear == null,
                graduationYear);
    }

    /**
     * Whether one student may be acted on for this requirement.
     *
     * <p>Answered by asking the same question discovery asks, rather than by a
     * shortcut. A shortcut here would be the place a cross-department or
     * cross-batch candidate slipped through on a hand-crafted request, because
     * the id in that request never came from a page the caller was shown.
     */
    public boolean covers(CompanyRequirement requirement, UUID institutionId,
                          AccessScope scope, UUID studentUserId) {
        return studentIds(requirement, institutionId, scope).contains(studentUserId);
    }
}
