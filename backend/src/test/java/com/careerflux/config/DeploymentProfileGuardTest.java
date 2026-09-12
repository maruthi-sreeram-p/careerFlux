package com.careerflux.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The startup guard, run directly against synthetic environments.
 *
 * <p>Nothing here needs a Spring context, a database or a real secret. The
 * values below are made up for this test and sign nothing.
 */
class DeploymentProfileGuardTest {

    private static final String REAL_SECRET = "a-genuinely-random-production-secret-value-0123456789";
    private static final String PUBLISHED_DEV_SECRET = "dev-only-insecure-secret-change-me-0123456789abcdef";
    private static final String DATABASE_PASSWORD = "a-database-password-made-up-for-this-test";

    private static final String SECRET = "careerflux.security.jwt.secret";
    private static final String URL = "spring.datasource.url";
    private static final String USERNAME = "spring.datasource.username";
    private static final String PASSWORD = "spring.datasource.password";
    private static final String ORIGINS = "careerflux.security.cors.allowed-origins";

    /** Every setting a deployment has to state, stated. */
    private static final Map<String, String> COMPLETE = Map.of(
            SECRET, REAL_SECRET,
            URL, "jdbc:postgresql://db.internal:5432/careerflux",
            USERNAME, "careerflux",
            PASSWORD, DATABASE_PASSWORD,
            ORIGINS, "https://careerflux.example.org");

    private final DeploymentProfileGuard guard = new DeploymentProfileGuard();

    private static MockEnvironment production(Map<String, String> settings) {
        MockEnvironment environment = new MockEnvironment();
        settings.forEach(environment::setProperty);
        environment.setActiveProfiles("prod", "postgres");
        return environment;
    }

    private static MockEnvironment productionWith(String key, String value) {
        Map<String, String> settings = new HashMap<>(COMPLETE);
        settings.put(key, value);
        return production(settings);
    }

    private static MockEnvironment productionWithout(String key) {
        Map<String, String> settings = new HashMap<>(COMPLETE);
        settings.remove(key);
        return production(settings);
    }

    private void process(MockEnvironment environment) {
        guard.postProcessEnvironment(environment, null);
    }

