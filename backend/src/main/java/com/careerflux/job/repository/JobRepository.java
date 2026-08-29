package com.careerflux.job.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface JobRepository extends JpaRepository<Job, UUID>, JpaSpecificationExecutor<Job> {

    Optional<Job> findByCanonicalKey(String canonicalKey);

    @EntityGraph(attributePaths = {"company"})
    Optional<Job> findWithCompanyById(UUID id);

    @Query("select distinct j from Job j left join fetch j.skills s left join fetch s.skill "
            + "left join fetch j.company where j.id = :id")
    Optional<Job> findFullById(UUID id);

    long countByStatus(JobStatus status);

    long countByFirstObservedAtAfter(Instant since);

    /**
     * Deduplication candidates: same company, similar normalized title. The final
     * decision uses several more signals, but this is the cheap indexed prefilter.
     */
    @Query("select j from Job j where j.company.id = :companyId and j.normalizedTitle = :normalizedTitle")
    List<Job> findDuplicateCandidates(UUID companyId, String normalizedTitle);

    /**
     * Company-wide, not title-scoped: two listings pointing at the same
     * application form are the same opening even when their titles differ.
     */
    @Query("select j from Job j where j.company.id = :companyId and j.applyUrlKey = :applyUrlKey")
    List<Job> findByCompanyAndApplyUrlKey(UUID companyId, String applyUrlKey);

    @Query("select distinct o.job from JobObservation o where o.job.company.id = :companyId "
            + "and lower(o.requisitionId) = lower(:requisitionId)")
    List<Job> findByCompanyAndRequisitionId(UUID companyId, String requisitionId);

    @Query("select j from Job j where j.status = com.careerflux.job.domain.JobStatus.OPEN "
            + "and j.lastObservedAt < :threshold")
    List<Job> findStale(Instant threshold, Pageable pageable);

    @EntityGraph(attributePaths = {"company"})
    Page<Job> findByStatusInOrderByLastObservedAtDesc(Collection<JobStatus> statuses, Pageable pageable);

    @Query("select j from Job j where j.enrichmentStatus = com.careerflux.job.domain.EnrichmentStatus.PENDING")
    List<Job> findPendingEnrichment(Pageable pageable);
}
