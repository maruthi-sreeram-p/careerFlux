package com.careerflux.privacy.erasure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountErasureRepository extends JpaRepository<AccountErasure, UUID> {

    /** The request in flight for an account, if there is one. At most one exists (V24). */
    Optional<AccountErasure> findByOpenSubjectUserId(UUID subjectUserId);

    Optional<AccountErasure> findFirstBySubjectUserIdOrderByRequestedAtDesc(UUID subjectUserId);

    /**
     * Requests the worker should carry out now: grace over, an earlier attempt
     * failed, or an attempt was claimed and never finished because the process
     * stopped part-way.
     */
    @Query("""
            select e.id from AccountErasure e
            where (e.status = com.careerflux.privacy.erasure.ErasureStatus.GRACE_PERIOD and e.graceEndsAt <= :now)
               or e.status = com.careerflux.privacy.erasure.ErasureStatus.FAILED
               or (e.status = com.careerflux.privacy.erasure.ErasureStatus.PROCESSING
                   and e.processingStartedAt < :stalledBefore)
            order by e.requestedAt asc
            """)
    List<UUID> findDueIds(@Param("now") Instant now, @Param("stalledBefore") Instant stalledBefore,
                          Pageable pageable);

    /**
     * Candidate profiles whose account has a request in flight. Retention leaves
     * these alone: a cancelled request must find the account as it was.
     */
    @Query("""
            select p.id from com.careerflux.candidate.domain.CandidateProfile p, AccountErasure e
            where e.openSubjectUserId is not null and p.user.id = e.subjectUserId
            """)
    List<UUID> findHeldCandidateIds();
}
