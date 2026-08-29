package com.careerflux.ingestion.event;

/**
 * The pipeline's event vocabulary.
 *
 * <p>These names are the topics when Kafka is the transport, and the routing keys
 * when it is not. The stages they describe are real either way:
 *
 * <pre>
 *   adapter -> job.raw -> job.normalized -> job.deduplicated
 *                      -> job.enriched   -> job.classified   -> stored
 *                                          job.changed
 *                                          job.expired
 *                                          notification.created
 * </pre>
 */
public final class PipelineTopics {

    /** A posting exactly as an adapter read it. */
    public static final String JOB_RAW = "job.raw";

    /** The same posting after field normalization: title, location, salary, work mode. */
    public static final String JOB_NORMALIZED = "job.normalized";

    /** After the canonical job has been identified or created. */
    public static final String JOB_DEDUPLICATED = "job.deduplicated";

    /** After structured attributes have been extracted from the description. */
    public static final String JOB_ENRICHED = "job.enriched";

    /** After skills and seniority have been resolved against the canonical dictionary. */
    public static final String JOB_CLASSIFIED = "job.classified";

    /** A difference was detected against the previous observation. */
    public static final String JOB_CHANGED = "job.changed";

    /** A job stopped appearing at every source that used to list it. */
    public static final String JOB_EXPIRED = "job.expired";

    /** A notification is ready to be delivered to a candidate. */
    public static final String NOTIFICATION_CREATED = "notification.created";

    private PipelineTopics() {
    }
}
