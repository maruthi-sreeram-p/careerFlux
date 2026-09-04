package com.careerflux.ingestion.event;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka transport for the same pipeline events.
 *
 * <p>Activated by {@code careerflux.ingestion.transport=kafka} (the {@code kafka}
 * profile sets it). The topic names and payloads are identical to the in-process
 * path, so switching transports changes the deployment shape and nothing about
 * the pipeline's behaviour.
 *
 * <p>The listener subscribes to every topic and routes by record topic, which
 * keeps the handler registration identical to the outbox dispatcher's.
 */
@Component
@ConditionalOnProperty(name = "careerflux.ingestion.transport", havingValue = "kafka")
public class KafkaEventBus implements PipelineEventBus {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventBus.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Handlers are resolved when a message arrives, not when this bean is built.
     *
     * <p>Injecting them directly formed a cycle and stopped the whole
     * application: one handler notifies candidates, notifying publishes an
     * event, and publishing is this bus. Spring refuses circular references by
     * default, so the {@code kafka} profile could not start at all — including
     * the profile docker-compose gives the backend.
     *
     * <p>The in-process transport never hit this because it writes to an outbox
     * and a separate dispatcher resolves handlers afterwards. Deferring the
     * lookup gives the Kafka path the same property: publishing needs nothing
     * but the template, and consuming happens long after the context is built.
     */
    private final ObjectProvider<PipelineEventHandler> handlerProvider;

    /** Built on first use, then reused. Handlers are singletons and do not change. */
    private volatile Map<String, PipelineEventHandler> handlersByTopic;

    public KafkaEventBus(KafkaTemplate<String, String> kafkaTemplate,
                         ObjectMapper objectMapper,
                         ObjectProvider<PipelineEventHandler> handlerProvider) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.handlerProvider = handlerProvider;
        log.info("Kafka transport active.");
    }

    private Map<String, PipelineEventHandler> handlers() {
        Map<String, PipelineEventHandler> resolved = handlersByTopic;
        if (resolved == null) {
            synchronized (this) {
                resolved = handlersByTopic;
                if (resolved == null) {
                    resolved = handlerProvider.stream()
                            .collect(Collectors.toMap(PipelineEventHandler::topic,
                                    Function.identity(), (first, second) -> first));
                    handlersByTopic = resolved;
                    log.info("Kafka handlers resolved for topics: {}", resolved.keySet());
                }
            }
        }
        return resolved;
    }

    @Override
    public void publish(String topic, String eventKey, Object payload) {
        kafkaTemplate.send(topic, eventKey, serialize(payload))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        log.error("Could not publish {}/{} to Kafka", topic, eventKey, failure);
                    }
                });
    }

    @Override
    public String transportName() {
        return "kafka";
    }

    @KafkaListener(topics = {
            PipelineTopics.JOB_RAW,
            PipelineTopics.JOB_NORMALIZED,
            PipelineTopics.JOB_DEDUPLICATED,
            PipelineTopics.JOB_ENRICHED,
            PipelineTopics.JOB_CLASSIFIED,
            PipelineTopics.JOB_CHANGED,
            PipelineTopics.JOB_EXPIRED,
            PipelineTopics.NOTIFICATION_CREATED
    }, groupId = "careerflux-ingestion")
    public void consume(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record) {
        PipelineEventHandler handler = handlers().get(record.topic());
        if (handler == null) {
            return;
        }
        try {
            handler.handle(record.key(), record.value());
        } catch (RuntimeException ex) {
            // Rethrown so Spring Kafka applies its configured error handling and retries.
            log.warn("Handler for {} failed on key {}: {}", record.topic(), record.key(), ex.getMessage());
            throw ex;
        }
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
