package com.careerflux.ai;

import com.careerflux.common.error.AiUnavailableException;

/**
 * Used when no model is configured. It fails loudly rather than quietly
 * returning fabricated output, so callers are forced to take their deterministic
 * fallback path and the UI can say plainly that AI is switched off.
 */
public class UnavailableAiClient implements AiClient {

    private static final String MESSAGE =
            "AI is not configured. Set GEMINI_API_KEY in the environment to enable it.";

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String modelName() {
        return "none";
    }

    @Override
    public <T> T structured(String systemPrompt, String userPrompt, Class<T> type) {
        throw new AiUnavailableException(MESSAGE);
    }

    @Override
    public String text(String systemPrompt, String userPrompt) {
        throw new AiUnavailableException(MESSAGE);
    }
}
