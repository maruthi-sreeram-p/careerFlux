package com.careerflux.config;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single place that decides whether scheduled work may run.
 *
 * <p>Before this existed the answer was spelled out again in each worker that
 * bothered to ask, and four of the nine did not ask at all — two of which delete
 * rows. An operator who set {@code scheduler-enabled: false} to freeze the system
 * got a system that still pruned its own history overnight. A control that does
 * not cover what its name implies is worse than no control, because it is
 * trusted.
 *
 * <p><b>Two switches, because there are two questions.</b> They are deliberately
 * not one flag:
 *
 * <ul>
 *   <li>{@code careerflux.background-work-enabled} — may any scheduled work write
 *       anything? This is the master switch, and the honest answer to "freeze
 *       everything". Turning it off stops all nine workers.
 *   <li>{@code careerflux.ingestion.scheduler-enabled} — may scheduled ingestion
 *       and the outbound source traffic around it run? This is the narrower one,
 *       used when the corpus must hold still but the product should otherwise
 *       behave normally.
 * </ul>
 *
 * <p>The distinction earns its keep on the rematch worker. A student editing
 * their profile expects their matches to catch up; that is product behaviour, not
 * scheduled ingestion, and it should survive a corpus freeze. It does — which is
 * what {@code application.yml} already claimed, before anything enforced it.
 *
 * <p><b>Defaults preserve today's behaviour exactly.</b> Background work defaults
 * to enabled, so an existing deployment that sets only {@code scheduler-enabled}
 * keeps the semantics it has now. The new switch is opt-in for the operator who
 * wants everything still.
 */
@Component
public class BackgroundWorkGate {

    private static final Logger log = LoggerFactory.getLogger(BackgroundWorkGate.class);

    private final CareerFluxProperties properties;

    public BackgroundWorkGate(CareerFluxProperties properties) {
        this.properties = properties;
    }

    /**
     * Whether scheduled ingestion and the source traffic around it may run.
     *
     * <p>Requires both switches. Scheduled ingestion is background work, so the
     * master switch governs it too; a reader should not have to remember that
     * one flag silently overrides the other.
     */
    public boolean permitsScheduledIngestion() {
        return permitsBackgroundWork() && properties.ingestion().schedulerEnabled();
    }

    /**
     * Whether any scheduled background write may happen at all.
     *
     * <p>Used by the workers that are not ingestion: queue draining, stalled-run
     * recovery, and the two retention sweeps that delete rows.
     */
    public boolean permitsBackgroundWork() {
        return properties.backgroundWorkEnabled();
    }

    /** One line an operator can read in the startup log to know what is running. */
    @PostConstruct
    void announce() {
        if (!permitsBackgroundWork()) {
            log.warn("Background work is DISABLED: no scheduled worker will run and nothing "
                    + "scheduled will write to the database.");
            return;
        }
        if (!permitsScheduledIngestion()) {
            log.warn("Scheduled ingestion is DISABLED: sources are not synced, job expiry and the "
                    + "nightly rematch do not run, and no outbound source traffic is scheduled. "
                    + "Other background work (rematch queue, retention sweeps) still runs.");
            return;
        }
        log.info("Background work and scheduled ingestion are both enabled.");
    }
}
