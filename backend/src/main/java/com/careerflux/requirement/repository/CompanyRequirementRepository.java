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
 *
 * <p><b>One collection per fetch.</b> The graphs below pull skills and leave
 * departments to load on their own. Fetching both in one query is a join across
 * two collections, and SQL answers that with a cartesian product: six skills
 * and two departments came back as twelve skill rows, so a requirement listed
 * every skill twice — in the officer's screen and in what the scorer was handed.
 * Departments are a {@code Set} and silently absorbed their half of the
 * duplication, which is why only the skills looked wrong.
 *
 * <p>Departments then load lazily, in batches of 32 (see
 * {@code default_batch_fetch_size}), and every caller reads them inside a
 * transactional service method, so this costs one extra query per page rather
 * than one per row.
 */
public interface CompanyRequirementRepository extends JpaRepository<CompanyRequirement, UUID> {

    @EntityGraph(attributePaths = {"skills", "skills.skill"})
    Optional<CompanyRequirement> findByIdAndInstitutionId(UUID id, UUID institutionId);

    @EntityGraph(attributePaths = {"skills", "skills.skill"})
    Page<CompanyRequirement> findByInstitutionIdOrderByCreatedAtDesc(UUID institutionId,
                                                                    Pageable pageable);

    @EntityGraph(attributePaths = {"skills", "skills.skill"})
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
