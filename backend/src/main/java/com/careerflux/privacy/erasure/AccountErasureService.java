package com.careerflux.privacy.erasure;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.audit.AuditService;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.service.ResumeStorageService;
import com.careerflux.common.error.ConflictException;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.privacy.RetentionProperties;
import com.careerflux.security.access.AccessGuard;
import com.careerflux.user.Permission;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Erasure requests: making them, calling them off, and carrying them out.
 *
 * <p><b>Who may ask.</b> A student, for their own account. A placement
 * coordinator, for a student in their own college, through
 * {@code STUDENT_MANAGE}, which no department coordinator and no portal
 * administrator holds. Nobody else.
 *
 * <p><b>Grace period.</b> A request removes nothing when it is made. It waits
 * for the configured grace period, during which the student or their placement
 * coordinator can cancel it and the account works as before. After that the
 * retention worker carries it out, unless the worker is in dry-run mode, in which
 * case it reports the request as due and leaves it waiting.
 *
 * <p>Transactions are opened explicitly: moving resume files out of reach has to
 * happen outside the transaction that deletes their rows.
 */
@Service
public class AccountErasureService {

    private static final Logger log = LoggerFactory.getLogger(AccountErasureService.class);
    private static final String STUDENT = "Student";

    /**
     * How long a claimed request may sit in PROCESSING before it is taken up
     * again. One erasure is a handful of statements; an hour means the process
     * that claimed it stopped, not that it is still working.
     */
    static final java.time.Duration STALLED_AFTER = java.time.Duration.ofHours(1);

    /** A request as its student or their placement coordinator sees it. */
    public record ErasureView(UUID id, String status, String requestedByRole, Instant requestedAt,
                              Instant graceEndsAt, boolean cancellable, Instant cancelledAt, Instant completedAt) {

        static ErasureView of(AccountErasure erasure) {
            return new ErasureView(erasure.getId(), erasure.getStatus().name(), erasure.getRequestedByRole().name(),
                    erasure.getRequestedAt(), erasure.getGraceEndsAt(), erasure.isCancellable(),
                    erasure.getCancelledAt(), erasure.getCompletedAt());
        }
    }

    /** What one run of the worker did. Counts only. */
    public record ErasureRunReport(boolean dryRun, int due, int completed, int failed) {
    }

    private final AccountErasureRepository erasures;
    private final ErasureExecutor executor;
    private final UserRepository users;
    private final CandidateProfileRepository profiles;
    private final ResumeStorageService storage;
    private final AccessGuard accessGuard;
    private final AuditService audit;
    private final RetentionProperties properties;
    private final TransactionTemplate transaction;

