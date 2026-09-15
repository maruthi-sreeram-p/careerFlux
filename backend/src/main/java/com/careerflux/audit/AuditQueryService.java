package com.careerflux.audit;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import com.careerflux.security.access.AccessGuard;
import com.careerflux.user.Permission;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may read which audit rows.
 *
 * <p>Two trails, never one. A college's placement coordinator reads their own
 * college's events, found by the college in their session. The Portal Admin
 * reads the platform's: the rows that belong to no college, which are their own
 * actions and scheduled work. Owning the platform is not a reason to read a
 * college's record of what its students and staff did, so there is no route
 * that returns every row.
 *
 * <p>No response names anybody by email. A person is an account id and a role;
 * the stored actor text is shown only for system actors, and any address in
 * text written before V18 is removed before it leaves the server.
 */
@Service
public class AuditQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    /** Loose on purpose: anything shaped like an address is removed, not validated. */
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+");

    static final String ADDRESS_REMOVED = "[address removed]";

    private final AuditEventRepository repository;
    private final AccessGuard accessGuard;

    public AuditQueryService(AuditEventRepository repository, AccessGuard accessGuard) {
        this.repository = repository;
        this.accessGuard = accessGuard;
    }

    /** The platform's own trail: Portal Admin actions and scheduled work, and no college's rows. */
    @Transactional(readOnly = true)
    public Page<AuditView> platformEvents(int page, int size) {
        accessGuard.requirePermission(Permission.AUDIT_READ_PLATFORM);
        return repository.findByInstitutionIdIsNullOrderByOccurredAtDesc(pageable(page, size))
                .map(AuditQueryService::toView);
    }

    /** One college's trail, for that college. The college comes from the session and nowhere else. */
    @Transactional(readOnly = true)
    public Page<AuditView> institutionEvents(int page, int size) {
        accessGuard.requirePermission(Permission.AUDIT_READ_INSTITUTION);
        UUID institutionId = accessGuard.requireInstitutionId();
        return repository.findByInstitutionIdOrderByOccurredAtDesc(institutionId, pageable(page, size))
                .map(AuditQueryService::toView);
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    static AuditView toView(AuditEvent event) {
        // A person is shown by role. The actor column is shown only for system
        // actors, and rows from before V18 may still hold an address in it.
        String actorLabel = event.getActorUserId() != null
                ? (event.getActorRole() == null ? "user" : event.getActorRole())
                : withoutAddresses(event.getActor());
        return new AuditView(event.getId(), event.getActorUserId(), event.getActorRole(), actorLabel,
                event.getAction(), event.getEntityType(), event.getEntityId(),
                withoutAddresses(event.getDetail()), event.getOccurredAt());
    }

    static String withoutAddresses(String text) {
        return text == null ? null : ADDRESS.matcher(text).replaceAll(ADDRESS_REMOVED);
    }

    /**
     * One audit row as a reader sees it.
     *
     * @param actorUserId the account that acted, or null for system work and
     *                    anonymous requests
     * @param actorLabel  the actor's role, or the system actor's name — never an
     *                    address
     */
    public record AuditView(
            UUID id,
            UUID actorUserId,
            String actorRole,
            String actorLabel,
            String action,
            String entityType,
            String entityId,
            String detail,
            Instant occurredAt) {
    }
}
