package com.careerflux.source.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.SourceState;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface JobSourceRepository extends JpaRepository<JobSource, UUID>, JpaSpecificationExecutor<JobSource> {

    Optional<JobSource> findByBaseUrl(String baseUrl);

    boolean existsByBaseUrl(String baseUrl);

    @EntityGraph(attributePaths = {"company", "accessPolicy"})
    Optional<JobSource> findWithDetailById(UUID id);

    @EntityGraph(attributePaths = {"company", "accessPolicy"})
    Page<JobSource> findByStateIn(Collection<SourceState> states, Pageable pageable);

    @EntityGraph(attributePaths = {"company", "accessPolicy"})
    List<JobSource> findByState(SourceState state);

    long countByState(SourceState state);

    @Query("select s from JobSource s where s.state in :states "
            + "and (s.lastSyncAttemptAt is null or s.lastSyncAttemptAt < :before) "
            + "order by s.lastSyncAttemptAt asc nulls first")
    List<JobSource> findDueForSync(Collection<SourceState> states, Instant before, Pageable pageable);

    @Query("select s from JobSource s where s.state in :states "
            + "and (s.lastHealthCheckAt is null or s.lastHealthCheckAt < :before)")
    List<JobSource> findDueForHealthCheck(Collection<SourceState> states, Instant before, Pageable pageable);

    @Query("select count(s) from JobSource s where s.state = com.careerflux.source.domain.SourceState.ACTIVE")
    long countActive();

    List<JobSource> findByCompanyId(UUID companyId);
}
