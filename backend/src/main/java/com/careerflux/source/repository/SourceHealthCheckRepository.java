package com.careerflux.source.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.careerflux.source.domain.SourceHealthCheck;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SourceHealthCheckRepository extends JpaRepository<SourceHealthCheck, UUID> {

    List<SourceHealthCheck> findBySourceIdOrderByCheckedAtDesc(UUID sourceId, Pageable pageable);

    /** Health history is a rolling window; older rows have no operational value. */
    @Modifying
    @Query("delete from SourceHealthCheck c where c.checkedAt < :before")
    int deleteOlderThan(Instant before);
}
