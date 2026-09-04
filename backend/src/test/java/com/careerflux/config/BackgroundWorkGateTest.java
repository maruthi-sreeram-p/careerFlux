package com.careerflux.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The gate itself, in isolation.
 *
 * <p>Four combinations, all four asserted, because the interesting one is not
 * obvious: turning off background work must also stop scheduled ingestion, even
 * though ingestion has its own switch that is still set to true. If the master
 * switch did not win, "freeze everything" would quietly mean "freeze everything
 * except the part that talks to the internet".
 */
class BackgroundWorkGateTest {

    private static BackgroundWorkGate gate(boolean backgroundWork, boolean scheduler) {
        return new BackgroundWorkGate(properties(Map.of(
                "careerflux.background-work-enabled", String.valueOf(backgroundWork),
                "careerflux.ingestion.scheduler-enabled", String.valueOf(scheduler))));
    }

    /**
     * Binds real properties rather than calling the record constructor.
     *
     * <p>Hand-constructing {@code CareerFluxProperties} would mean listing every
     * matching weight and re-listing them whenever one is added, and it would
     * skip the {@code @DefaultValue} handling that the default-preservation test
     * below is actually about.
     */
    private static CareerFluxProperties properties(Map<String, String> overrides) {
        Map<String, Object> values = new java.util.HashMap<>(overrides);
        values.put("careerflux.security.jwt.secret", "a-test-secret-that-is-long-enough-0123456789");
        // bindOrCreate so the sections this test does not set take their own
        // declared defaults, which is the point: the defaults are under test.
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("careerflux", CareerFluxProperties.class);
    }

    @Nested
    @DisplayName("the four combinations")
    class Combinations {

        @Test
        @DisplayName("both on: everything may run")
        void bothOn() {
            var gate = gate(true, true);
            assertThat(gate.permitsBackgroundWork()).isTrue();
            assertThat(gate.permitsScheduledIngestion()).isTrue();
        }

        @Test
        @DisplayName("ingestion off: other background work continues")
        void ingestionOffOnly() {
            // The current production posture. The rematch queue and the retention
            // sweeps keep running; nothing contacts a source.
            var gate = gate(true, false);
            assertThat(gate.permitsBackgroundWork()).isTrue();
            assertThat(gate.permitsScheduledIngestion()).isFalse();
        }

        @Test
        @DisplayName("background work off: scheduled ingestion is off too, whatever its own flag says")
        void masterSwitchWins() {
            // The case worth being explicit about. scheduler-enabled is true here
            // and must not be enough on its own.
            var gate = gate(false, true);
            assertThat(gate.permitsBackgroundWork()).isFalse();
            assertThat(gate.permitsScheduledIngestion())
                    .describedAs("the master switch must override the narrower one")
                    .isFalse();
        }

        @Test
        @DisplayName("both off: nothing runs")
        void bothOff() {
            var gate = gate(false, false);
            assertThat(gate.permitsBackgroundWork()).isFalse();
            assertThat(gate.permitsScheduledIngestion()).isFalse();
        }
    }

    @Test
    @DisplayName("scheduled ingestion is never permitted without background work")
    void ingestionImpliesBackgroundWork() {
        for (boolean background : new boolean[] {true, false}) {
            for (boolean scheduler : new boolean[] {true, false}) {
                var gate = gate(background, scheduler);
                if (gate.permitsScheduledIngestion()) {
                    assertThat(gate.permitsBackgroundWork())
                            .describedAs("background=%s scheduler=%s", background, scheduler)
                            .isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("background work defaults to on, so existing deployments are unchanged")
    void defaultPreservesExistingBehaviour() {
        // Binding with nothing set must leave the system behaving as it did
        // before the switch existed, or upgrading silently freezes a production
        // deployment's queue processing and retention sweeps.
        CareerFluxProperties bound = properties(Map.of());
        assertThat(bound.backgroundWorkEnabled()).isTrue();
        assertThat(new BackgroundWorkGate(bound).permitsBackgroundWork()).isTrue();
    }

    @Test
    @DisplayName("announce() never throws, whatever the configuration")
    void announceIsSafe() {
        for (boolean background : new boolean[] {true, false}) {
            for (boolean scheduler : new boolean[] {true, false}) {
                gate(background, scheduler).announce();
            }
        }
    }
}
