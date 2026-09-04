package com.careerflux.shortlist;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;

import com.careerflux.shortlist.domain.PlacementStage;
import com.careerflux.shortlist.domain.PlacementStage.ActorKind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The placement transition matrix, asserted exhaustively.
 *
 * <p>Written as a whitelist and then checked against every other combination
 * rather than as a list of examples. A state machine tested by example passes
 * while a route nobody thought of stays open, and the routes that matter here
 * are the ones that would let a college mark a student interested on their
 * behalf, or let a student select themselves.
 */
class PlacementStageTest {

    /** Exactly the moves the design permits. Everything absent must be refused. */
    private record Move(PlacementStage from, PlacementStage to, ActorKind actor) {
    }

    private static final Set<Move> ALLOWED = Set.of(
            // Staff
            new Move(PlacementStage.SHORTLISTED, PlacementStage.INVITED, ActorKind.STAFF),
            new Move(PlacementStage.SHORTLISTED, PlacementStage.NOT_PROCEEDING, ActorKind.STAFF),
            new Move(PlacementStage.INVITED, PlacementStage.NOT_PROCEEDING, ActorKind.STAFF),
            new Move(PlacementStage.INTERESTED, PlacementStage.SELECTED, ActorKind.STAFF),
            new Move(PlacementStage.INTERESTED, PlacementStage.NOT_PROCEEDING, ActorKind.STAFF),
            // Student
            new Move(PlacementStage.INVITED, PlacementStage.INTERESTED, ActorKind.STUDENT),
            new Move(PlacementStage.INVITED, PlacementStage.DECLINED, ActorKind.STUDENT));

    @Test
    @DisplayName("every permitted move is permitted, and nothing else is")
    void theMatrixIsExactlyTheWhitelist() {
        for (PlacementStage from : PlacementStage.values()) {
            for (PlacementStage to : PlacementStage.values()) {
                for (ActorKind actor : ActorKind.values()) {
                    boolean expected = ALLOWED.contains(new Move(from, to, actor));
                    assertThat(from.canMoveTo(to, actor))
                            .describedAs("%s: %s -> %s", actor, from, to)
                            .isEqualTo(expected);
                }
            }
        }
    }

    @Nested
    @DisplayName("the moves that must fail")
    class Refused {

        @Test
        @DisplayName("staff cannot mark a student interested on their behalf")
        void staffCannotAnswerForTheStudent() {
            // The one that would quietly hollow out the invite step: if the
            // college can record interest itself, asking becomes a formality.
            assertThat(PlacementStage.INVITED.canMoveTo(PlacementStage.INTERESTED, ActorKind.STAFF))
                    .isFalse();
        }

        @Test
        @DisplayName("a student cannot select, invite or shortlist themselves")
        void studentCannotActAsTheCollege() {
            assertThat(PlacementStage.INTERESTED.canMoveTo(PlacementStage.SELECTED, ActorKind.STUDENT))
                    .isFalse();
            assertThat(PlacementStage.SHORTLISTED.canMoveTo(PlacementStage.INVITED, ActorKind.STUDENT))
                    .isFalse();
            assertThat(PlacementStage.SHORTLISTED.canMoveTo(PlacementStage.INTERESTED, ActorKind.STUDENT))
                    .isFalse();
        }

        @Test
        @DisplayName("nobody skips the middle")
        void noSkipping() {
            for (ActorKind actor : ActorKind.values()) {
                assertThat(PlacementStage.SHORTLISTED.canMoveTo(PlacementStage.SELECTED, actor))
                        .describedAs("%s must not select an uninvited candidate", actor).isFalse();
                assertThat(PlacementStage.SHORTLISTED.canMoveTo(PlacementStage.DECLINED, actor))
                        .describedAs("%s must not decline an invitation nobody sent", actor).isFalse();
                assertThat(PlacementStage.INVITED.canMoveTo(PlacementStage.SELECTED, actor))
                        .describedAs("%s must not select before the student answers", actor).isFalse();
            }
        }

