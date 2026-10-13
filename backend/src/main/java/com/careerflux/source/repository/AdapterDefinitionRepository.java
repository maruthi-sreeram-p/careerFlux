package com.careerflux.source.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.source.domain.AdapterDefinition;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AdapterDefinitionRepository extends JpaRepository<AdapterDefinition, UUID> {

    Optional<AdapterDefinition> findByAdapterKey(String adapterKey);

    List<AdapterDefinition> findAllByOrderByDisplayNameAsc();

    List<AdapterDefinition> findByEnabledTrueOrderByDisplayNameAsc();
}
