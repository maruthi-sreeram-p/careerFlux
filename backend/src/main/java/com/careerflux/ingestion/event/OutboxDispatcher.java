package com.careerflux.ingestion.event;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.careerflux.common.TextUtils;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.ingestion.domain.PipelineEvent;
import com.careerflux.ingestion.domain.PipelineEventStatus;
import com.careerflux.ingestion.repository.PipelineEventRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the outbox and delivers each event to the handler registered for its
 * topic.
 *
 * <p>Delivery is at-least-once with a bounded retry count: an event that fails
 * {@value #MAX_ATTEMPTS} times is parked as FAILED and surfaced on the operations
 * console rather than retried forever. Each event is processed in its own
 * transaction so one poisonous event cannot roll back a whole batch.
 */
@Component
@ConditionalOnProperty(name = "careerflux.ingestion.transport", havingValue = "in-process", matchIfMissing = true)
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final int BATCH_SIZE = 100;
    private static final int PROCESSED_RETENTION_DAYS = 7;

    private final PipelineEventRepository eventRepository;
    private final Map<String, PipelineEventHandler> handlersByTopic;
    private final CareerFluxProperties properties;

    public OutboxDispatcher(PipelineEventRepository eventRepository,
                            List<PipelineEventHandler> handlers,
                            CareerFluxProperties properties) {
        this.eventRepository = eventRepository;
        this.properties = properties;
        this.handlersByTopic = handlers.stream()
                .collect(Collectors.toMap(PipelineEventHandler::topic, Function.identity(),
                        (first, second) -> {
                            throw new IllegalStateException(
                                    "Two handlers registered for topic " + first.topic());
                        }));
        log.info("Outbox dispatcher handling topics: {}", handlersByTopic.keySet());
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void drain() {
        if (!properties.ingestion().schedulerEnabled()) {
            return;
        }
        drainOnce();
    }

    /** Processes one batch. Exposed so tests and manual triggers can drain synchronously. */
    public int drainOnce() {
        List<PipelineEvent> pending = eventRepository.findByStatusOrderByCreatedAtAsc(
                PipelineEventStatus.PENDING, PageRequest.of(0, BATCH_SIZE));
        if (pending.isEmpty()) {
            return 0;
        }
        int processed = 0;
        for (PipelineEvent event : pending) {
            if (deliver(event.getId())) {
                processed++;
            }
        }
        return processed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean deliver(java.util.UUID eventId) {
        PipelineEvent event = eventRepository.findById(eventId).orElse(null);
        if (event == null || event.getStatus() != PipelineEventStatus.PENDING) {
            return false;
        }

        PipelineEventHandler handler = handlersByTopic.get(event.getTopic());
        if (handler == null) {
            // Not an error: some topics exist for observability and have no consumer yet.
            event.setStatus(PipelineEventStatus.PROCESSED);
            event.setProcessedAt(Instant.now());
            eventRepository.save(event);
            return true;
        }

        event.setAttempts(event.getAttempts() + 1);
        try {
            handler.handle(event.getEventKey(), event.getPayload());
            event.setStatus(PipelineEventStatus.PROCESSED);
            event.setProcessedAt(Instant.now());
            event.setError(null);
            eventRepository.save(event);
            return true;
        } catch (RuntimeException ex) {
            event.setError(TextUtils.truncate(ex.getMessage(), 1000));
            if (event.getAttempts() >= MAX_ATTEMPTS) {
                event.setStatus(PipelineEventStatus.FAILED);
                event.setProcessedAt(Instant.now());
                log.error("Event {}/{} failed permanently after {} attempts",
                        event.getTopic(), event.getEventKey(), event.getAttempts(), ex);
            } else {
                log.warn("Event {}/{} failed on attempt {}: {}",
                        event.getTopic(), event.getEventKey(), event.getAttempts(), ex.getMessage());
            }
            eventRepository.save(event);
            return false;
        }
    }

    @Scheduled(cron = "0 45 3 * * *")
    @Transactional
    public void pruneProcessed() {
        int removed = eventRepository.deleteProcessedBefore(
                Instant.now().minus(Duration.ofDays(PROCESSED_RETENTION_DAYS)));
        if (removed > 0) {
            log.info("Pruned {} processed pipeline events", removed);
        }
    }
}
