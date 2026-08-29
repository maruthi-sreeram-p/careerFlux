package com.careerflux.ingestion.event;

/**
 * Consumes events off the pipeline bus.
 *
 * <p>Handlers must be idempotent: the same event may be delivered more than once,
 * whether the transport is the outbox dispatcher retrying or Kafka redelivering.
 */
public interface PipelineEventHandler {

    /** The topic this handler subscribes to. */
    String topic();

    /**
     * Processes one event.
     *
     * @param eventKey the idempotency key the event was published with
     * @param payload  the raw JSON body
     * @throws RuntimeException to signal a retryable failure
     */
    void handle(String eventKey, String payload);
}
