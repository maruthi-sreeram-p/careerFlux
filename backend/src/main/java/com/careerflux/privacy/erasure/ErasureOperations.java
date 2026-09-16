package com.careerflux.privacy.erasure;

import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The deletions an erasure performs, one statement per kind of data.
 *
 * <p>Every statement removes data that belongs to the student alone. None of
 * them touches a shortlist entry, a placement stage change or an audit row:
 * the one statement that reaches placement history only replaces the name a
 * stage change was labelled with, and leaves the event itself as it was.
 */
public interface ErasureOperations extends Repository<AccountErasure, UUID> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from com.careerflux.ai.proposal.AiProfileProposal p where p.candidate.id = :candidateId")
    int deleteProposals(@Param("candidateId") UUID candidateId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from com.careerflux.candidate.domain.Resume r where r.candidate.id = :candidateId")
    int deleteResumes(@Param("candidateId") UUID candidateId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from com.careerflux.candidate.domain.CandidateSkill s where s.candidate.id = :candidateId")
    int deleteSkills(@Param("candidateId") UUID candidateId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from com.careerflux.candidate.domain.CandidateCustomSkill s where s.candidate.id = :candidateId")
    int deletePrivateSkills(@Param("candidateId") UUID candidateId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from com.careerflux.candidate.domain.CandidateExperience e where e.candidate.id = :candidateId")
    int deleteExperiences(@Param("candidateId") UUID candidateId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from com.careerflux.candidate.domain.CandidateEducation e where e.candidate.id = :candidateId")
    int deleteEducation(@Param("candidateId") UUID candidateId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from com.careerflux.candidate.domain.CandidatePreferenceValue v where v.candidate.id = :candidateId")
    int deletePreferenceValues(@Param("candidateId") UUID candidateId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from com.careerflux.candidate.domain.CandidatePreferences p where p.candidate.id = :candidateId")
    int deletePreferences(@Param("candidateId") UUID candidateId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from com.careerflux.engagement.domain.JobInteraction i where i.candidate.id = :candidateId")
    int deleteJobInteractions(@Param("candidateId") UUID candidateId);

    /** Match components go with their match through V4's cascade. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from com.careerflux.matching.domain.JobMatch m where m.candidate.id = :candidateId")
    int deleteJobMatches(@Param("candidateId") UUID candidateId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from com.careerflux.matching.rematch.RematchRequest r where r.candidate.id = :candidateId")
    int deleteRematchRequests(@Param("candidateId") UUID candidateId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from com.careerflux.notification.domain.Notification n where n.user.id = :userId")
    int deleteNotifications(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from com.careerflux.ai.quota.AiUsageCounter c
            where c.scope = com.careerflux.ai.quota.AiQuotaScope.USER and c.scopeId = :userId
            """)
    int deleteAiUsageCounters(@Param("userId") UUID userId);

    /**
     * The name a stage change was labelled with when the student made it.
     * The stage, the time and the kind of actor are left exactly as recorded.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update com.careerflux.shortlist.domain.PlacementStageChange c
            set c.actorLabel = :label where c.actor.id = :userId
            """)
    int anonymiseStageChangeLabels(@Param("userId") UUID userId, @Param("label") String label);
}
