package com.careerflux.consent;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeVersionRepository extends JpaRepository<NoticeVersion, UUID> {

    Optional<NoticeVersion> findByKindAndVersion(ConsentPurpose kind, String version);
}
