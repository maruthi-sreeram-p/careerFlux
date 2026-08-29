package com.careerflux.institution.repository;

import java.util.List;
import java.util.UUID;

import com.careerflux.institution.domain.StaffScope;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffScopeRepository extends JpaRepository<StaffScope, UUID> {

    /** Loaded on every staff request, so the associations are fetched eagerly here. */
    @EntityGraph(attributePaths = {"department", "batch"})
    List<StaffScope> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
