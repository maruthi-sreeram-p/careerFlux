package com.careerflux.candidate.service;

import java.util.UUID;
import java.util.function.Consumer;

import com.careerflux.ai.proposal.AiProfileProposalRepository;
import com.careerflux.audit.AuditService;
import com.careerflux.candidate.domain.Resume;
import com.careerflux.candidate.repository.ResumeRepository;
import com.careerflux.common.error.NotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes a resume: its file, its row, its extracted text and every proposal read
 * from it.
 *
 * <p>Nothing else. A student's profile, including every field they already
 * approved from a proposal, stays as it is; their placement history does not
 * refer to resumes and is untouched; the audit trail keeps its record that the
 * resume was uploaded and that it was deleted. Deleting the active resume leaves
 * the student with no active resume rather than quietly promoting an older one.
 *
 * <p>The file and the row are kept consistent through quarantine: see
 * {@link ResumeStorageService#quarantine}. Transactions are opened explicitly
 * because the file moves must happen outside them.
 */
@Service
public class ResumeDeletionService {

    private static final Logger log = LoggerFactory.getLogger(ResumeDeletionService.class);
    private static final String RESUME = "Resume";

    private final ResumeRepository resumes;
    private final AiProfileProposalRepository proposals;
    private final ResumeStorageService storage;
    private final AuditService audit;
    private final TransactionTemplate transaction;

    public ResumeDeletionService(ResumeRepository resumes, AiProfileProposalRepository proposals,
                                 ResumeStorageService storage, AuditService audit,
                                 PlatformTransactionManager transactionManager) {
        this.resumes = resumes;
        this.proposals = proposals;
        this.storage = storage;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * A student deleting one of their own resumes.
     *
     * <p>Any other resume id, whether it belongs to somebody else or does not
     * exist, is the same not-found. Ownership is checked before the file is
     * touched and again inside the transaction that deletes the row.
     */
    public void deleteOwn(UUID userId, UUID resumeId) {
        String storageKey = transaction.execute(status -> requireOwned(userId, resumeId).getStoragePath());
        boolean[] wasActive = new boolean[1];
        remove(resumeId, storageKey, resume -> {
            Resume owned = requireOwned(userId, resume.getId());
            wasActive[0] = owned.isActive();
        });
        audit.record("RESUME_DELETED", RESUME, resumeId, "by=STUDENT active=" + wasActive[0]);
        log.info("Resume {} deleted by its owner", resumeId);
    }

    /**
     * Removal by the retention sweep. Which resumes qualify is the sweep's
     * decision; this carries it out with the same file handling as a student's
     * own deletion.
     */
    public void removeForRetention(UUID resumeId) {
        String storageKey = transaction.execute(status -> resumes.findById(resumeId)
                .map(Resume::getStoragePath)
                .orElseThrow(() -> NotFoundException.of(RESUME, resumeId)));
        remove(resumeId, storageKey, resume -> {
        });
    }

    private void remove(UUID resumeId, String storageKey, Consumer<Resume> checkInsideTransaction) {
        String quarantined = storage.quarantine(storageKey);
        try {
            transaction.executeWithoutResult(status -> {
                Resume resume = resumes.findById(resumeId).orElseThrow(() -> NotFoundException.of(RESUME, resumeId));
                checkInsideTransaction.accept(resume);
                proposals.deleteAll(proposals.findByResumeId(resumeId));
                resumes.delete(resume);
                resumes.flush();
            });
        } catch (RuntimeException failure) {
            if (quarantined != null && !storage.restore(quarantined, storageKey)) {
                storage.markRestoreFailed(quarantined);
                log.error("Resume {} is still recorded but its file could not be put back; it was set aside "
                        + "in quarantine for an operator", resumeId);
            }
            throw failure;
        }
        if (quarantined != null && !storage.purge(quarantined)) {
            log.warn("Resume {} was deleted; its quarantined file will be destroyed by the retention sweep", resumeId);
        }
    }

    private Resume requireOwned(UUID userId, UUID resumeId) {
        Resume resume = resumes.findById(resumeId).orElseThrow(() -> NotFoundException.of(RESUME, resumeId));
        if (resume.getCandidate().getUser() == null || !resume.getCandidate().getUser().getId().equals(userId)) {
            throw NotFoundException.of(RESUME, resumeId);
        }
        return resume;
    }
}
