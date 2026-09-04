package com.careerflux.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import com.careerflux.config.CareerFluxProperties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The startup checks that stand between a forgotten environment variable and a
 * production system signing real sessions with a public key.
 *
 * <p>The secret in {@code application.yml} is a convenience for local work and
 * is visible to anyone who can read this repository. Before this, only its
 * length was checked — and it is fifty characters long, so it passed. A
 * deployment that simply never set {@code CAREERFLUX_JWT_SECRET} would start
 * perfectly happily and issue forgeable tokens for every user of every college.
 */
class ProductionHardeningTest {

    private static final String PUBLISHED_DEV_SECRET =
            "dev-only-insecure-secret-change-me-0123456789abcdef";
    private static final String REAL_SECRET =
            "a-genuinely-random-production-secret-value-0123456789";

    private static CareerFluxProperties propertiesWith(String secret) {
        return new CareerFluxProperties(
                new CareerFluxProperties.Security(
                        new CareerFluxProperties.Jwt(secret, Duration.ofHours(2), Duration.ofDays(14)),
                        new CareerFluxProperties.Cors(java.util.List.of("http://localhost:5173"))),
                null, null, null, null, null,
                null /* rate limits: not exercised here */,
                true /* background work: not exercised here */);
    }

    private static MockEnvironment profiles(String... active) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        return environment;
    }

    @Nested
    @DisplayName("the published development secret")
    class PublishedSecret {

        @Test
        @DisplayName("is refused under a deployment profile, with a message that says what to do")
        void refusedInProduction() {
            assertThatThrownBy(() ->
                    new JwtService(propertiesWith(PUBLISHED_DEV_SECRET), profiles("postgres")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("development value")
                    .hasMessageContaining("openssl rand");
        }

        @Test
        @DisplayName("is refused under any profile that is not dev or test")
        void refusedUnderOtherProfiles() {
            for (String profile : new String[] {"postgres", "kafka", "prod", "staging"}) {
                assertThatThrownBy(() ->
                        new JwtService(propertiesWith(PUBLISHED_DEV_SECRET), profiles(profile)))
                        .describedAs("profile %s", profile)
                        .isInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        @DisplayName("stays usable for local work, which is the only reason it exists")
        void allowedInDevelopment() {
            assertThatCode(() -> new JwtService(propertiesWith(PUBLISHED_DEV_SECRET), profiles("dev")))
                    .doesNotThrowAnyException();
            assertThatCode(() -> new JwtService(propertiesWith(PUBLISHED_DEV_SECRET), profiles("test")))
                    .doesNotThrowAnyException();
            // No profile named at all falls back to dev.
            assertThatCode(() -> new JwtService(propertiesWith(PUBLISHED_DEV_SECRET), profiles()))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("a real secret")
    class RealSecret {

        @Test
        @DisplayName("is accepted everywhere, including production")
        void acceptedEverywhere() {
            assertThatCode(() -> new JwtService(propertiesWith(REAL_SECRET), profiles("postgres")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("must still be long enough to sign with")
        void lengthIsStillChecked() {
            assertThatThrownBy(() -> new JwtService(propertiesWith("too-short"), profiles("postgres")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32 characters");
        }
    }

    @Test
    @DisplayName("the refusal names the variable an operator has to set")
    void messageIsActionable() {
        assertThatThrownBy(() ->
                new JwtService(propertiesWith(PUBLISHED_DEV_SECRET), profiles("postgres")))
                .hasMessageContaining("CAREERFLUX_JWT_SECRET");
    }

    @Test
    @DisplayName("the value in application.yml is exactly the one being guarded against")
    void theGuardMatchesTheShippedDefault() throws Exception {
        // If somebody changes the fallback in application.yml without changing
        // the constant, the guard silently stops guarding. This ties the two
        // together so that drift fails a test rather than a pilot.
        String config = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/application.yml"));
        assertThat(config).contains(PUBLISHED_DEV_SECRET);
    }
}
