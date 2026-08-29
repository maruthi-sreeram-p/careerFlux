package com.careerflux.skill;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillAliasRepository extends JpaRepository<SkillAlias, UUID> {

    Optional<SkillAlias> findByAlias(String alias);
}
