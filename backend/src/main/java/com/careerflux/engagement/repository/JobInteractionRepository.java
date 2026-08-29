package com.careerflux.engagement.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.engagement.domain.InteractionType;
import com.careerflux.engagement.domain.JobInteraction;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobInteractionRepository extends JpaRepository<JobInteraction, UUID> {

    Optional<JobInteraction> findByCandidateIdAndJobIdAndInteractionType(UUID candidateId, UUID jobId,
                                                                        InteractionType type);

    @EntityGraph(attributePaths = {"job", "job.company"})
    List<JobInteraction> findByCandidateIdAndInteractionTypeOrderByCreatedAtDesc(UUID candidateId,
                                                                                InteractionType type);

    List<JobInteraction> findByCandidateIdAndJobIdIn(UUID candidateId, Collection<UUID> jobIds);

    @Query("select i.job.id from JobInteraction i where i.candidate.id = :candidateId "
            + "and i.interactionType = :type")
    List<UUID> findJobIdsByType(UUID candidateId, InteractionType type);

    long countByCandidateIdAndInteractionType(UUID candidateId, InteractionType type);

    void deleteByCandidateIdAndJobIdAndInteractionType(UUID candidateId, UUID jobId, InteractionType type);

    /** Interactions of one kind across a cohort. */
    long countByCandidateIdInAndInteractionType(Collection<UUID> candidateIds, InteractionType type);

    /** How many distinct students in the cohort have interacted this way at least once. */
    @Query("""
            select count(distinct i.candidate.id) from JobInteraction i
            where i.candidate.id in :candidateIds and i.interactionType = :type
            """)
    long countCandidatesWithInteraction(@Param("candidateIds") Collection<UUID> candidateIds,
                                        @Param("type") InteractionType type);
}
