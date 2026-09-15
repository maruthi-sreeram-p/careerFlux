package com.careerflux.candidate.repository;

import java.util.List;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CandidateProfileRepository extends JpaRepository<CandidateProfile, UUID> {

    @EntityGraph(attributePaths = {"user", "preferences"})
    Optional<CandidateProfile> findByUserId(UUID userId);

    @Query("select p from CandidateProfile p left join fetch p.skills s left join fetch s.skill where p.id = :id")
    Optional<CandidateProfile> findWithSkillsById(UUID id);

    @Query("select p from CandidateProfile p where p.onboardingStage = com.careerflux.candidate.domain.OnboardingStage.COMPLETE")
    List<CandidateProfile> findAllOnboarded();

    /** How many of these students have a candidate profile row at all. */
    long countByUserIdIn(Collection<UUID> userIds);

    /** How many have finished onboarding, which is what "has a career profile" means. */
    @Query("""
            select count(p) from CandidateProfile p
            where p.user.id in :userIds
              and p.onboardingStage = com.careerflux.candidate.domain.OnboardingStage.COMPLETE
            """)
    long countOnboarded(@Param("userIds") Collection<UUID> userIds);

    /** Mean profile completeness, or null when the cohort is empty. */
    @Query("select avg(p.profileCompleteness) from CandidateProfile p where p.user.id in :userIds")
    Double averageCompleteness(@Param("userIds") Collection<UUID> userIds);

    /** Students who have recorded at least one skill. */
    @Query("""
            select count(distinct p.id) from CandidateProfile p join p.skills s
            where p.user.id in :userIds
            """)
    long countWithAnySkill(@Param("userIds") Collection<UUID> userIds);

    /** The cohort's candidate ids, for aggregates that hang off the profile. */
    @Query("select p.id from CandidateProfile p where p.user.id in :userIds")
    List<UUID> findIdsByUserIdIn(@Param("userIds") Collection<UUID> userIds);

    /** The most common skills in the cohort, strongest first. */
    @Query("""
            select sk.canonicalName, count(distinct p.id)
            from CandidateProfile p join p.skills cs join cs.skill sk
            where p.user.id in :userIds
            group by sk.canonicalName
            order by count(distinct p.id) desc, sk.canonicalName asc
            """)
    List<Object[]> topSkills(@Param("userIds") Collection<UUID> userIds, Pageable pageable);

    /**
     * Profiles for a set of student accounts, with everything scoring reads.
     *
     * <p>{@code preferences} is fetched deliberately. It is a lazy one-to-one on
     * the inverse side, which Hibernate cannot proxy — it has to query to learn
     * whether the row exists at all — so touching it while building a snapshot
     * costs one statement per student. Against two thousand students that was
     * two thousand round trips hiding behind a single method call.
     */
    @Query("""
            select p from CandidateProfile p join fetch p.user u left join fetch u.department
            left join fetch u.batch left join fetch p.preferences
            where p.user.id in :userIds
            """)
    List<CandidateProfile> findForDiscovery(@Param("userIds") Collection<UUID> userIds);

    /**
     * The cohort's profiles, selected by scope rather than by a list of ids.
     *
     * <p>One fixed query shape however large the college is. The id-list form
     * above is still right for a page of twenty-five, but handing it two
     * thousand parameters made the database rebuild a plan per distinct length:
     * 83 seconds for the first 2,000-id call against the college fixture, and
     * 70 milliseconds once that exact shape had been seen before.
     *
     * <p>{@code allDepartments} is only ever true for an institution-wide caller,
     * and {@code anyGrantedBatch} is false exactly when a department coordinator
     * is narrowed to batches (see {@code DiscoveryScope}).
     */
    @Query("""
            select p from CandidateProfile p
            join fetch p.user u
            left join fetch u.department d
            left join fetch u.batch b
            left join fetch p.preferences
            where u.institution.id = :institutionId
              and u.role = com.careerflux.user.UserRole.STUDENT
              and (:allDepartments = true or d.id in :departmentIds)
              and (:anyBatch = true or b.graduationYear = :graduationYear)
              and (:anyGrantedBatch = true or b.id in :grantedBatchIds)
            """)
    List<CandidateProfile> findForDiscoveryScoped(@Param("institutionId") UUID institutionId,
                                                  @Param("allDepartments") boolean allDepartments,
                                                  @Param("departmentIds") Collection<UUID> departmentIds,
                                                  @Param("anyBatch") boolean anyBatch,
                                                  @Param("graduationYear") Integer graduationYear,
                                                  @Param("anyGrantedBatch") boolean anyGrantedBatch,
                                                  @Param("grantedBatchIds") Collection<UUID> grantedBatchIds);
}
