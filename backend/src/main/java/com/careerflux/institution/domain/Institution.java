package com.careerflux.institution.domain;

import com.careerflux.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A college. The tenant boundary for everything candidate-owned.
 *
 * <p>This is also where institution-wide policy lives — currently the AI budget
 * — so that configuring a college means editing one row rather than hunting
 * through several settings tables.
 */
@Entity
@Table(name = "institutions")
public class Institution extends BaseEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 200)
    private String slug;

    @Column(name = "short_name", length = 60)
    private String shortName;

    @Column(name = "website", length = 300)
    private String website;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "country", length = 120)
    private String country;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private InstitutionStatus status = InstitutionStatus.PROVISIONING;

    /**
     * Comma-separated email domains this college owns, e.g.
     * {@code "osmania.ac.in,students.osmania.ac.in"}. A student registering from
     * one of these is recognised without needing a code.
     */
    @Column(name = "email_domains", length = 500)
    private String emailDomains;

    /** Code the placement office hands out, for students whose email is not institutional. */
    @Column(name = "registration_code", length = 40)
    private String registrationCode;

    /** Null means no ceiling has been set, not that the ceiling is zero. */

    @Column(name = "ai_daily_request_budget")
    private Integer aiDailyRequestBudget;

    /** Per-student daily AI request allowance. Always set; defaults to 25. */
    @Column(name = "student_ai_daily_quota", nullable = false)
    private int studentAiDailyQuota = 25;

    public boolean isActive() {
        return status == InstitutionStatus.ACTIVE;
    }

    /** Whether this college recognises the domain of the given address as its own. */
    public boolean acceptsEmail(String email) {
        if (emailDomains == null || emailDomains.isBlank() || email == null) {
            return false;
        }
        int at = email.lastIndexOf('@');
        if (at < 0) {
            return false;
        }
        String domain = email.substring(at + 1).toLowerCase(java.util.Locale.ROOT).strip();
        for (String candidate : emailDomains.split(",")) {
            String allowed = candidate.toLowerCase(java.util.Locale.ROOT).strip();
            // A subdomain of an allowed domain counts; a domain merely ending in
            // the same letters does not. "evilosmania.ac.in" must not match.
            if (!allowed.isEmpty() && (domain.equals(allowed) || domain.endsWith("." + allowed))) {
                return true;
            }
        }
        return false;
    }

    public String getEmailDomains() {
        return emailDomains;
    }

    public void setEmailDomains(String emailDomains) {
        this.emailDomains = emailDomains;
    }

    public String getRegistrationCode() {
        return registrationCode;
    }

    public void setRegistrationCode(String registrationCode) {
        this.registrationCode = registrationCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public InstitutionStatus getStatus() {
        return status;
    }

    public void setStatus(InstitutionStatus status) {
        this.status = status;
    }



    public Integer getAiDailyRequestBudget() {
        return aiDailyRequestBudget;
    }

    public void setAiDailyRequestBudget(Integer aiDailyRequestBudget) {
        this.aiDailyRequestBudget = aiDailyRequestBudget;
    }

    public int getStudentAiDailyQuota() {
        return studentAiDailyQuota;
    }

    public void setStudentAiDailyQuota(int studentAiDailyQuota) {
        this.studentAiDailyQuota = studentAiDailyQuota;
    }
}
