package com.careerflux.privacy;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.careerflux.candidate.domain.Resume;
import com.careerflux.candidate.repository.ResumeRepository;
import com.careerflux.candidate.service.ResumeDeletionService;
import com.careerflux.candidate.service.ResumeStorageService;
import com.careerflux.candidate.service.ResumeStorageService.StoredFile;
import com.careerflux.privacy.erasure.AccountErasureService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps resumes to the configured retention policy.
 *
 * <ul>
 *   <li><b>Extracted text</b> older than the maximum age is removed, whether or
 *       not its proposal was ever answered. Text whose proposal was answered is
 *       already gone; this catches the rest.
 *   <li><b>Versions.</b> The active resume is always kept. Of the superseded
 *       ones, the newest few are kept and the rest are removed, file and row
 *       together.
 *   <li><b>Orphans.</b> Files no row refers to, and quarantined copies that
 *       outlived their deletion, are destroyed once they are old enough that no
 *       upload can still be writing them.
 * </ul>
 *
 * <p>Students with an erasure request in flight are left alone: a request that
 * is cancelled must find the account as it was, and one that completes removes
 * everything anyway.
 *
 * <p>Each kind removes at most one batch per run, so a backlog is worked through
 * over several nights rather than in one long lock. In dry-run mode every count
 * is what would have been removed, and nothing is.
 */
@Service
public class ResumeRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ResumeRetentionService.class);

    /** Stands in for an empty exclusion list: an empty {@code in} clause is invalid JPQL. */
    private static final List<UUID> NOBODY = List.of(new UUID(0, 0));

    public record ResumeSweepReport(boolean dryRun, int textDropped, int versionsRemoved, int orphanFilesRemoved,
                                    int quarantineEntriesRemoved, int candidatesHeld, int failures) {
    }

    private final ResumeRepository resumes;
    private final ResumeDeletionService deletion;
    private final ResumeStorageService storage;
    private final AccountErasureService erasures;
    private final RetentionProperties properties;
    private final TransactionTemplate transaction;

    public ResumeRetentionService(ResumeRepository resumes, ResumeDeletionService deletion,
                                  ResumeStorageService storage, AccountErasureService erasures,
                                  RetentionProperties properties, PlatformTransactionManager transactionManager) {
        this.resumes = resumes;
        this.deletion = deletion;
        this.storage = storage;
        this.erasures = erasures;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public ResumeSweepReport sweep(Instant now, boolean dryRun) {
        List<UUID> heldIds = erasures.heldCandidateIds();
        List<UUID> held = heldIds.isEmpty() ? NOBODY : heldIds;
        int[] failures = new int[1];

        int textDropped = dropExpiredText(now, held, dryRun);
        int versionsRemoved = enforceVersionCap(held, dryRun, failures);
        int orphans = removeOrphanFiles(now, new HashSet<>(heldIds), dryRun);
        int quarantine = purgeQuarantine(now, dryRun);

        return new ResumeSweepReport(dryRun, textDropped, versionsRemoved, orphans, quarantine, heldIds.size(),
                failures[0]);
    }

    private int dropExpiredText(Instant now, List<UUID> held, boolean dryRun) {
        Instant cutoff = now.minus(properties.extractedTextMaxAge());
        if (dryRun) {
            return (int) Math.min(resumes.countTextDue(cutoff, held), properties.batchSize());
        }
        List<UUID> due = resumes.findTextDue(cutoff, held, PageRequest.of(0, properties.batchSize()));
        if (due.isEmpty()) {
            return 0;
        }
        Integer dropped = transaction.execute(status -> resumes.dropText(due, now));
        return dropped == null ? 0 : dropped;
    }

    private int enforceVersionCap(List<UUID> held, boolean dryRun, int[] failures) {
        int kept = properties.supersededResumeVersionsKept();
        int limit = properties.batchSize();
        int removed = 0;
        List<UUID> candidates = resumes.findCandidatesOverVersionCap(kept, held, PageRequest.of(0, limit));
        for (UUID candidateId : candidates) {
            List<Resume> newestFirst = transaction.execute(status ->
                    resumes.findByCandidateIdOrderByUploadedAtDesc(candidateId));
            if (newestFirst == null) {
                continue;
            }
            int supersededKept = 0;
            for (Resume resume : newestFirst) {
                // The active resume is the one a student's profile is built on.
                // It is never a candidate for removal, however old it is.
                if (resume.isActive()) {
                    continue;
                }
                if (supersededKept < kept) {
                    supersededKept++;
                    continue;
                }
                if (removed >= limit) {
                    return removed;
                }
                if (dryRun) {
                    removed++;
                    continue;
                }
                try {
                    deletion.removeForRetention(resume.getId());
                    removed++;
                } catch (RuntimeException e) {
                    failures[0]++;
                    log.warn("Retention could not remove resume {} ({})", resume.getId(), e.getClass().getSimpleName());
                }
            }
        }
        return removed;
    }

    private int removeOrphanFiles(Instant now, Set<UUID> held, boolean dryRun) {
        Instant oldEnough = now.minus(properties.orphanFileMinAge());
        Map<UUID, List<StoredFile>> byCandidate = storage.listCandidateFiles().stream()
                .collect(Collectors.groupingBy(StoredFile::candidateId));
        int removed = 0;
        for (Map.Entry<UUID, List<StoredFile>> entry : byCandidate.entrySet()) {
            if (held.contains(entry.getKey())) {
                continue;
            }
            Set<String> referenced = new HashSet<>(resumes.findStoragePathsByCandidateId(entry.getKey()));
            for (StoredFile file : entry.getValue()) {
                if (referenced.contains(file.storageKey()) || file.lastModified().isAfter(oldEnough)) {
                    continue;
                }
                if (removed >= properties.batchSize()) {
                    return removed;
                }
                if (!dryRun) {
                    storage.delete(file.storageKey());
                }
                removed++;
            }
        }
        return removed;
    }

    private int purgeQuarantine(Instant now, boolean dryRun) {
        Instant oldEnough = now.minus(properties.orphanFileMinAge());
        int removed = 0;
        for (ResumeStorageService.QuarantinedEntry entry : storage.listQuarantined()) {
            if (entry.lastModified().isAfter(oldEnough)) {
                continue;
            }
            if (removed >= properties.batchSize()) {
                break;
            }
            if (!dryRun) {
                storage.purge(entry.quarantineKey());
            }
            removed++;
        }
        return removed;
    }
}
