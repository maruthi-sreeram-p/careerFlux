package com.careerflux.ai.proposal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiProfileProposalRepository extends JpaRepository<AiProfileProposal, UUID> {

    List<AiProfileProposal> findByCandidateIdOrderByCreatedAtDesc(UUID candidateId);

    /** Used to retire whatever is still awaiting a student when a newer resume is read. */
    List<AiProfileProposal> findByCandidateIdAndStatus(UUID candidateId, ProposalStatus status);

    List<AiProfileProposal> findByResumeId(UUID resumeId);
}
