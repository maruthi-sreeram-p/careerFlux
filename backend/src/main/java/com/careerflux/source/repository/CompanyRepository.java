package com.careerflux.source.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.source.domain.Company;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, UUID> {

    Optional<Company> findBySlug(String slug);

    List<Company> findByNameContainingIgnoreCaseOrderByNameAsc(String fragment);
}
