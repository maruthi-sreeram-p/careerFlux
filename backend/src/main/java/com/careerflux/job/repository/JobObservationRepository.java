package com.careerflux.job.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.job.domain.JobObservation;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JobObservationRepository extends JpaRepository<JobObservation, UUID> {

    Optional<JobObservation> findBySourceIdAndExternalJobId(UUID sourceId, String externalJobId);

    @EntityGraph(attributePaths = {"source", "source.company"})
    List<JobObservation> findByJobIdOrderByFirstObservedAtAsc(UUID jobId);

    List<JobObservation> findBySourceIdAndActiveTrue(UUID sourceId);

    @Query("select o from JobObservation o where o.source.id = :sourceId and o.active = true "
            + "and o.externalJobId not in :seenIds")
    List<JobObservation> findDisappeared(UUID sourceId, Collection<String> seenIds);

    long countByJobIdAndActiveTrue(UUID jobId);

    long countBySourceId(UUID sourceId);
}
