package com.careerflux.job.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.careerflux.job.domain.JobChange;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JobChangeRepository extends JpaRepository<JobChange, UUID> {

    List<JobChange> findByJobIdOrderByDetectedAtDesc(UUID jobId, Pageable pageable);

    List<JobChange> findByDetectedAtAfterOrderByDetectedAtDesc(Instant since, Pageable pageable);

    long countByDetectedAtAfter(Instant since);

    /**
     * Counts only changes that mean the posting itself moved.
     *
     * <p>CREATED and NEW_SOURCE_OBSERVED are recorded as changes because they are
     * part of a job's history, but neither means the employer edited anything. A
     * job that has only ever been seen once must not be badged "Updated".
     */
    @Query("select count(c) from JobChange c where c.job.id in :jobIds and c.detectedAt > :since "
            + "and c.changeType not in ("
            + "com.careerflux.job.domain.JobChangeType.CREATED, "
            + "com.careerflux.job.domain.JobChangeType.NEW_SOURCE_OBSERVED)")
    long countRealChanges(Collection<UUID> jobIds, Instant since);

    @Query("select c.job.id, count(c) from JobChange c where c.job.id in :jobIds "
            + "and c.detectedAt > :since and c.changeType not in ("
            + "com.careerflux.job.domain.JobChangeType.CREATED, "
            + "com.careerflux.job.domain.JobChangeType.NEW_SOURCE_OBSERVED) "
            + "group by c.job.id")
    List<Object[]> countRealChangesByJob(Collection<UUID> jobIds, Instant since);
}
