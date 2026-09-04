package com.careerflux.shortlist.domain;

import java.time.Instant;
import java.util.UUID;

import com.careerflux.user.User;

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
 * One move, written down.
 *
 * <p>Append-only. Rows are never edited or deleted, because the value of this
 * table is that it says what happened rather than what is currently believed.
 * The current stage on the shortlist row answers "where is this candidate now";
 * only this answers "who invited them, when, and what did the student say".
 *
 * <p>Written in the same transaction as the stage it describes. That is the
 * whole reason it is not {@code audit_events}: the audit service is
 * {@code REQUIRES_NEW} by design, so it commits on its own and would leave a
 * line about a transition that later rolled back. Both records are kept — the
 * audit trail for "what has anybody been doing", this for "what happened to
 * this candidate" — and only this one is transactional.
 */
@Entity
@Table(name = "placement_stage_changes")
public class PlacementStageChange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shortlist_id", nullable = false)
    private ShortlistEntry shortlist;

    /** Null only on the row recording arrival at SHORTLISTED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_stage", length = 32)
    private PlacementStage fromStage;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_stage", nullable = false, length = 32)
    private PlacementStage toStage;

    /**
     * Who did it. Null once the account is gone; the label below survives.
     *
     * <p>A student's own responses are recorded against the student.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User actor;

    /** Kept separately so the trail still reads after an account is deleted. */
    @Column(name = "actor_label", length = 160)
    private String actorLabel;

    /**
     * Whether the college moved this or the student did.
     *
     * <p>Recorded rather than inferred from the stage. NOT_PROCEEDING and
     * DECLINED already carry that distinction, but INTERESTED does not, and a
     * reader should not have to reconstruct who acted from which state was
     * reached.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_kind", nullable = false, length = 16)
    private PlacementStage.ActorKind actorKind;

    /** Optional. Why, in the words of whoever decided. */
    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public static PlacementStageChange of(ShortlistEntry shortlist, PlacementStage from,
                                          PlacementStage to, User actor,
                                          PlacementStage.ActorKind actorKind, String note) {
        PlacementStageChange change = new PlacementStageChange();
        change.shortlist = shortlist;
        change.fromStage = from;
        change.toStage = to;
        change.actor = actor;
        change.actorLabel = actor == null ? null : actor.getFullName();
        change.actorKind = actorKind;
        change.note = note;
        change.occurredAt = Instant.now();
        return change;
    }

    public UUID getId() {
        return id;
    }

    public ShortlistEntry getShortlist() {
        return shortlist;
    }

    public PlacementStage getFromStage() {
        return fromStage;
    }

    public PlacementStage getToStage() {
        return toStage;
    }

    public User getActor() {
        return actor;
    }

    public String getActorLabel() {
        return actorLabel;
    }

    public PlacementStage.ActorKind getActorKind() {
        return actorKind;
    }

    public String getNote() {
        return note;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
