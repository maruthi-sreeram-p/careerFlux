package com.careerflux.audit;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findAllByOrderByOccurredAtDesc(Pageable pageable);

    Page<AuditEvent> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, String entityId,
                                                                     Pageable pageable);
}
