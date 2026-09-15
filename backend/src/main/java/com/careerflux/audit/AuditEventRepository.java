package com.careerflux.audit;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /** The platform's own trail: every row that belongs to no college. */
    Page<AuditEvent> findByInstitutionIdIsNullOrderByOccurredAtDesc(Pageable pageable);

    /** One college's trail. The id must come from the caller's session, never the request. */
    Page<AuditEvent> findByInstitutionIdOrderByOccurredAtDesc(UUID institutionId, Pageable pageable);

    Page<AuditEvent> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, String entityId,
                                                                     Pageable pageable);
}
