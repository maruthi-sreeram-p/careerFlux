package com.careerflux.requirement.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.requirement.domain.CompanyRequirement;
import com.careerflux.requirement.domain.RequirementStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Requirements, always read within one institution.
 *
 * <p>There is no plain {@code findById} in use anywhere above this interface.
 * Every lookup takes the institution as well, so a tampered id from another
 * college misses rather than loads — the caller then reports not-found without
 * ever having held the row.
 */
public interface CompanyRequirementRepository extends JpaRepository<CompanyRequirement, UUID> {

    @EntityGraph(attributePaths = {"skills", "skills.skill", "departments"})
    Optional<CompanyRequirement> findByIdAndInstitutionId(UUID id, UUID institutionId);

    @EntityGraph(attributePaths = {"skills", "skills.skill", "departments"})
    Page<CompanyRequirement> findByInstitutionIdOrderByCreatedAtDesc(UUID institutionId,
                                                                    Pageable pageable);

    @EntityGraph(attributePaths = {"skills", "skills.skill", "departments"})
    Page<CompanyRequirement> findByInstitutionIdAndStatusOrderByCreatedAtDesc(
            UUID institutionId, RequirementStatus status, Pageable pageable);

    long countByInstitutionIdAndStatus(UUID institutionId, RequirementStatus status);

    /**
     * Requirements a coordinator may see: those targeting one of their
     * departments, plus those targeting none, which mean the whole college.
     */
    @Query("""
            select distinct r from CompanyRequirement r
            left join r.departments d
            where r.institution.id = :institutionId
              and (d.id in :departmentIds or r.departments is empty)
            order by r.createdAt desc
            """)
    List<CompanyRequirement> findVisibleToDepartments(@Param("institutionId") UUID institutionId,
                                                      @Param("departmentIds") List<UUID> departmentIds);
}
