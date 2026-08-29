package com.careerflux.matching.domain;

import java.math.BigDecimal;
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
 * One line of a match explanation: a single reason the score is what it is.
 *
 * <p>These are what the candidate actually reads — "Spring Boot", "0-2 years
 * experience", "AWS production experience" — so the label is written to stand on
 * its own without the surrounding UI having to add words.
 */
@Entity
@Table(name = "match_components")
public class MatchComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_id", nullable = false)
    private JobMatch match;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private ComponentKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "dimension", nullable = false, length = 32)
    private MatchDimension dimension;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    /** Optional second line, used when the label alone would be cryptic. */
    @Column(name = "detail", length = 600)
    private String detail;

    /** How much this component contributed, for the detailed breakdown view. */
    @Column(name = "weight", precision = 5, scale = 2)
    private BigDecimal weight;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    public static MatchComponent strength(MatchDimension dimension, String label, String detail, int order) {
        return of(ComponentKind.STRENGTH, dimension, label, detail, order);
    }

    public static MatchComponent gap(MatchDimension dimension, String label, String detail, int order) {
        return of(ComponentKind.GAP, dimension, label, detail, order);
    }

    /** A stated condition the candidate does not meet. */
    public static MatchComponent blocker(MatchDimension dimension, String label, String detail, int order) {
        return of(ComponentKind.BLOCKER, dimension, label, detail, order);
    }

    /** A dimension that could not be compared, and why. */
    public static MatchComponent unknown(MatchDimension dimension, String label, String detail, int order) {
        return of(ComponentKind.UNKNOWN, dimension, label, detail, order);
    }

    public static MatchComponent neutral(MatchDimension dimension, String label, String detail, int order) {
        return of(ComponentKind.NEUTRAL, dimension, label, detail, order);
    }

    private static MatchComponent of(ComponentKind kind, MatchDimension dimension, String label,
                                     String detail, int order) {
        MatchComponent component = new MatchComponent();
        component.kind = kind;
        component.dimension = dimension;
        component.label = label;
        component.detail = detail;
        component.displayOrder = order;
        return component;
    }

    public UUID getId() {
        return id;
    }

    public JobMatch getMatch() {
        return match;
    }

    public void setMatch(JobMatch match) {
        this.match = match;
    }

    public ComponentKind getKind() {
        return kind;
    }

    public void setKind(ComponentKind kind) {
        this.kind = kind;
    }

    public MatchDimension getDimension() {
        return dimension;
    }

    public void setDimension(MatchDimension dimension) {
        this.dimension = dimension;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }
}