    @Test
    @DisplayName("without any profile, refuses to start instead of falling back to development")
    void noProfileIsRefused() {
        MockEnvironment environment = new MockEnvironment();
        // What `spring.profiles.default: dev` used to arrange.
        environment.setDefaultProfiles("dev");

        assertThatThrownBy(() -> process(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Spring profile is active")
                .hasMessageContaining("SPRING_PROFILES_ACTIVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "demo", "postgres", "postgres,kafka", "dev,demo", "test,demo"})
    @DisplayName("outside prod, starts without asking for production settings")
    void nonProductionProfilesStart(String profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles.split(","));

        assertThatCode(() -> process(environment)).doesNotThrowAnyException();
    }

    @Nested
    @DisplayName("under prod")
    class Production {

        @Test
        @DisplayName("starts when every required setting is stated")
        void completeConfigurationStarts() {
            assertThatCode(() -> process(production(COMPLETE))).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {"dev", "test", "demo"})
        @DisplayName("refuses to share a process with a development or demo profile")
        void refusesDevelopmentCompanions(String companion) {
            MockEnvironment environment = production(COMPLETE);
            environment.setActiveProfiles("prod", "postgres", companion);

            assertThatThrownBy(() -> process(environment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot be combined with " + companion);
        }

        @Test
        @DisplayName("refuses a missing signing secret")
        void missingSecret() {
            assertThatThrownBy(() -> process(productionWithout(SECRET)))
                    .hasMessageContaining("CAREERFLUX_JWT_SECRET is not set");
        }

        @Test
        @DisplayName("refuses the signing secret published in the repository, without repeating it")
        void publishedSecret() {
            assertThatThrownBy(() -> process(productionWith(SECRET, PUBLISHED_DEV_SECRET)))
                    .hasMessageContaining("published in the repository")
                    .satisfies(error -> assertThat(error.getMessage()).doesNotContain(PUBLISHED_DEV_SECRET));
        }

        @Test
        @DisplayName("refuses a signing secret too short to sign with")
        void shortSecret() {
            assertThatThrownBy(() -> process(productionWith(SECRET, "too-short")))
                    .hasMessageContaining("shorter than 32 characters");
        }

        @Test
        @DisplayName("refuses the postgres profile's localhost fallback as the database")
        void developmentDatabaseUrl() {
            assertThatThrownBy(() -> process(
                    productionWith(URL, DeploymentProfileGuard.DEVELOPMENT_DATABASE_URL)))
                    .hasMessageContaining("DATABASE_URL is not set");
        }

        @Test
        @DisplayName("refuses a missing database URL")
        void missingDatabaseUrl() {
            assertThatThrownBy(() -> process(productionWithout(URL)))
                    .hasMessageContaining("DATABASE_URL is not set");
        }

        @Test
        @DisplayName("refuses a database that is not PostgreSQL")
        void nonPostgresDatabase() {
            assertThatThrownBy(() -> process(productionWith(URL, "jdbc:h2:file:./data/careerflux")))
                    .hasMessageContaining("not a PostgreSQL JDBC URL");
        }

        @Test
        @DisplayName("treats a variable nobody set as missing rather than crashing on it")
        void unresolvedUsername() {
            assertThatThrownBy(() -> process(productionWith(USERNAME, "${DATABASE_USERNAME}")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DATABASE_USERNAME is not set");
        }

        @Test
        @DisplayName("refuses a blank database password")
        void blankPassword() {
            assertThatThrownBy(() -> process(productionWith(PASSWORD, "   ")))
                    .hasMessageContaining("DATABASE_PASSWORD is not set");
        }

        @Test
        @DisplayName("refuses missing browser origins")
        void missingOrigins() {
            assertThatThrownBy(() -> process(productionWithout(ORIGINS)))
                    .hasMessageContaining("CAREERFLUX_CORS_ORIGINS is not set");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://localhost:5173,http://127.0.0.1:5173",
                "*",
                "https://careerflux.example.org,http://localhost:5173",
                "http://[::1]:8081"})
        @DisplayName("refuses a wildcard or a local development origin")
        void developmentOrigins(String origins) {
            assertThatThrownBy(() -> process(productionWith(ORIGINS, origins)))
                    .hasMessageContaining("CAREERFLUX_CORS_ORIGINS must list the deployment's public origins");
        }

        @Test
        @DisplayName("names every problem at once and never echoes a value")
        void reportsEverythingWithoutValues() {
            MockEnvironment environment = production(Map.of(
                    SECRET, PUBLISHED_DEV_SECRET,
                    PASSWORD, DATABASE_PASSWORD,
                    ORIGINS, "http://localhost:5173"));

            assertThatThrownBy(() -> process(environment))
                    .hasMessageContaining("CAREERFLUX_JWT_SECRET")
                    .hasMessageContaining("DATABASE_URL")
                    .hasMessageContaining("DATABASE_USERNAME")
                    .hasMessageContaining("CAREERFLUX_CORS_ORIGINS")
                    .satisfies(error -> assertThat(error.getMessage())
                            .doesNotContain(PUBLISHED_DEV_SECRET)
                            .doesNotContain(DATABASE_PASSWORD)
                            .doesNotContain("localhost"));
        }
    }

    @Nested
    @DisplayName("wiring")
    class Wiring {

        @Test
        @DisplayName("is registered, so it runs on every start and not only in these tests")
        void registered() throws Exception {
            String factories = Files.readString(Path.of("src/main/resources/META-INF/spring.factories"));
            assertThat(factories).contains(DeploymentProfileGuard.class.getName());
        }

        @Test
        @DisplayName("guards exactly the database fallback the postgres profile ships")
        void databaseFallbackMatchesThePostgresProfile() throws Exception {
            String config = Files.readString(Path.of("src/main/resources/application-postgres.yml"));
            assertThat(config).contains(DeploymentProfileGuard.DEVELOPMENT_DATABASE_URL);
        }

        @Test
        @DisplayName("the base configuration names no default profile, and prod brings postgres")
        void baseConfiguration() throws Exception {
            String config = Files.readString(Path.of("src/main/resources/application.yml"));
            assertThat(config).doesNotContainPattern("(?m)^\\s*default:\\s*dev\\s*$");
            assertThat(config).containsPattern("(?m)^\\s*prod:\\s*postgres\\s*$");
        }
    }
}
