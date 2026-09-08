package com.careerflux.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Whether a developer's own Gemini credential can leak into an automated run.
 *
 * <p>The post-processor reads the API key out of the environment, which is
 * right for a deployment and dangerous for a test: a developer working on the
 * AI features has {@code GEMINI_API_KEY} exported, and without the profile
 * guard the whole suite would start making real, billed model calls. Two
 * integration tests upload a resume without stubbing extraction, so the suite's
 * result would depend on a third party's uptime and a provider outage would
 * read as a CareerFlux regression.
 *
 * <p>These run the post-processor directly against a synthetic environment.
 * Nothing here needs a Spring context, a key, or a network.
 */
class AiEnablementEnvironmentPostProcessorTest {

    private static final String SELECTOR = "spring.ai.model.chat";
    private static final String API_KEY = "spring.ai.google.genai.api-key";

    private final AiEnablementEnvironmentPostProcessor processor =
            new AiEnablementEnvironmentPostProcessor();

    /** The base configuration's state: the selector pinned off, so the app boots without a key. */
    private static MockEnvironment pinnedOff() {
        return new MockEnvironment().withProperty(SELECTOR, "none");
    }

    private String selectorAfterProcessing(MockEnvironment environment) {
        processor.postProcessEnvironment(environment, null);
        return environment.getProperty(SELECTOR);
    }

    @Nested
    @DisplayName("an automated run")
    class TestRuns {

        @Test
        @DisplayName("does not enable the model, even with a key in the environment")
        void activeTestProfileKeepsAiOff() {
            MockEnvironment environment = pinnedOff();
            environment.setActiveProfiles("test");
            environment.setProperty(API_KEY, "a-key-shaped-value");

            assertThat(selectorAfterProcessing(environment))
                    .describedAs("a developer's credential must not turn the suite into a billed client")
                    .isEqualTo("none");
        }

        @Test
        @DisplayName("is recognised from spring.profiles.active as well as the resolved profiles")
        void requestedTestProfileKeepsAiOff() {
            MockEnvironment environment = pinnedOff();
            environment.setProperty("spring.profiles.active", "postgres,test");
            environment.setProperty(API_KEY, "a-key-shaped-value");

            assertThat(selectorAfterProcessing(environment))
                    .describedAs("a command line or IDE run states the profile as a property")
                    .isEqualTo("none");
        }

        @Test
        @DisplayName("is recognised despite surrounding whitespace in the profile list")
        void whitespaceInProfileListStillCounts() {
            MockEnvironment environment = pinnedOff();
            environment.setProperty("spring.profiles.active", " postgres , test ");
            environment.setProperty(API_KEY, "a-key-shaped-value");

            assertThat(selectorAfterProcessing(environment)).isEqualTo("none");
        }
    }

    @Nested
    @DisplayName("a deployment")
    class Deployments {

        @Test
        @DisplayName("enables the model when a key is present — unchanged behaviour")
        void keyEnablesAi() {
            MockEnvironment environment = pinnedOff();
            environment.setActiveProfiles("postgres");
            environment.setProperty(API_KEY, "a-key-shaped-value");

            assertThat(selectorAfterProcessing(environment))
                    .describedAs("this is the whole point of the post-processor and must still work")
                    .isEqualTo("google-genai");
        }

        @Test
        @DisplayName("leaves the model off when no key is configured")
        void noKeyLeavesAiOff() {
            MockEnvironment environment = pinnedOff();
            environment.setActiveProfiles("postgres");

            assertThat(selectorAfterProcessing(environment)).isEqualTo("none");
        }

        @Test
        @DisplayName("leaves the model off when the key is blank rather than absent")
        void blankKeyLeavesAiOff() {
            MockEnvironment environment = pinnedOff();
            environment.setActiveProfiles("postgres");
            environment.setProperty(API_KEY, "   ");

            assertThat(selectorAfterProcessing(environment)).isEqualTo("none");
        }

        @Test
        @DisplayName("respects an operator who named a different provider")
        void explicitOperatorChoiceWins() {
            MockEnvironment environment = new MockEnvironment().withProperty(SELECTOR, "openai");
            environment.setActiveProfiles("postgres");
            environment.setProperty(API_KEY, "a-key-shaped-value");

            assertThat(selectorAfterProcessing(environment))
                    .describedAs("automatic enablement must not overrule a stated choice")
                    .isEqualTo("openai");
        }

        @Test
        @DisplayName("a profile merely containing the word test is not a test run")
        void similarlyNamedProfileIsNotATestRun() {
            MockEnvironment environment = pinnedOff();
            environment.setActiveProfiles("integration-testing");
            environment.setProperty(API_KEY, "a-key-shaped-value");

            assertThat(selectorAfterProcessing(environment))
                    .describedAs("the guard matches the profile exactly, not as a substring")
                    .isEqualTo("google-genai");
        }
    }
}
