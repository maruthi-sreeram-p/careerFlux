package com.careerflux.audit;

import java.util.Optional;
import java.util.UUID;

import com.careerflux.common.TextUtils;
import com.careerflux.security.AuthenticatedUser;
import com.careerflux.security.CurrentUser;
import com.careerflux.user.User;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit trail.
 *
 * <p>Every row says who acted by account id and role, and which college the
 * event belongs to — never by email address. The college is what later decides
 * who may read the row ({@link AuditQueryService}): a college reads its own, and
 * the Portal Admin reads the platform's, which is the rows that belong to none.
 *
 * <p>Callers write the detail and must keep it free of personal data:
 * identifiers, counts, codes and states, never names, addresses or anything a
 * student wrote.
 */
@Service
public class AuditService {

    /** The actor of work nobody is signed in for. */
    static final String SYSTEM = "system";

    /** Somebody with no session who cannot be named, such as whoever asked for a reset. */
    static final String UNAUTHENTICATED = "unauthenticated";

    private final AuditEventRepository repository;
    private final CurrentUser currentUser;

    public AuditService(AuditEventRepository repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    /**
     * Records an event using the calling user as the actor. Written in its own
     * transaction so that an audit failure can never roll back the business
     * operation it was describing, and a rolled-back operation still leaves the
     * attempt visible.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String entityType, Object entityId, String detail) {
        Optional<AuthenticatedUser> principal = currentUser.find();
        if (principal.isPresent()) {
            saveFor(principal.get(), action, entityType, entityId, detail);
        } else {
            save(SYSTEM, null, null, null, action, entityType, entityId, detail);
        }
    }

    /**
     * An account acting for itself before it has a session: registering, or
     * completing a password reset with the link it was sent.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAsAccount(User account, String action, String entityType, Object entityId, String detail) {
        save(account.getRole().name(), account.getId(), account.getRole().name(), account.getInstitutionId(),
                action, entityType, entityId, detail);
    }

    /**
     * Something asked of an account by somebody who cannot be named — a password
     * reset request, which anybody may make for any address. The event belongs
     * to the account's college. The actor is recorded as unknown rather than as
     * the account holder, because it may not have been them.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAboutAccount(User account, String action, String entityType, Object entityId, String detail) {
        save(UNAUTHENTICATED, null, null, account.getInstitutionId(), action, entityType, entityId, detail);
    }

    /**
     * Work nobody is signed in for, such as a scheduled check. When a person is
     * signed in after all, they are the actor: a name passed in never outranks
     * the session.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSystem(String actor, String action, String entityType, Object entityId, String detail) {
        Optional<AuthenticatedUser> principal = currentUser.find();
        if (principal.isPresent()) {
            saveFor(principal.get(), action, entityType, entityId, detail);
            return;
        }
        save(systemLabel(actor), null, null, null, action, entityType, entityId, detail);
    }

    /**
     * What a system actor is called. A system actor is a name like "scheduler";
     * an address means a person with no session, and rows do not name people by
     * address.
     */
    static String systemLabel(String actor) {
        if (!TextUtils.hasText(actor)) {
            return SYSTEM;
        }
        return actor.contains("@") ? UNAUTHENTICATED : TextUtils.truncate(actor.strip(), 160);
    }

    private void saveFor(AuthenticatedUser person, String action, String entityType, Object entityId,
                         String detail) {
        save(person.getRole().name(), person.getUserId(), person.getRole().name(), person.getInstitutionId(),
                action, entityType, entityId, detail);
    }

    private void save(String actorLabel, UUID actorUserId, String actorRole, UUID institutionId,
                      String action, String entityType, Object entityId, String detail) {
        AuditEvent event = new AuditEvent();
        event.setActor(TextUtils.truncate(actorLabel, 160));
        event.setActorUserId(actorUserId);
        event.setActorRole(actorRole);
        event.setInstitutionId(institutionId);
        event.setAction(TextUtils.truncate(action, 80));
        event.setEntityType(TextUtils.truncate(entityType, 60));
        event.setEntityId(entityId == null ? null : TextUtils.truncate(entityId.toString(), 64));
        event.setDetail(TextUtils.truncate(detail, 2000));
        repository.save(event);
    }
}
