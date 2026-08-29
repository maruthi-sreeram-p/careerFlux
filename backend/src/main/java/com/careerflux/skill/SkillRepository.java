package com.careerflux.skill;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillRepository extends JpaRepository<Skill, UUID> {

    Optional<Skill> findBySlug(String slug);

    List<Skill> findBySlugIn(Collection<String> slugs);

    List<Skill> findByCanonicalNameContainingIgnoreCaseOrderByCanonicalNameAsc(String fragment);
}
