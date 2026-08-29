package com.careerflux.institution.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.institution.domain.Department;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    List<Department> findByInstitutionIdOrderByNameAsc(UUID institutionId);

    Optional<Department> findByInstitutionIdAndCode(UUID institutionId, String code);

    /** Tenant-scoped lookup. Prefer this over findById so a stray id cannot cross tenants. */
    Optional<Department> findByIdAndInstitutionId(UUID id, UUID institutionId);

    long countByInstitutionId(UUID institutionId);
}
