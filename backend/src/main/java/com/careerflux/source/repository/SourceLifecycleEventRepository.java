package com.careerflux.source.repository;

import java.util.List;
import java.util.UUID;

import com.careerflux.source.domain.SourceLifecycleEvent;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceLifecycleEventRepository extends JpaRepository<SourceLifecycleEvent, UUID> {

    List<SourceLifecycleEvent> findBySourceIdOrderByOccurredAtAsc(UUID sourceId);
}
