package com.careerflux.config;

import java.util.Arrays;
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
 *
 * <p><b>Never under the test profile.</b> Reading the key from the environment is exactly right
 * for a deployment and exactly wrong for a test run: a developer who has exported
 * {@code GEMINI_API_KEY} to work on the AI features would, without this, have the automated suite
 * quietly start calling — and being billed for — the real model. Two integration tests upload a
 * resume without stubbing extraction, so the suite's result would depend on somebody else's
 * uptime, and a provider outage would read as a CareerFlux regression.
 *
 * <p>Pinning {@code spring.ai.model.chat=none} in {@code application-test.yml} would not have
 * worked: the guard below deliberately treats {@code none} as "the default, not a choice", since
 * that is the value the base configuration always carries. The profile is the honest signal.
 */
public class AiEnablementEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "careerFluxAiEnablement";
    private static final String API_KEY_PROPERTY = "spring.ai.google.genai.api-key";
    private static final String CHAT_MODEL_SELECTOR = "spring.ai.model.chat";

    /** The profile under which a live model call is never what the caller wanted. */
    private static final String TEST_PROFILE = "test";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Checked before the key, so that an automated run behaves identically
        // whether or not the machine happens to have a credential on it.
        if (isTestRun(environment)) {
            return;
        }
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

    /**
     * Whether this is an automated test run.
     *
     * <p>Both sources are consulted because they are populated at different
     * moments. {@code @ActiveProfiles} on a test class arrives as the resolved
     * active profiles, while a command line or IDE run configuration arrives as
     * the raw {@code spring.profiles.active} property; reading only one of them
     * would leave a way in.
     */
    private static boolean isTestRun(ConfigurableEnvironment environment) {
        if (Arrays.asList(environment.getActiveProfiles()).contains(TEST_PROFILE)) {
            return true;
        }
        String requested = environment.getProperty("spring.profiles.active");
        return requested != null
                && Arrays.stream(requested.split(","))
                        .map(String::trim)
                        .anyMatch(TEST_PROFILE::equals);
    }
}
