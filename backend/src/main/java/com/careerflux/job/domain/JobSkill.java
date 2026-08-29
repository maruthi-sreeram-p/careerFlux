package com.careerflux.job.domain;

import java.util.UUID;

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

/** A skill a job asks for, resolved to the same canonical dictionary candidates use. */
@Entity
@Table(name = "job_skills")
public class JobSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement", nullable = false, length = 16)
    private SkillRequirement requirement = SkillRequirement.REQUIRED;

    @Enumerated(EnumType.STRING)
    @Column(name = "extracted_by", nullable = false, length = 24)
    private SkillExtractionMethod extractedBy = SkillExtractionMethod.DICTIONARY;

    public UUID getId() {
        return id;
    }

    public Job getJob() {
        return job;
    }

    public void setJob(Job job) {
        this.job = job;
    }

    public Skill getSkill() {
        return skill;
    }

    public void setSkill(Skill skill) {
        this.skill = skill;
    }

    public SkillRequirement getRequirement() {
        return requirement;
    }

    public void setRequirement(SkillRequirement requirement) {
        this.requirement = requirement;
    }

    public SkillExtractionMethod getExtractedBy() {
        return extractedBy;
    }

    public void setExtractedBy(SkillExtractionMethod extractedBy) {
        this.extractedBy = extractedBy;
    }
}
