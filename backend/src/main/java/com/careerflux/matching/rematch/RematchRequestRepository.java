package com.careerflux.matching.rematch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RematchRequestRepository extends JpaRepository<RematchRequest, UUID> {

    Optional<RematchRequest> findByCandidateId(UUID candidateId);

    @Query("select r from RematchRequest r where r.status = :status order by r.requestedAt asc")
    List<RematchRequest> findByStatus(@Param("status") RematchStatus status, Pageable pageable);

    long countByStatus(RematchStatus status);

    /**
     * Requests left RUNNING by a worker that died.
     *
     * <p>Without this a crash mid-run would strand a candidate in "recalculating"
     * for ever, which is exactly the state the queue exists to avoid.
     */
    @Query("select r from RematchRequest r where r.status = com.careerflux.matching.rematch.RematchStatus.RUNNING "
            + "and r.startedAt < :before")
    List<RematchRequest> findStalledSince(@Param("before") java.time.Instant before);
}
