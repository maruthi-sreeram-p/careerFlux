package com.careerflux.source.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The source state machine is the rule that keeps CareerFlux from reading things
 * it should not. These tests pin the transitions that matter, so a future edit to
 * the switch cannot quietly open a path into ACTIVE.
 */
class SourceStateTest {

    @Test
    @DisplayName("the happy path walks discovered to active one step at a time")
    void happyPath() {
        assertThat(SourceState.DISCOVERED.canTransitionTo(SourceState.CLASSIFIED)).isTrue();
        assertThat(SourceState.CLASSIFIED.canTransitionTo(SourceState.POLICY_REVIEW)).isTrue();
        assertThat(SourceState.POLICY_REVIEW.canTransitionTo(SourceState.APPROVED)).isTrue();
        assertThat(SourceState.APPROVED.canTransitionTo(SourceState.ACTIVE)).isTrue();
    }

    @Test
    @DisplayName("a source cannot skip straight from discovery to active")
    void noShortcutToActive() {
        assertThat(SourceState.DISCOVERED.canTransitionTo(SourceState.ACTIVE)).isFalse();
        assertThat(SourceState.CLASSIFIED.canTransitionTo(SourceState.ACTIVE)).isFalse();
        assertThat(SourceState.POLICY_REVIEW.canTransitionTo(SourceState.ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("a blocked source can be re-reviewed but never reactivated directly")
    void blockedNeedsReReview() {
        assertThat(SourceState.BLOCKED.canTransitionTo(SourceState.ACTIVE)).isFalse();
        assertThat(SourceState.BLOCKED.canTransitionTo(SourceState.APPROVED)).isFalse();
        assertThat(SourceState.BLOCKED.canTransitionTo(SourceState.POLICY_REVIEW)).isTrue();
    }

    @Test
    @DisplayName("retired is terminal")
    void retiredIsTerminal() {
        assertThat(SourceState.RETIRED.allowedTransitions()).isEmpty();
        assertThat(SourceState.RETIRED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("a degraded source recovers to active without another policy review")
    void degradedRecovers() {
        assertThat(SourceState.ACTIVE.canTransitionTo(SourceState.DEGRADED)).isTrue();
        assertThat(SourceState.DEGRADED.canTransitionTo(SourceState.ACTIVE)).isTrue();
    }

    @Test
    @DisplayName("every state can be blocked, because a policy can change at any time")
    void anythingCanBeBlocked() {
        for (SourceState state : SourceState.values()) {
            if (state == SourceState.BLOCKED || state == SourceState.RETIRED) {
                continue;
            }
            assertThat(state.canTransitionTo(SourceState.BLOCKED))
                    .as("%s should be blockable", state)
                    .isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(SourceState.class)
    @DisplayName("only active and degraded sources may be contacted")
    void onlyServiceableStatesPermitFetching(SourceState state) {
        boolean expected = state == SourceState.ACTIVE || state == SourceState.DEGRADED;
        assertThat(state.permitsFetching()).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(SourceState.class)
    @DisplayName("no state lists itself as a transition target")
    void noSelfTransitions(SourceState state) {
        assertThat(state.allowedTransitions()).doesNotContain(state);
    }

    @Test
    @DisplayName("the states needing a human are the ones the console highlights")
    void attentionStates() {
        assertThat(SourceState.POLICY_REVIEW.needsAttention()).isTrue();
        assertThat(SourceState.PENDING_REVIEW.needsAttention()).isTrue();
        assertThat(SourceState.DEGRADED.needsAttention()).isTrue();
        assertThat(SourceState.ACTIVE.needsAttention()).isFalse();
    }
}
