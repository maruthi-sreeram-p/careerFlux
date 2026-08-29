package com.careerflux.notification.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.careerflux.common.TextUtils;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.ingestion.event.PipelineEventBus;
import com.careerflux.ingestion.event.PipelineTopics;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobChange;
import com.careerflux.matching.domain.JobMatch;
import com.careerflux.matching.domain.MatchTier;
import com.careerflux.notification.domain.Notification;
import com.careerflux.notification.domain.NotificationCategory;
import com.careerflux.notification.domain.NotificationPriority;
import com.careerflux.notification.repository.NotificationRepository;
import com.careerflux.source.domain.JobSource;
import com.careerflux.user.User;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates candidate notifications, with the routing rules from the product spec
 * applied in one place.
 *
 * <pre>
 *   95-100  immediate alert
 *   85-94   high priority
 *   70-84   daily digest
 *   below   not sent at all
 * </pre>
 *
 * <p>Two further rules keep this from becoming noise: a candidate is never told
 * about the same job twice, and candidates who switched immediate alerts off get
 * the digest instead of nothing.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final CareerFluxProperties properties;
    private final PipelineEventBus eventBus;

    public NotificationService(NotificationRepository notificationRepository,
                               CareerFluxProperties properties,
                               PipelineEventBus eventBus) {
        this.notificationRepository = notificationRepository;
        this.properties = properties;
        this.eventBus = eventBus;
    }

    /**
     * Notifies a candidate about a new match, if the score and their preferences
     * warrant it.
     *
     * @return the created notification, or empty when the match is below the floor,
     *         already notified, or filtered out by the candidate's settings
     */
    @Transactional
    public java.util.Optional<Notification> notifyMatch(User user, JobMatch match, boolean immediateAlertsEnabled) {
        if (match.getTier() == MatchTier.HIDDEN) {
            return java.util.Optional.empty();
        }
        NotificationPriority priority = priorityFor(match.getOverallScore());
        if (priority == NotificationPriority.LOW) {
            return java.util.Optional.empty();
        }
        if (priority == NotificationPriority.IMMEDIATE && !immediateAlertsEnabled) {
            // Respect the preference by downgrading rather than dropping.
            priority = NotificationPriority.DIGEST;
        }

        Job job = match.getJob();
        if (notificationRepository.existsByUserIdAndJobIdAndCategory(
                user.getId(), job.getId(), NotificationCategory.MATCH)) {
            return java.util.Optional.empty();
        }

        String company = job.getCompany() == null ? "a company" : job.getCompany().getName();
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setCategory(NotificationCategory.MATCH);
        notification.setPriority(priority);
        notification.setJob(job);
        notification.setMatchId(match.getId());
        notification.setTitle(TextUtils.truncate(
                match.getOverallScore() + "% match: " + job.getTitle() + " at " + company, 240));
        notification.setBody(TextUtils.truncate(match.getNarrative(), 1200));
        notificationRepository.save(notification);

        eventBus.publish(PipelineTopics.NOTIFICATION_CREATED, notification.getId().toString(),
                new NotificationCreatedEvent(notification.getId(), user.getId(), job.getId(),
                        priority.name(), match.getOverallScore()));
        return java.util.Optional.of(notification);
    }

    /** Tells a candidate that a job they saved or applied to has changed. */
    @Transactional
    public Notification notifyJobChange(User user, JobChange change) {
        Job job = change.getJob();
        String company = job.getCompany() == null ? "a company" : job.getCompany().getName();

        Notification notification = new Notification();
        notification.setUser(user);
        notification.setCategory(NotificationCategory.JOB_CHANGE);
        notification.setPriority(NotificationPriority.HIGH);
        notification.setJob(job);
        notification.setTitle(TextUtils.truncate(
                "Updated: " + job.getTitle() + " at " + company, 240));
        notification.setBody(TextUtils.truncate(change.getSummary(), 1200));
        return notificationRepository.save(notification);
    }

    /** Operational notice about a source, for administrators. */
    @Transactional
    public Notification notifySourceChange(User user, JobSource source, String title, String body) {
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setCategory(NotificationCategory.SOURCE_CHANGE);
        notification.setPriority(NotificationPriority.HIGH);
        notification.setSource(source);
        notification.setTitle(TextUtils.truncate(title, 240));
        notification.setBody(TextUtils.truncate(body, 1200));
        return notificationRepository.save(notification);
    }

    public NotificationPriority priorityFor(int score) {
        CareerFluxProperties.Matching thresholds = properties.matching();
        if (score >= thresholds.immediateMin()) {
            return NotificationPriority.IMMEDIATE;
        }
        if (score >= thresholds.highPriorityMin()) {
            return NotificationPriority.HIGH;
        }
        if (score >= thresholds.digestMin()) {
            return NotificationPriority.DIGEST;
        }
        return NotificationPriority.LOW;
    }

    @Transactional(readOnly = true)
    public Page<Notification> list(UUID userId, Pageable pageable) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        notificationRepository.findById(notificationId)
                .filter(notification -> notification.getUser().getId().equals(userId))
                .ifPresent(notification -> notification.setReadAt(Instant.now()));
    }

    @Transactional
    public int markAllRead(UUID userId) {
        List<Notification> unread = notificationRepository.findByUserIdAndReadAtIsNull(userId);
        Instant now = Instant.now();
        unread.forEach(notification -> notification.setReadAt(now));
        log.debug("Marked {} notifications read for user {}", unread.size(), userId);
        return unread.size();
    }

    public record NotificationCreatedEvent(UUID notificationId, UUID userId, UUID jobId,
                                           String priority, int score) {
    }
}
