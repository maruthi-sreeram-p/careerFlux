package com.careerflux.config;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Spring AI's Gemini auto-configuration is gated on {@code spring.ai.model.chat=google-genai}
 * (with {@code matchIfMissing = true}), which means the application would fail to start when no
 * API key is configured. CareerFlux must boot and stay usable without AI, so the base
 * configuration pins that selector to {@code none} and this post-processor flips it back on when,
 * and only when, an API key is actually present in the environment.
 *
 * <p>Effect: the operator sets a single environment variable ({@code GEMINI_API_KEY}) and AI
 * features light up; set nothing and the application runs with AI reported as unavailable rather
 * than crashing.
 */
public class AiEnablementEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "careerFluxAiEnablement";
    private static final String API_KEY_PROPERTY = "spring.ai.google.genai.api-key";
    private static final String CHAT_MODEL_SELECTOR = "spring.ai.model.chat";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String apiKey = environment.getProperty(API_KEY_PROPERTY);
        if (!StringUtils.hasText(apiKey)) {
            return;
        }
        // An explicit operator choice always wins over the automatic enablement below.
        if (StringUtils.hasText(environment.getProperty(CHAT_MODEL_SELECTOR))
                && !"none".equals(environment.getProperty(CHAT_MODEL_SELECTOR))) {
            return;
        }
        environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(CHAT_MODEL_SELECTOR, "google-genai")));
    }
}
