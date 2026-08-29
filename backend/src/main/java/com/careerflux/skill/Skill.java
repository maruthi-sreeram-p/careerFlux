package com.careerflux.skill;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.careerflux.common.taxonomy.SkillCategory;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * A canonical skill. Every skill string coming out of a resume or a job
 * description is resolved to one of these before it is stored, which is what
 * makes "SpringBoot" on a CV comparable with "Spring Boot" in a posting.
 */
@Entity
@Table(name = "skills")
public class Skill {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "canonical_name", nullable = false, length = 120)
    private String canonicalName;

    @Column(name = "slug", nullable = false, unique = true, length = 120)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 48)
    private SkillCategory category = SkillCategory.OTHER;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "skill", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<SkillAlias> aliases = new ArrayList<>();

    public UUID getId() {
        return id;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public void setCanonicalName(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public SkillCategory getCategory() {
        return category;
    }

    public void setCategory(SkillCategory category) {
        this.category = category;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<SkillAlias> getAliases() {
        return aliases;
    }

    public void addAlias(String alias) {
        SkillAlias entity = new SkillAlias();
        entity.setSkill(this);
        entity.setAlias(alias);
        aliases.add(entity);
    }
}
