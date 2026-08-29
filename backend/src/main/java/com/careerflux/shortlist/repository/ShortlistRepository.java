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
