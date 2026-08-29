package com.careerflux.source.repository;

import java.util.Optional;
import java.util.UUID;

import com.careerflux.source.domain.SourceAccessPolicy;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceAccessPolicyRepository extends JpaRepository<SourceAccessPolicy, UUID> {

    Optional<SourceAccessPolicy> findBySourceId(UUID sourceId);
}
