package com.careerflux.matching.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.careerflux.matching.domain.MatchComponent;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchComponentRepository extends JpaRepository<MatchComponent, UUID> {

    List<MatchComponent> findByMatchIdOrderByDisplayOrderAsc(UUID matchId);

    List<MatchComponent> findByMatchIdInOrderByDisplayOrderAsc(Collection<UUID> matchIds);
}
