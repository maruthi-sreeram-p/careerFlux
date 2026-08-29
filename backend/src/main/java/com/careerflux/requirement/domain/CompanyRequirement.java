package com.careerflux.requirement.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.Institution;
import com.careerflux.user.User;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * What a company told the placement office they are hiring for.
 *
 * <p>This is the institution's side of CareerFlux. A student is matched against
 * the job corpus; a requirement is matched against the college's students. The
 * two directions share their comparable attributes on purpose — a title, an
 * experience band, a work mode, and tiered skills — so the existing scorer can
 * read either one rather than growing a second implementation.
 *
 * <p>It is not a {@code Job} and must not become one. Jobs are observed market
 * postings with provenance and deduplication; this is authored by a person, is
 * owned by one college, and never appears in the corpus students browse.
 *
 * <p>{@code minCgpa} is stored but nothing compares against it yet. Student
 * CGPA is not modelled, and the rule that technical fit and formal eligibility
 * stay separate concepts is what the later eligibility work has to honour. A
 * requirement recording the company's stated rule from the day it is written
 * costs nothing; inventing an eligibility verdict from data that does not exist
 * would cost correctness.
 */
@Entity
@Table(name = "company_requirements")
public class CompanyRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** The tenancy discriminator. Every read is filtered on this first. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "institution_id", nullable = false)
    private Institution institution;

    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;

    @Column(name = "role_title", nullable = false, length = 200)
    private String roleTitle;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "min_experience_years", precision = 4, scale = 1)
    private BigDecimal minExperienceYears;

    @Column(name = "max_experience_years", precision = 4, scale = 1)
    private BigDecimal maxExperienceYears;

    /** Null means any batch, which is commoner than naming a year. */
    @Column(name = "graduation_year")
    private Integer graduationYear;

    /** The company's stated academic rule. Recorded, not yet evaluated. */
    @Column(name = "min_cgpa", precision = 4, scale = 2)
    private BigDecimal minCgpa;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_mode", nullable = false, length = 24)
    private WorkMode workMode = WorkMode.UNSPECIFIED;

    @Column(name = "location_raw", length = 300)
    private String locationRaw;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private RequirementStatus status = RequirementStatus.DRAFT;

    @Column(name = "drive_date")
    private LocalDate driveDate;

    /**
     * Who wrote it. Nullable because a requirement outlives the account that
     * created it: removing a staff member must not delete the college's record
     * of who they were hiring for.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "requirement", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<CompanyRequirementSkill> skills = new ArrayList<>();

    /** Empty means the whole college rather than nobody. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "company_requirement_departments",
            joinColumns = @JoinColumn(name = "requirement_id"),
            inverseJoinColumns = @JoinColumn(name = "department_id"))
    private Set<Department> departments = new LinkedHashSet<>();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * Replaces the skill set.
     *
     * <p>Clearing and re-adding in one transaction makes Hibernate order the
     * inserts ahead of the orphan deletes, and the new rows then collide with
     * the old ones on {@code uq_requirement_skills}. Callers must flush the
     * removal before adding, which is why this only clears.
     */
    public void clearSkills() {
        this.skills.clear();
    }

    public void addSkill(CompanyRequirementSkill skill) {
        skill.setRequirement(this);
        this.skills.add(skill);
    }

    public UUID getId() {
        return id;
    }

    public Institution getInstitution() {
        return institution;
    }

    public void setInstitution(Institution institution) {
        this.institution = institution;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getRoleTitle() {
        return roleTitle;
    }

    public void setRoleTitle(String roleTitle) {
        this.roleTitle = roleTitle;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getMinExperienceYears() {
        return minExperienceYears;
    }

    public void setMinExperienceYears(BigDecimal minExperienceYears) {
        this.minExperienceYears = minExperienceYears;
    }

    public BigDecimal getMaxExperienceYears() {
        return maxExperienceYears;
    }

    public void setMaxExperienceYears(BigDecimal maxExperienceYears) {
        this.maxExperienceYears = maxExperienceYears;
    }

    public Integer getGraduationYear() {
        return graduationYear;
    }

    public void setGraduationYear(Integer graduationYear) {
        this.graduationYear = graduationYear;
    }

    public BigDecimal getMinCgpa() {
        return minCgpa;
    }

    public void setMinCgpa(BigDecimal minCgpa) {
        this.minCgpa = minCgpa;
    }

    public WorkMode getWorkMode() {
        return workMode;
    }

    public void setWorkMode(WorkMode workMode) {
        this.workMode = workMode;
    }

    public String getLocationRaw() {
        return locationRaw;
    }

    public void setLocationRaw(String locationRaw) {
        this.locationRaw = locationRaw;
    }

    public RequirementStatus getStatus() {
        return status;
    }

    public void setStatus(RequirementStatus status) {
        this.status = status;
    }

    public LocalDate getDriveDate() {
        return driveDate;
    }

    public void setDriveDate(LocalDate driveDate) {
        this.driveDate = driveDate;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<CompanyRequirementSkill> getSkills() {
        return skills;
    }

    public Set<Department> getDepartments() {
        return departments;
    }

    public void setDepartments(Set<Department> departments) {
        this.departments = departments;
    }
}
