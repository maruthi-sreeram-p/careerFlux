package com.careerflux.privacy;

import java.time.Instant;

import com.careerflux.audit.AuditService;
import com.careerflux.config.BackgroundWorkGate;
import com.careerflux.privacy.ResumeRetentionService.ResumeSweepReport;
import com.careerflux.privacy.erasure.AccountErasureService;
import com.careerflux.privacy.erasure.AccountErasureService.ErasureRunReport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The nightly privacy retention run: resume retention, then due erasures.
 *
 * <p>It deletes rows and files, so it answers to the master switch
 * ({@code careerflux.background-work-enabled}) like every other sweep that does,
 * and to {@code careerflux.retention.dry-run}, which defaults to true. Each run
 * leaves one audit row in the platform trail holding counts and nothing else.
 *
 * <p>Scheduled at 02:20, clear of the existing 03:30, 03:45, 04:15 and 05:00 jobs.
 */
@Component
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final BackgroundWorkGate gate;
    private final RetentionProperties properties;
    private final ResumeRetentionService resumeRetention;
    private final AccountErasureService erasures;
    private final AuditService audit;

    public RetentionScheduler(BackgroundWorkGate gate, RetentionProperties properties,
                              ResumeRetentionService resumeRetention, AccountErasureService erasures,
                              AuditService audit) {
        this.gate = gate;
        this.properties = properties;
        this.resumeRetention = resumeRetention;
        this.erasures = erasures;
        this.audit = audit;
    }

    @Scheduled(cron = "${careerflux.retention.cron:0 20 2 * * *}")
    public void runNightly() {
        if (!gate.permitsBackgroundWork()) {
            return;
        }
        runOnce(Instant.now(), properties.dryRun());
    }

    /** One run, with the clock and mode given explicitly so it can be exercised directly. */
    public void runOnce(Instant now, boolean dryRun) {
        ResumeSweepReport resumes = resumeRetention.sweep(now, dryRun);
        ErasureRunReport erasureRun = erasures.processDue(now, dryRun, properties.batchSize());

        String counts = "dryRun=" + dryRun
                + " textDropped=" + resumes.textDropped()
                + " versionsRemoved=" + resumes.versionsRemoved()
                + " orphanFilesRemoved=" + resumes.orphanFilesRemoved()
                + " quarantineEntriesRemoved=" + resumes.quarantineEntriesRemoved()
                + " candidatesHeld=" + resumes.candidatesHeld()
                + " resumeFailures=" + resumes.failures()
                + " erasuresDue=" + erasureRun.due()
                + " erasuresCompleted=" + erasureRun.completed()
                + " erasuresFailed=" + erasureRun.failed();
        audit.record("RETENTION_SWEEP", "Retention", null, counts);
        log.info("Retention run: {}", counts);
    }
}
