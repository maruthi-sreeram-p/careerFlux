package com.careerflux.ingestion.event;

/**
 * The transport the ingestion pipeline publishes stage events on.
 *
 * <p>Two implementations exist. {@code InProcessEventBus} is the default: events
 * are written to a transactional outbox and dispatched in-process, which is the
 * right shape for a single node and needs no infrastructure to run.
 * {@code KafkaEventBus} publishes the identical events to the identical topic
 * names through Spring Kafka, and is selected by setting
 * {@code careerflux.ingestion.transport=kafka} (the {@code kafka} profile does
 * this).
 *
 * <p>The abstraction exists so the pipeline is genuinely event-driven either way,
 * rather than pretending to be distributed when no broker is running.
 */
public interface PipelineEventBus {

    /**
     * Publishes an event.
     *
     * @param topic     one of the constants in {@link PipelineTopics}
     * @param eventKey  the idempotency key; replaying the same key must be a no-op
     * @param payload   the event body, serialised to JSON by the implementation
     */
    void publish(String topic, String eventKey, Object payload);

    /** A short name for the active transport, shown on the operations console. */
    String transportName();
}
