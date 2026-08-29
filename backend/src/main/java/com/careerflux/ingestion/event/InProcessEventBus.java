package com.careerflux.ingestion.event;

import java.time.Instant;

import com.careerflux.common.TextUtils;
import com.careerflux.ingestion.domain.PipelineEvent;
import com.careerflux.ingestion.domain.PipelineEventStatus;
import com.careerflux.ingestion.repository.PipelineEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The default transport: a transactional outbox dispatched inside this process.
 *
 * <p>The event row is written in the caller's transaction, so it commits or rolls
 * back with the data it describes. {@code OutboxDispatcher} then picks it up and
 * hands it to the registered handlers, retrying failures with a bounded attempt
 * count. That gives the same at-least-once delivery and idempotency guarantees a
 * broker would, without requiring one to be running.
 */
@Component
@ConditionalOnProperty(name = "careerflux.ingestion.transport", havingValue = "in-process", matchIfMissing = true)
public class InProcessEventBus implements PipelineEventBus {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventBus.class);

    private final PipelineEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public InProcessEventBus(PipelineEventRepository eventRepository, ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void publish(String topic, String eventKey, Object payload) {
        String key = TextUtils.truncate(eventKey, 255);

        // Idempotency: an event already accepted for this topic and key is not re-queued.
        if (eventRepository.existsByTopicAndEventKeyAndStatus(topic, key, PipelineEventStatus.PENDING)) {
            log.debug("Event {}/{} is already queued; skipping duplicate publish", topic, key);
            return;
        }

        PipelineEvent event = new PipelineEvent();
        event.setTopic(topic);
        event.setEventKey(key);
        event.setPayload(serialize(payload));
        event.setStatus(PipelineEventStatus.PENDING);
        event.setCreatedAt(Instant.now());
        eventRepository.save(event);
    }

    @Override
    public String transportName() {
        return "in-process outbox";
    }

    private String serialize(Object payload) {
        if (payload == null) {
            return "{}";
        }
        if (payload instanceof String string) {
            return string;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Could not serialise pipeline event payload: {}", ex.getMessage());
            return "{\"serializationError\":true}";
        }
    }
}
