package com.careerflux.ingestion.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.careerflux.ingestion.domain.PipelineEvent;
import com.careerflux.ingestion.domain.PipelineEventStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PipelineEventRepository extends JpaRepository<PipelineEvent, UUID> {

    boolean existsByTopicAndEventKeyAndStatus(String topic, String eventKey, PipelineEventStatus status);

    List<PipelineEvent> findByStatusOrderByCreatedAtAsc(PipelineEventStatus status, Pageable pageable);

    Page<PipelineEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<PipelineEvent> findByStatusOrderByCreatedAtDesc(PipelineEventStatus status, Pageable pageable);

    long countByStatus(PipelineEventStatus status);

    long countByTopicAndCreatedAtAfter(String topic, Instant since);

    /** Processed events are a debugging aid, not a permanent record. */
    @Modifying
    @Query("delete from PipelineEvent e where e.status = com.careerflux.ingestion.domain.PipelineEventStatus.PROCESSED "
            + "and e.processedAt < :before")
    int deleteProcessedBefore(Instant before);
}
