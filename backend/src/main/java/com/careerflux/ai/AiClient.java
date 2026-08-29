package com.careerflux.ai;

/**
 * The single boundary between CareerFlux and any language model.
 *
 * <p>Everything the model is allowed to influence goes through this port, and
 * everything it is <em>not</em> allowed to influence — authentication, source
 * state transitions, access policy, deduplication rules, scheduling, scoring
 * arithmetic — never calls it. Keeping the boundary this narrow is what makes it
 * possible to run the whole product with AI switched off.
 */
public interface AiClient {

    /** Whether a model is configured and usable right now. */
    boolean isAvailable();

    /** The model identifier to record against anything this client produced. */
    String modelName();

    /**
     * Asks the model for a value of {@code type}, parsed from its structured
     * output.
     *
     * @throws com.careerflux.common.error.AiUnavailableException when no model is
     *         configured, the call fails, or the response cannot be parsed.
     */
    <T> T structured(String systemPrompt, String userPrompt, Class<T> type);

    /**
     * Asks the model for a short piece of prose.
     *
     * @throws com.careerflux.common.error.AiUnavailableException when no model is
     *         configured or the call fails.
     */
    String text(String systemPrompt, String userPrompt);
}
