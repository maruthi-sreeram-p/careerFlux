package com.careerflux.requirement.domain;

import java.util.UUID;

import com.careerflux.job.domain.SkillRequirement;
import com.careerflux.skill.Skill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A skill a company asked for, at a stated tier.
 *
 * <p>Mirrors {@code JobSkill} deliberately, down to reusing
 * {@link SkillRequirement}. The tiers mean exactly what the rules-2 classifier
 * makes them mean on the job side — required is a condition, preferred is a
 * wish, optional is a mention — so a candidate scored against a requirement is
 * scored on the same terms as one scored against a posting.
 *
 * <p>What is absent is {@code extractedBy}. A job's skills are inferred from
 * prose and the extraction method is worth recording; a requirement's skills
 * are typed in by a placement officer who was told them, so there is no
 * inference to attribute.
 */
@Entity
@Table(name = "company_requirement_skills")
public class CompanyRequirementSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requirement_id", nullable = false)
    private CompanyRequirement requirement;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement", nullable = false, length = 24)
    private SkillRequirement tier = SkillRequirement.REQUIRED;

    public UUID getId() {
        return id;
    }

    public CompanyRequirement getRequirement() {
        return requirement;
    }

    public void setRequirement(CompanyRequirement requirement) {
        this.requirement = requirement;
    }

    public Skill getSkill() {
        return skill;
    }

    public void setSkill(Skill skill) {
        this.skill = skill;
    }

    public SkillRequirement getTier() {
        return tier;
    }

    public void setTier(SkillRequirement tier) {
        this.tier = tier;
    }
}
