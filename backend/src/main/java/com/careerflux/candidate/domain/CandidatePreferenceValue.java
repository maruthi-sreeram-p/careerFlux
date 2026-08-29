package com.careerflux.candidate.domain;

import java.util.UUID;

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
 * One row per multi-valued preference, discriminated by {@link PreferenceType}.
 * Kept separate from the profile aggregate so preferences can be replaced
 * wholesale on save without touching the rest of the candidate record.
 */
@Entity
@Table(name = "candidate_preference_values")
public class CandidatePreferenceValue {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private CandidateProfile candidate;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 32)
    private PreferenceType valueType;

    @Column(name = "preference_value", nullable = false, length = 200)
    private String value;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    public static CandidatePreferenceValue of(CandidateProfile candidate, PreferenceType type,
                                              String value, int order) {
        CandidatePreferenceValue entity = new CandidatePreferenceValue();
        entity.candidate = candidate;
        entity.valueType = type;
        entity.value = value;
        entity.displayOrder = order;
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public CandidateProfile getCandidate() {
        return candidate;
    }

    public void setCandidate(CandidateProfile candidate) {
        this.candidate = candidate;
    }

    public PreferenceType getValueType() {
        return valueType;
    }

    public void setValueType(PreferenceType valueType) {
        this.valueType = valueType;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }
}
