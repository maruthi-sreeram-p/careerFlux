package com.careerflux.matching.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.matching.domain.JobMatch;
import com.careerflux.matching.domain.MatchTier;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JobMatchRepository extends JpaRepository<JobMatch, UUID> {

    Optional<JobMatch> findByCandidateIdAndJobId(UUID candidateId, UUID jobId);

    @EntityGraph(attributePaths = {"job", "job.company"})
    Page<JobMatch> findByCandidateIdAndTierInOrderByOverallScoreDesc(UUID candidateId,
                                                                    Collection<MatchTier> tiers,
                                                                    Pageable pageable);

    @EntityGraph(attributePaths = {"job", "job.company", "components"})
    Optional<JobMatch> findWithComponentsByCandidateIdAndJobId(UUID candidateId, UUID jobId);

    long countByCandidateIdAndTierIn(UUID candidateId, Collection<MatchTier> tiers);

    long countByCandidateIdAndComputedAtAfter(UUID candidateId, Instant since);

    @Query("select m from JobMatch m where m.candidate.id = :candidateId and m.overallScore >= :minScore "
            + "and m.computedAt > :since order by m.overallScore desc")
    List<JobMatch> findRecentAbove(UUID candidateId, int minScore, Instant since, Pageable pageable);

    void deleteByCandidateId(UUID candidateId);

    List<JobMatch> findByJobId(UUID jobId);
}
