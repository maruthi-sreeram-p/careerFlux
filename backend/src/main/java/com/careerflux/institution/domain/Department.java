package com.careerflux.institution.domain;

import com.careerflux.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A department within an institution. The coarsest unit a coordinator is scoped to. */
@Entity
@Table(name = "departments")
public class Department extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "institution_id", nullable = false, updatable = false)
    private Institution institution;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    /** Short code, unique within the institution: CSE, ECE, MECH. */
    @Column(name = "code", nullable = false, length = 32)
    private String code;

    public Institution getInstitution() {
        return institution;
    }

    public void setInstitution(Institution institution) {
        this.institution = institution;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
