package com.careerflux.candidate.repository;

import java.util.List;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.candidate.domain.Resume;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumeRepository extends JpaRepository<Resume, UUID> {

    Optional<Resume> findFirstByCandidateIdAndActiveTrueOrderByUploadedAtDesc(UUID candidateId);

    List<Resume> findByCandidateIdOrderByUploadedAtDesc(UUID candidateId);

    List<Resume> findByCandidateId(UUID candidateId);

    long countByCandidateId(UUID candidateId);

    /** Students in the cohort who have uploaded at least one resume. */
    @Query("select count(distinct r.candidate.id) from Resume r where r.candidate.id in :candidateIds")
    long countCandidatesWithResume(@Param("candidateIds") Collection<UUID> candidateIds);

    /** Which of these candidates have a resume, in one query rather than one each. */
    @Query("select distinct r.candidate.id from Resume r where r.candidate.id in :candidateIds")
    List<UUID> findCandidateIdsWithResume(@Param("candidateIds") Collection<UUID> candidateIds);

    // ------------------------------------------------------------- retention

    /** Resumes whose text is still held and older than the cutoff, oldest first. */
    @Query("""
            select r.id from Resume r
            where r.textDroppedAt is null and r.uploadedAt < :cutoff and r.candidate.id not in :held
            order by r.uploadedAt asc
            """)
    List<UUID> findTextDue(@Param("cutoff") java.time.Instant cutoff, @Param("held") Collection<UUID> held,
                           org.springframework.data.domain.Pageable pageable);

    @Query("""
            select count(r) from Resume r
            where r.textDroppedAt is null and r.uploadedAt < :cutoff and r.candidate.id not in :held
            """)
    long countTextDue(@Param("cutoff") java.time.Instant cutoff, @Param("held") Collection<UUID> held);

    /** Removes the text of these resumes, once each. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Resume r set r.extractedText = null, r.textDroppedAt = :at "
            + "where r.id in :ids and r.textDroppedAt is null")
    int dropText(@Param("ids") Collection<UUID> ids, @Param("at") java.time.Instant at);

    /** Students holding more superseded resumes than the policy keeps. */
    @Query("""
            select r.candidate.id from Resume r
            where r.active = false and r.candidate.id not in :held
            group by r.candidate.id having count(r) > :kept
            """)
    List<UUID> findCandidatesOverVersionCap(@Param("kept") long kept, @Param("held") Collection<UUID> held,
                                           org.springframework.data.domain.Pageable pageable);

    @Query("select r.storagePath from Resume r where r.candidate.id = :candidateId")
    List<String> findStoragePathsByCandidateId(@Param("candidateId") UUID candidateId);
}
