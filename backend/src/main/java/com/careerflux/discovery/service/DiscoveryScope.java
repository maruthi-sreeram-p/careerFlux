package com.careerflux.discovery.service;

import java.util.Collection;
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

    /**
     * The scope as predicates rather than as a list of ids.
     *
     * <p>Resolving to ids and then passing those ids back into the next query is
     * how a cohort of two thousand became a two-thousand-parameter {@code IN}
     * clause — and the database rebuilds a query plan for every distinct list
     * length it sees. Measured on the college fixture: 500 ids cost 5.9 seconds
     * to plan the first time, 2,000 ids cost 83 seconds, and both were
     * milliseconds on repeat. The ids were derived from these predicates in the
     * first place, so handing the predicates onward keeps one fixed query shape.
     *
     * @param allDepartments true when no department restriction applies
     * @param departmentIds  never empty — an empty {@code in} list is invalid
     *                       JPQL, so an unused restriction carries one
     *                       impossible id
     * @param anyBatch       true when no graduation year was named
     */
    public record Criteria(UUID institutionId, boolean allDepartments,
                           Collection<UUID> departmentIds, boolean anyBatch,
                           Integer graduationYear, boolean empty) {

        /** A caller with no grant at all, who may see nobody. */
        static Criteria nobody(UUID institutionId) {
            return new Criteria(institutionId, false, List.of(new UUID(0, 0)), true, null, true);
        }
    }

    /** The same intersection {@link #studentIds} applies, expressed as predicates. */
    public Criteria criteriaFor(CompanyRequirement requirement, UUID institutionId,
                                AccessScope scope) {
        if (scope.isEmpty()) {
            return Criteria.nobody(institutionId);
        }

        Set<UUID> targetDepartments = requirement.getDepartments().stream()
                .map(Department::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<UUID> allowed;
        if (scope.seesWholeInstitution()) {
            allowed = targetDepartments;
        } else if (targetDepartments.isEmpty()) {
            allowed = new LinkedHashSet<>(scope.departmentIds());
        } else {
            allowed = targetDepartments.stream()
                    .filter(scope.departmentIds()::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (allowed.isEmpty()) {
                return Criteria.nobody(institutionId);
            }
        }

        Integer graduationYear = requirement.getGraduationYear();
        return new Criteria(institutionId,
                allowed.isEmpty(),
                allowed.isEmpty() ? List.of(new UUID(0, 0)) : allowed,
                graduationYear == null,
                graduationYear,
                false);
    }

    /** Every student in scope. Empty when the caller has no grant at all. */
    public List<UUID> studentIds(CompanyRequirement requirement, UUID institutionId,
                                 AccessScope scope) {
        Criteria criteria = criteriaFor(requirement, institutionId, scope);
        if (criteria.empty()) {
            return List.of();
        }
        return users.findStudentsForDiscovery(criteria.institutionId(), criteria.allDepartments(),
                criteria.departmentIds(), criteria.anyBatch(), criteria.graduationYear());
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
        Criteria criteria = criteriaFor(requirement, institutionId, scope);
        if (criteria.empty()) {
            return false;
        }
        // Asked of the one student rather than by listing the cohort and
        // searching it: shortlisting one candidate should not read two thousand
        // rows to decide whether it may.
        return users.isStudentInDiscoveryScope(criteria.institutionId(), criteria.allDepartments(),
                criteria.departmentIds(), criteria.anyBatch(), criteria.graduationYear(),
                studentUserId);
    }
}
