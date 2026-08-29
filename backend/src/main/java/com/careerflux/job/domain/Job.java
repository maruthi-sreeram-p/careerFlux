package com.careerflux.job.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.careerflux.common.BaseEntity;
import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.source.domain.Company;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The canonical job.
 *
 * <p>One row per real-world opening, no matter how many sources list it. The
 * sightings themselves live in {@link JobObservation}, which is what lets
 * CareerFlux say "this posting appears on the company career page and on two
 * boards" instead of throwing duplicates away and losing that fact.
 */
@Entity
@Table(name = "jobs")
public class Job extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    /**
     * The deduplication key: a stable fingerprint of company plus normalized title
     * plus location. Two observations that produce the same key are the same job.
     */
    @Column(name = "canonical_key", nullable = false, unique = true, length = 255)
    private String canonicalKey;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "normalized_title", length = 200)
    private String normalizedTitle;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "responsibilities", columnDefinition = "text")
    private String responsibilities;

    @Column(name = "requirements", columnDefinition = "text")
    private String requirements;

    @Column(name = "location_raw", length = 300)
    private String locationRaw;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "region", length = 120)
    private String region;

    @Column(name = "country", length = 120)
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_mode", nullable = false, length = 24)
    private WorkMode workMode = WorkMode.UNSPECIFIED;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", nullable = false, length = 32)
    private EmploymentType employmentType = EmploymentType.UNSPECIFIED;

    @Enumerated(EnumType.STRING)
    @Column(name = "seniority", nullable = false, length = 32)
    private Seniority seniority = Seniority.UNSPECIFIED;

    @Column(name = "min_experience_years", precision = 4, scale = 1)
    private BigDecimal minExperienceYears;

    @Column(name = "max_experience_years", precision = 4, scale = 1)
    private BigDecimal maxExperienceYears;

    @Column(name = "salary_min", precision = 12, scale = 2)
    private BigDecimal salaryMin;

    @Column(name = "salary_max", precision = 12, scale = 2)
    private BigDecimal salaryMax;

    @Column(name = "salary_currency", length = 8)
    private String salaryCurrency;

    @Column(name = "salary_period", length = 16)
    private String salaryPeriod;

    @Column(name = "apply_url", length = 1000)
    private String applyUrl;

    /**
     * Normalized {@link #applyUrl} used as a deduplication signal: lowercased,
     * query string and trailing slash removed, so tracking parameters added by
     * different boards do not make one application form look like two.
     */
    @Column(name = "apply_url_key", length = 500)
    private String applyUrlKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private JobStatus status = JobStatus.OPEN;

    /** When the employer says it was posted. Null when no source stated it. */
    @Column(name = "posted_at")
    private Instant postedAt;

    /** When CareerFlux first saw it, which is a fact we can always state. */
    @Column(name = "first_observed_at", nullable = false)
    private Instant firstObservedAt = Instant.now();

    @Column(name = "last_observed_at", nullable = false)
    private Instant lastObservedAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "enrichment_status", nullable = false, length = 24)
    private EnrichmentStatus enrichmentStatus = EnrichmentStatus.PENDING;

    @Column(name = "enrichment_engine", length = 64)
    private String enrichmentEngine;

    /** Hash of the content fields, used to detect that a posting actually changed. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "search_text", columnDefinition = "text")
    private String searchText;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<JobSkill> skills = new ArrayList<>();

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<JobObservation> observations = new ArrayList<>();

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public String getCanonicalKey() {
        return canonicalKey;
    }

    public void setCanonicalKey(String canonicalKey) {
        this.canonicalKey = canonicalKey;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getNormalizedTitle() {
        return normalizedTitle;
    }

    public void setNormalizedTitle(String normalizedTitle) {
        this.normalizedTitle = normalizedTitle;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getResponsibilities() {
        return responsibilities;
    }

    public void setResponsibilities(String responsibilities) {
        this.responsibilities = responsibilities;
    }

    public String getRequirements() {
        return requirements;
    }

    public void setRequirements(String requirements) {
        this.requirements = requirements;
    }

    public String getLocationRaw() {
        return locationRaw;
    }

    public void setLocationRaw(String locationRaw) {
        this.locationRaw = locationRaw;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public WorkMode getWorkMode() {
        return workMode;
    }

    public void setWorkMode(WorkMode workMode) {
        this.workMode = workMode;
    }

    public EmploymentType getEmploymentType() {
        return employmentType;
    }

    public void setEmploymentType(EmploymentType employmentType) {
        this.employmentType = employmentType;
    }

    public Seniority getSeniority() {
        return seniority;
    }

    public void setSeniority(Seniority seniority) {
        this.seniority = seniority;
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

    public BigDecimal getSalaryMin() {
        return salaryMin;
    }

    public void setSalaryMin(BigDecimal salaryMin) {
        this.salaryMin = salaryMin;
    }

    public BigDecimal getSalaryMax() {
        return salaryMax;
    }

    public void setSalaryMax(BigDecimal salaryMax) {
        this.salaryMax = salaryMax;
    }

    public String getSalaryCurrency() {
        return salaryCurrency;
    }

    public void setSalaryCurrency(String salaryCurrency) {
        this.salaryCurrency = salaryCurrency;
    }

    public String getSalaryPeriod() {
        return salaryPeriod;
    }

    public void setSalaryPeriod(String salaryPeriod) {
        this.salaryPeriod = salaryPeriod;
    }

    public String getApplyUrl() {
        return applyUrl;
    }

    public void setApplyUrl(String applyUrl) {
        this.applyUrl = applyUrl;
    }

    public String getApplyUrlKey() {
        return applyUrlKey;
    }

    public void setApplyUrlKey(String applyUrlKey) {
        this.applyUrlKey = applyUrlKey;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(Instant postedAt) {
        this.postedAt = postedAt;
    }

    public Instant getFirstObservedAt() {
        return firstObservedAt;
    }

    public void setFirstObservedAt(Instant firstObservedAt) {
        this.firstObservedAt = firstObservedAt;
    }

    public Instant getLastObservedAt() {
        return lastObservedAt;
    }

    public void setLastObservedAt(Instant lastObservedAt) {
        this.lastObservedAt = lastObservedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public EnrichmentStatus getEnrichmentStatus() {
        return enrichmentStatus;
    }

    public void setEnrichmentStatus(EnrichmentStatus enrichmentStatus) {
        this.enrichmentStatus = enrichmentStatus;
    }

    public String getEnrichmentEngine() {
        return enrichmentEngine;
    }

    public void setEnrichmentEngine(String enrichmentEngine) {
        this.enrichmentEngine = enrichmentEngine;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getSearchText() {
        return searchText;
    }

    public void setSearchText(String searchText) {
        this.searchText = searchText;
    }

    public long getRowVersion() {
        return rowVersion;
    }

    public List<JobSkill> getSkills() {
        return skills;
    }

    public List<JobObservation> getObservations() {
        return observations;
    }
}
