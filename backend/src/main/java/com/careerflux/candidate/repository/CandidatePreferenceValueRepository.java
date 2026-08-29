package com.careerflux.candidate.repository;

import java.util.List;
import java.util.Collection;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidatePreferenceValue;
import com.careerflux.candidate.domain.PreferenceType;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidatePreferenceValueRepository extends JpaRepository<CandidatePreferenceValue, UUID> {

    List<CandidatePreferenceValue> findByCandidateIdOrderByValueTypeAscDisplayOrderAsc(UUID candidateId);

    List<CandidatePreferenceValue> findByCandidateIdAndValueType(UUID candidateId, PreferenceType valueType);

    void deleteByCandidateIdAndValueType(UUID candidateId, PreferenceType valueType);

    /** Every preference for a cohort, in one query. */
    List<CandidatePreferenceValue> findByCandidateIdIn(Collection<UUID> candidateIds);
}
