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
 * <p>Four constraints, intersected:
 *
 * <ul>
 *   <li>the college the requirement belongs to;
 *   <li>the departments and graduation year the company said it will consider;
 *   <li>the departments the caller was granted;
 *   <li>the batches the caller was narrowed to, if any.
 * </ul>
 *
 * <p>The intersection, never the union. A requirement naming Computer Science
 * and Information Technology widens what the <em>company</em> will look at; it
 * does not widen what a coordinator granted only Computer Science may touch.
 *
 * <p><b>It fails closed.</b> "All departments" is only ever the answer for an
 * institution-wide caller. A department coordinator is never given it, whatever
 * their grants turn out to be: one with no department — nothing granted, or
 * batches only — sees nobody, because a batch grant narrows a department and is
 * not a scope of its own.
 */
@Component
public class DiscoveryScope {

    /** Stands in for an unused restriction: an empty {@code in} list is invalid JPQL. */
    private static final List<UUID> UNUSED = List.of(new UUID(0, 0));

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
     * @param allDepartments  true when no department restriction applies; only
     *                        ever for an institution-wide caller
     * @param departmentIds   never empty — an unused restriction carries one
     *                        impossible id
     * @param anyBatch        true when the company named no graduation year
     * @param anyGrantedBatch true when the caller is not narrowed to batches
     * @param grantedBatchIds never empty, for the same reason as departmentIds
     */
    public record Criteria(UUID institutionId, boolean allDepartments,
                           Collection<UUID> departmentIds, boolean anyBatch,
                           Integer graduationYear, boolean anyGrantedBatch,
                           Collection<UUID> grantedBatchIds, boolean empty) {

        /** A caller who may see nobody. */
        static Criteria nobody(UUID institutionId) {
            return new Criteria(institutionId, false, UNUSED, true, null, true, UNUSED, true);
        }
    }

    /** The same intersection {@link #studentIds} applies, expressed as predicates. */
    public Criteria criteriaFor(CompanyRequirement requirement, UUID institutionId,
                                AccessScope scope) {
        // No department, no students: this covers the coordinator granted
        // nothing and the one granted only batches.
        if (scope.isEmpty()) {
            return Criteria.nobody(institutionId);
        }

        Set<UUID> targetDepartments = requirement.getDepartments().stream()
                .map(Department::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Integer graduationYear = requirement.getGraduationYear();

        if (scope.seesWholeInstitution()) {
            // The placement coordinator: every department the company named, or
            // the whole college when it named none.
            return new Criteria(institutionId,
                    targetDepartments.isEmpty(),
                    targetDepartments.isEmpty() ? UNUSED : targetDepartments,
                    graduationYear == null,
                    graduationYear,
                    true,
                    UNUSED,
                    false);
        }

        // A department coordinator: the departments the company named that are
        // also theirs, or all of theirs when the company named none.
        Set<UUID> allowed = targetDepartments.isEmpty()
                ? new LinkedHashSet<>(scope.departmentIds())
                : targetDepartments.stream()
                        .filter(scope.departmentIds()::contains)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        if (allowed.isEmpty()) {
            return Criteria.nobody(institutionId);
        }
        boolean narrowedToBatches = scope.hasBatchRestriction();
        return new Criteria(institutionId,
                // Never "all departments" for a department coordinator.
                false,
                allowed,
                graduationYear == null,
                graduationYear,
                !narrowedToBatches,
                narrowedToBatches ? scope.batchIds() : UNUSED,
                false);
    }

    /** Every student in scope. Empty when the caller may see nobody. */
    public List<UUID> studentIds(CompanyRequirement requirement, UUID institutionId,
                                 AccessScope scope) {
        Criteria criteria = criteriaFor(requirement, institutionId, scope);
        if (criteria.empty()) {
            return List.of();
        }
        return users.findStudentsForDiscovery(criteria.institutionId(), criteria.allDepartments(),
                criteria.departmentIds(), criteria.anyBatch(), criteria.graduationYear(),
                criteria.anyGrantedBatch(), criteria.grantedBatchIds());
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
                criteria.anyGrantedBatch(), criteria.grantedBatchIds(), studentUserId);
    }
}
