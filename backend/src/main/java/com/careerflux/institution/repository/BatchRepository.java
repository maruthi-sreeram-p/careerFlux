package com.careerflux.institution.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.institution.domain.Batch;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchRepository extends JpaRepository<Batch, UUID> {

    List<Batch> findByInstitutionIdOrderByGraduationYearDescNameAsc(UUID institutionId);

    Optional<Batch> findByInstitutionIdAndName(UUID institutionId, String name);

    /** Tenant-scoped lookup. Prefer this over findById so a stray id cannot cross tenants. */
    Optional<Batch> findByIdAndInstitutionId(UUID id, UUID institutionId);

    long countByInstitutionId(UUID institutionId);
}
