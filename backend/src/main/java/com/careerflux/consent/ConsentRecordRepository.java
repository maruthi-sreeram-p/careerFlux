package com.careerflux.consent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The consent ledger. Rows are only ever added: nothing in the application
 * updates or deletes one, and the entity is immutable to Hibernate.
 */
public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

    /** The newest event for one purpose, which is that purpose's current state. */
    @EntityGraph(attributePaths = "noticeVersion")
    Optional<ConsentRecord> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(UUID userId, ConsentPurpose purpose);

    @EntityGraph(attributePaths = "noticeVersion")
    List<ConsentRecord> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