        @Test
        @DisplayName("nothing goes backwards")
        void noReversing() {
            for (ActorKind actor : ActorKind.values()) {
                assertThat(PlacementStage.INTERESTED.canMoveTo(PlacementStage.INVITED, actor)).isFalse();
                assertThat(PlacementStage.INVITED.canMoveTo(PlacementStage.SHORTLISTED, actor)).isFalse();
                assertThat(PlacementStage.INTERESTED.canMoveTo(PlacementStage.SHORTLISTED, actor)).isFalse();
            }
        }

        @Test
        @DisplayName("a terminal stage is the end of the road")
        void terminalStagesAreClosed() {
            for (PlacementStage terminal : EnumSet.of(PlacementStage.SELECTED,
                    PlacementStage.NOT_PROCEEDING, PlacementStage.DECLINED)) {
                assertThat(terminal.isTerminal()).isTrue();
                for (PlacementStage to : PlacementStage.values()) {
                    for (ActorKind actor : ActorKind.values()) {
                        assertThat(terminal.canMoveTo(to, actor))
                                .describedAs("%s must not move out of %s", actor, terminal)
                                .isFalse();
                    }
                }
            }
        }

        @Test
        @DisplayName("moving somewhere you already are is refused, not ignored")
        void sameStageIsRefused() {
            // Clicking twice should say the second one did nothing, rather than
            // leave somebody believing they made a decision they did not.
            for (PlacementStage stage : PlacementStage.values()) {
                for (ActorKind actor : ActorKind.values()) {
                    assertThat(stage.canMoveTo(stage, actor)).isFalse();
                }
            }
        }

        @Test
        @DisplayName("a null target or actor is refused rather than thrown at")
        void nullsAreRefused() {
            assertThat(PlacementStage.SHORTLISTED.canMoveTo(null, ActorKind.STAFF)).isFalse();
            assertThat(PlacementStage.SHORTLISTED.canMoveTo(PlacementStage.INVITED, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("what the stages mean")
    class Meaning {

        @Test
        @DisplayName("only the three end states are terminal")
        void terminality() {
            assertThat(PlacementStage.SHORTLISTED.isTerminal()).isFalse();
            assertThat(PlacementStage.INVITED.isTerminal()).isFalse();
            assertThat(PlacementStage.INTERESTED.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("only an invitation is waiting on the student")
        void awaiting() {
            for (PlacementStage stage : PlacementStage.values()) {
                assertThat(stage.awaitsStudentResponse())
                        .describedAs("%s", stage)
                        .isEqualTo(stage == PlacementStage.INVITED);
            }
        }

        @Test
        @DisplayName("every stage has a label written for a person")
        void labels() {
            for (PlacementStage stage : PlacementStage.values()) {
                assertThat(stage.label())
                        .describedAs("%s", stage)
                        .isNotBlank()
                        .doesNotContain("_");
            }
            assertThat(PlacementStage.NOT_PROCEEDING.label()).isEqualTo("Not proceeding");
        }

        @Test
        @DisplayName("the college's decision and the student's are different stages")
        void refusalsAreDistinguished() {
            // Collapsing these would lose the only fact anybody asks about
            // afterwards: did we turn them down, or did they turn us down?
            assertThat(PlacementStage.NOT_PROCEEDING).isNotEqualTo(PlacementStage.DECLINED);
            assertThat(PlacementStage.INVITED.canMoveTo(PlacementStage.DECLINED, ActorKind.STAFF))
                    .describedAs("the college cannot decline on the student's behalf").isFalse();
            assertThat(PlacementStage.INVITED.canMoveTo(PlacementStage.NOT_PROCEEDING, ActorKind.STUDENT))
                    .describedAs("a student cannot record the college's decision").isFalse();
        }
    }
}
