package com.careerflux.candidate.repository;

import java.util.List;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateSkill;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CandidateSkillRepository extends JpaRepository<CandidateSkill, UUID> {

    @Query("select cs from CandidateSkill cs join fetch cs.skill where cs.candidate.id = :candidateId")
    List<CandidateSkill> findByCandidateId(UUID candidateId);

    void deleteByCandidateId(UUID candidateId);

    /** Every skill for a cohort, in one query, for batch snapshot building. */
    @Query("""
            select cs from CandidateSkill cs join fetch cs.skill
            where cs.candidate.id in :candidateIds
            """)
    List<CandidateSkill> findByCandidateIdIn(@Param("candidateIds") Collection<UUID> candidateIds);
}
