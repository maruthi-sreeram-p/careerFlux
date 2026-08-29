package com.careerflux.notification.repository;

import java.util.List;
import java.util.UUID;

import com.careerflux.notification.domain.Notification;
import com.careerflux.notification.domain.NotificationCategory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @EntityGraph(attributePaths = {"job", "job.company"})
    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<Notification> findByUserIdAndReadAtIsNull(UUID userId);

    long countByUserIdAndReadAtIsNull(UUID userId);

    boolean existsByUserIdAndJobIdAndCategory(UUID userId, UUID jobId, NotificationCategory category);
}
