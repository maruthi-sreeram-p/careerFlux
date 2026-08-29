package com.careerflux.audit;

import java.util.UUID;

import com.careerflux.common.TextUtils;
import com.careerflux.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

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
        var principal = currentUser.find();
        record(principal.map(p -> p.getEmail()).orElse("system"),
                principal.map(p -> p.getUserId()).orElse(null),
                action, entityType, entityId, detail);
    }

    /** Records an event attributed to a non-interactive actor such as the scheduler. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSystem(String actor, String action, String entityType, Object entityId, String detail) {
        record(actor, null, action, entityType, entityId, detail);
    }

    private void record(String actor, UUID actorUserId, String action, String entityType,
                        Object entityId, String detail) {
        AuditEvent event = new AuditEvent();
        event.setActor(TextUtils.truncate(actor, 160));
        event.setActorUserId(actorUserId);
        event.setAction(TextUtils.truncate(action, 80));
        event.setEntityType(TextUtils.truncate(entityType, 60));
        event.setEntityId(entityId == null ? null : TextUtils.truncate(entityId.toString(), 64));
        event.setDetail(TextUtils.truncate(detail, 2000));
        repository.save(event);
    }
}
