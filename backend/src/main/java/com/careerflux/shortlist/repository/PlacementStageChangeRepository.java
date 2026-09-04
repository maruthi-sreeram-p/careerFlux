package com.careerflux.shortlist.repository;

import java.util.List;
import java.util.UUID;

import com.careerflux.shortlist.domain.PlacementStageChange;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The placement history. Append-only: rows are written and read, never updated
 * or deleted, because its value is that it says what happened rather than what
 * is currently believed.
 */
public interface PlacementStageChangeRepository extends JpaRepository<PlacementStageChange, UUID> {

    /** One candidate's progress on one drive, oldest first, which is how it reads. */
    List<PlacementStageChange> findByShortlistIdOrderByOccurredAtAsc(UUID shortlistId);

    long countByShortlistId(UUID shortlistId);
}
