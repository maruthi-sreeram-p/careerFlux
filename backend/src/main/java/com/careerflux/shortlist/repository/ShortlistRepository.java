package com.careerflux.shortlist.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.shortlist.domain.ShortlistEntry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShortlistRepository extends JpaRepository<ShortlistEntry, UUID> {

    Optional<ShortlistEntry> findByRequirementIdAndCandidateId(UUID requirementId, UUID candidateId);

    long countByRequirementId(UUID requirementId);

    /**
     * The candidate ids on one shortlist.
     *
     * <p>Ids rather than rows: the discovery page needs to mark hundreds of
     * candidates as shortlisted or not, and one set lookup answers all of them
     * without a query per row.
     */
    @Query("select e.candidate.id from ShortlistEntry e where e.requirement.id = :requirementId")
    List<UUID> findCandidateIds(@Param("requirementId") UUID requirementId);

    /**
     * Candidate id and current stage for one requirement, in a single read.
     *
     * <p>The discovery and shortlist screens both need to know who is on the
     * list and how far each has got. Fetching the entries and walking them would
     * load every association behind them for two scalars.
     */
    @Query("select e.candidate.id, e.stage from ShortlistEntry e where e.requirement.id = :requirementId")
    List<Object[]> findCandidateStages(@Param("requirementId") UUID requirementId);

    /** One student's placement records across every drive at their college. */
    @Query("""
            select e from ShortlistEntry e
            join fetch e.requirement r
            where e.candidate.id = :candidateId
            order by e.createdAt desc
            """)
    List<ShortlistEntry> findForCandidate(@Param("candidateId") UUID candidateId);

    @Query("""
            select e from ShortlistEntry e
            join fetch e.candidate c join fetch c.user u
            left join fetch u.department left join fetch u.batch
            where e.requirement.id = :requirementId
            order by e.createdAt asc
            """)
    List<ShortlistEntry> findForRequirement(@Param("requirementId") UUID requirementId);

    boolean existsByRequirementIdAndCandidateIdIn(UUID requirementId, Collection<UUID> candidateIds);
}
