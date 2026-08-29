package com.careerflux.ingestion.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.careerflux.ingestion.domain.IngestionRun;
import com.careerflux.ingestion.domain.IngestionStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionRunRepository extends JpaRepository<IngestionRun, UUID> {

    Page<IngestionRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    List<IngestionRun> findBySourceIdOrderByStartedAtDesc(UUID sourceId, Pageable pageable);

    Page<IngestionRun> findByStatusOrderByStartedAtDesc(IngestionStatus status, Pageable pageable);

    long countByStatusAndStartedAtAfter(IngestionStatus status, Instant since);

    long countByStartedAtAfter(Instant since);
}