    public AccountErasureService(AccountErasureRepository erasures, ErasureExecutor executor, UserRepository users,
                                 CandidateProfileRepository profiles, ResumeStorageService storage,
                                 AccessGuard accessGuard, AuditService audit, RetentionProperties properties,
                                 PlatformTransactionManager transactionManager) {
        this.erasures = erasures;
        this.executor = executor;
        this.users = users;
        this.profiles = profiles;
        this.storage = storage;
        this.accessGuard = accessGuard;
        this.audit = audit;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // ---------------------------------------------------------- the student

    public ErasureView requestBySelf(UUID userId) {
        User account = users.findById(userId).orElseThrow(() -> NotFoundException.of(STUDENT, userId));
        return request(account, UserRole.STUDENT);
    }

    public ErasureView cancelBySelf(UUID userId) {
        return cancel(userId, UserRole.STUDENT);
    }

    public Optional<ErasureView> statusForSelf(UUID userId) {
        return erasures.findFirstBySubjectUserIdOrderByRequestedAtDesc(userId).map(ErasureView::of);
    }

    // ------------------------------------------------ the placement coordinator

    public ErasureView requestByStaff(UUID studentUserId) {
        User student = requireManageableStudent(studentUserId);
        return request(student, accessGuard.scope().role());
    }

    public ErasureView cancelByStaff(UUID studentUserId) {
        requireManageableStudent(studentUserId);
        return cancel(studentUserId, accessGuard.scope().role());
    }

    public Optional<ErasureView> statusForStaff(UUID studentUserId) {
        requireManageableStudent(studentUserId);
        return erasures.findFirstBySubjectUserIdOrderByRequestedAtDesc(studentUserId).map(ErasureView::of);
    }

    /**
     * The student, if this caller may manage them: the permission first, then
     * the same college-and-scope check every staff read of a student goes
     * through, answering not-found for anyone outside it.
     */
    private User requireManageableStudent(UUID studentUserId) {
        accessGuard.requirePermission(Permission.STUDENT_MANAGE);
        return transaction.execute(status -> {
            CandidateProfile profile = profiles.findByUserId(studentUserId)
                    .orElseThrow(() -> NotFoundException.of(STUDENT, studentUserId));
            accessGuard.requireCanReadCandidate(profile);
            return profile.getUser();
        });
    }

    // -------------------------------------------------------------- requests

    private ErasureView request(User subject, UserRole requestedBy) {
        if (subject.getStatus() == UserStatus.ERASED) {
            throw new ConflictException("This account has already been erased.");
        }
        Optional<AccountErasure> open = erasures.findByOpenSubjectUserId(subject.getId());
        if (open.isPresent()) {
            return ErasureView.of(open.get());
        }
        try {
            AccountErasure saved = transaction.execute(status -> erasures.saveAndFlush(AccountErasure.request(
                    subject.getId(), subject.getInstitutionId(), requestedBy, Instant.now(),
                    properties.erasureGracePeriod())));
            audit.record("ERASURE_REQUESTED", "User", subject.getId(),
                    "requestedBy=" + requestedBy + " graceEndsAt=" + saved.getGraceEndsAt());
            log.info("Erasure {} requested for account {} by {}", saved.getId(), subject.getId(), requestedBy);
            return ErasureView.of(saved);
        } catch (DataIntegrityViolationException raced) {
            // Two requests arrived together; one open request is the answer to both.
            return erasures.findByOpenSubjectUserId(subject.getId()).map(ErasureView::of).orElseThrow(() -> raced);
        }
    }

    private ErasureView cancel(UUID subjectUserId, UserRole cancelledBy) {
        try {
            ErasureView view = transaction.execute(status -> {
                AccountErasure open = erasures.findByOpenSubjectUserId(subjectUserId)
                        .orElseThrow(() -> new NotFoundException("There is no erasure request to cancel."));
                if (!open.isCancellable()) {
                    throw new ConflictException("This erasure is already being carried out and can no longer "
                            + "be cancelled.");
                }
                open.cancel(cancelledBy, Instant.now());
                return ErasureView.of(erasures.saveAndFlush(open));
            });
            audit.record("ERASURE_CANCELLED", "User", subjectUserId, "cancelledBy=" + cancelledBy);
            return view;
        } catch (ObjectOptimisticLockingFailureException raced) {
            throw new ConflictException("This erasure request changed a moment ago. Reload to see where it stands.");
        }
    }

    // ------------------------------------------------------------- the worker

    /** Candidate profiles whose accounts have a request in flight, for retention to leave alone. */
    public List<UUID> heldCandidateIds() {
        return erasures.findHeldCandidateIds();
    }

    /**
     * Carries out every request that is due, up to the batch size.
     *
     * <p>In dry-run mode nothing is claimed and nothing is removed; the report
     * says how many are waiting. Each request is claimed with an optimistic lock
     * before any work, so two runners can never both carry out the same one.
     */
    public ErasureRunReport processDue(Instant now, boolean dryRun, int batchSize) {
        List<UUID> due = erasures.findDueIds(now, now.minus(STALLED_AFTER), PageRequest.of(0, batchSize));
        if (dryRun) {
            if (!due.isEmpty()) {
                log.info("Dry run: {} erasure requests are due and were not carried out", due.size());
            }
            return new ErasureRunReport(true, due.size(), 0, 0);
        }
        int completed = 0;
        int failed = 0;
        for (UUID erasureId : due) {
            if (!claim(erasureId, now)) {
                continue;
            }
            if (carryOut(erasureId)) {
                completed++;
            } else {
                failed++;
            }
        }
        return new ErasureRunReport(false, due.size(), completed, failed);
    }

    private boolean claim(UUID erasureId, Instant now) {
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> {
                AccountErasure erasure = erasures.findById(erasureId).orElse(null);
                if (erasure == null || !erasure.isDue(now, now.minus(STALLED_AFTER))) {
                    return false;
                }
                erasure.claim(Instant.now());
                erasures.saveAndFlush(erasure);
                return true;
            }));
        } catch (ObjectOptimisticLockingFailureException raced) {
            return false;
        }
    }

    private boolean carryOut(UUID erasureId) {
        UUID subject = transaction.execute(status -> erasures.findById(erasureId)
                .map(AccountErasure::getSubjectUserId).orElse(null));
        if (subject == null) {
            return false;
        }
        UUID candidateId = transaction.execute(status -> profiles.findByUserId(subject)
                .map(CandidateProfile::getId).orElse(null));
        String quarantined = null;
        try {
            if (candidateId != null) {
                quarantined = storage.quarantineCandidateDirectory(candidateId);
            }
            int files = quarantined == null ? 0 : storage.countFiles(quarantined);
            Map<String, Integer> counts = transaction.execute(status -> executor.erase(erasureId, Instant.now(), files));
            if (quarantined != null && !storage.purge(quarantined)) {
                log.warn("Erasure {} completed; its quarantined files will be destroyed by the retention sweep",
                        erasureId);
            }
            users.findById(subject).ifPresent(account -> audit.recordSystemAbout(account, "ERASURE_COMPLETED",
                    "User", subject, "erasure=" + erasureId + " counts=" + counts));
            log.info("Erasure {} completed: {}", erasureId, counts);
            return true;
        } catch (RuntimeException failure) {
            if (quarantined != null && !storage.restoreCandidateDirectory(quarantined, candidateId)) {
                storage.markRestoreFailed(quarantined);
                log.error("Erasure {} failed and its resume files could not be put back; they were set aside in "
                        + "quarantine for an operator", erasureId);
            }
            String code = failure.getClass().getSimpleName();
            try {
                transaction.executeWithoutResult(status -> erasures.findById(erasureId).ifPresent(erasure -> {
                    erasure.fail(code);
                    erasures.saveAndFlush(erasure);
                }));
            } catch (RuntimeException unrecorded) {
                log.error("Erasure {} failed and the failure could not be recorded ({})", erasureId,
                        unrecorded.getClass().getSimpleName());
            }
            users.findById(subject).ifPresent(account -> audit.recordSystemAbout(account, "ERASURE_FAILED",
                    "User", subject, "erasure=" + erasureId + " failure=" + code));
            log.warn("Erasure {} failed ({}); it will be retried on the next run", erasureId, code);
            return false;
        }
    }
}
