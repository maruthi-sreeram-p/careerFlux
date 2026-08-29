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
}
