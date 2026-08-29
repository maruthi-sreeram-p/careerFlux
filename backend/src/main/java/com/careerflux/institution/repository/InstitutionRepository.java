package com.careerflux.institution.repository;

import java.util.Optional;
import java.util.UUID;

import com.careerflux.institution.domain.Institution;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InstitutionRepository extends JpaRepository<Institution, UUID> {

    Optional<Institution> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
