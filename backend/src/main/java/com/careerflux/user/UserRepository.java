package com.careerflux.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<User> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    Optional<User> findByPasswordResetToken(String token);

    long countByRole(UserRole role);

    /**
     * Every student in one college, for a caller who sees the whole institution.
     *
     * <p>The institution filter is not optional and not derived from anything the
     * caller sent. It comes from their own token, which is what makes tenant
     * isolation a property of the query rather than of the code that calls it.
     */
    @Query("""
            select u from User u
            left join fetch u.department
            left join fetch u.batch
            where u.institution.id = :institutionId
              and u.role = com.careerflux.user.UserRole.STUDENT
            """)
    Page<User> findStudentsInInstitution(@Param("institutionId") UUID institutionId, Pageable pageable);

    /**
     * The ids of every student a caller may see, institution-wide.
     *
     * <p>Ids rather than rows because the overview only counts. Deriving the
     * cohort here and passing it to the aggregate queries keeps the scope
     * decision in one place: nothing downstream can widen it, and no id ever
     * comes from the client.
     */
    @Query("""
            select u.id from User u
            where u.institution.id = :institutionId
              and u.role = com.careerflux.user.UserRole.STUDENT
            """)
    List<UUID> findStudentIdsInInstitution(@Param("institutionId") UUID institutionId);

    /**
     * The same cohort for a department coordinator: their departments, narrowed
     * to their granted batches when they hold any.
     *
     * <p>A batch grant only narrows. It never adds a student from another
     * department, so a student outside every granted department is excluded
     * whatever batch they are in. A student with no department matches nothing
     * here; they stay visible only to institution-wide staff, who can assign
     * them.
     *
     * @param anyBatch true when the coordinator holds no batch grant, so no batch
     *                 narrowing applies
     */
    @Query("""
            select u.id from User u
            left join u.batch b
            where u.institution.id = :institutionId
              and u.role = com.careerflux.user.UserRole.STUDENT
              and u.department.id in :departmentIds
              and (:anyBatch = true or b.id in :batchIds)
            """)
    List<UUID> findStudentIdsInScope(@Param("institutionId") UUID institutionId,
                                     @Param("departmentIds") Collection<UUID> departmentIds,
                                     @Param("anyBatch") boolean anyBatch,
                                     @Param("batchIds") Collection<UUID> batchIds);

    long countByIdInAndStatus(Collection<UUID> ids, UserStatus status);

    /** Head-count per department for the given cohort. Unassigned students are excluded. */
    @Query("""
            select d.name, count(u) from User u join u.department d
            where u.id in :ids group by d.name order by count(u) desc, d.name asc
            """)
    List<Object[]> countByDepartment(@Param("ids") Collection<UUID> ids);

    /** Head-count per batch for the given cohort. */
    @Query("""
            select b.name, count(u) from User u join u.batch b
            where u.id in :ids group by b.name order by count(u) desc, b.name asc
            """)
    List<Object[]> countByBatch(@Param("ids") Collection<UUID> ids);

    /** Staff of a college, for the institution-configuration view. */
    @Query("""
            select count(u) from User u
            where u.institution.id = :institutionId
              and u.role <> com.careerflux.user.UserRole.STUDENT
            """)
    long countStaffInInstitution(@Param("institutionId") UUID institutionId);

    /**
     * The same staff, listed rather than counted.
     *
     * <p>Ordered by role then name so the list reads the way a college thinks
     * about it — administrators, officers, coordinators — rather than by
     * whatever order rows happen to come back in.
     */
    @Query("""
            select u from User u
            left join fetch u.department
            where u.institution.id = :institutionId
              and u.role <> com.careerflux.user.UserRole.STUDENT
            order by u.role, u.fullName
            """)
    List<User> findStaffInInstitution(@Param("institutionId") UUID institutionId);

    /**
     * The students a requirement may be scored against.
     *
     * <p>Institution, department and batch are all applied here rather than
     * after the fact, so a student outside the scope is never loaded, never
     * scored and never available to be filtered back in by a client.
     *
     * <p>The booleans carry "no restriction" because an empty {@code in} clause
     * is invalid in JPQL and a null collection parameter behaves inconsistently
     * across providers. {@code allDepartments} is only ever true for an
     * institution-wide caller: {@code DiscoveryScope} never sets it for a
     * department coordinator.
     *
     * <p>Erased students are deliberately not excluded. This decides who a
     * caller may see on a shortlist that already exists, and the college keeps
     * those placement records after erasure. Finding new candidates goes through
     * {@code CandidateProfileRepository.findForDiscoveryScoped}, which does
     * exclude them.
     *
     * <p><b>The joins are explicit and left.</b> Writing {@code u.batch.graduationYear}
     * in the where clause produces an implicit <em>inner</em> join, which drops
     * every student who has no batch — even when no batch was asked for, because
     * the join is applied before the condition short-circuits. That silently
     * returned nobody against real data where students are not yet assigned to a
     * batch. A student with no batch is still excluded when a graduation year
     * <em>is</em> named, or when the coordinator is narrowed to batches, which is
     * correct: an unassigned student cannot be shown to satisfy either.
     */
    @Query("""
            select u.id from User u
            left join u.department d
            left join u.batch b
            where u.institution.id = :institutionId
              and u.role = com.careerflux.user.UserRole.STUDENT
              and (:allDepartments = true or d.id in :departmentIds)
              and (:anyBatch = true or b.graduationYear = :graduationYear)
              and (:anyGrantedBatch = true or b.id in :grantedBatchIds)
            """)
    List<UUID> findStudentsForDiscovery(@Param("institutionId") UUID institutionId,
                                        @Param("allDepartments") boolean allDepartments,
                                        @Param("departmentIds") Collection<UUID> departmentIds,
                                        @Param("anyBatch") boolean anyBatch,
                                        @Param("graduationYear") Integer graduationYear,
                                        @Param("anyGrantedBatch") boolean anyGrantedBatch,
                                        @Param("grantedBatchIds") Collection<UUID> grantedBatchIds);

    /**
     * Whether one student falls inside a discovery scope.
     *
     * <p>The single-student form of {@link #findStudentsForDiscovery}. Used when
     * shortlisting, where listing an entire cohort to check one membership would
     * read thousands of rows to answer a yes/no.
     */
    @Query("""
            select count(u) > 0 from User u
            left join u.department d
            left join u.batch b
            where u.id = :userId
              and u.institution.id = :institutionId
              and u.role = com.careerflux.user.UserRole.STUDENT
              and (:allDepartments = true or d.id in :departmentIds)
              and (:anyBatch = true or b.graduationYear = :graduationYear)
              and (:anyGrantedBatch = true or b.id in :grantedBatchIds)
            """)
    boolean isStudentInDiscoveryScope(@Param("institutionId") UUID institutionId,
                                      @Param("allDepartments") boolean allDepartments,
                                      @Param("departmentIds") Collection<UUID> departmentIds,
                                      @Param("anyBatch") boolean anyBatch,
                                      @Param("graduationYear") Integer graduationYear,
                                      @Param("anyGrantedBatch") boolean anyGrantedBatch,
                                      @Param("grantedBatchIds") Collection<UUID> grantedBatchIds,
                                      @Param("userId") UUID userId);
}
