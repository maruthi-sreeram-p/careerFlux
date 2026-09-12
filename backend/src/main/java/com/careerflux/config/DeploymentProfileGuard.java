package com.careerflux.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.careerflux.security.JwtService;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

/**
 * Refuses to start CareerFlux in a configuration that is not clearly one thing.
 *
 * <p>It runs while the environment is being prepared, before the application
 * context exists, so a refusal happens before a database is opened, a migration
 * is applied or a port is bound.
 *
 * <ul>
 *   <li><b>No profile is refused.</b> Starting without one used to mean
 *       {@code dev}: an H2 file database, the published JWT secret and an open
 *       database console, with nothing in the log to say a deployment had
 *       forgotten {@code SPRING_PROFILES_ACTIVE}.</li>
 *   <li><b>{@code prod} runs alone.</b> It cannot share a process with
 *       {@code dev}, {@code test} or {@code demo}, whose shortcuts (the published
 *       secret, open developer tooling, reset tokens in responses, demo accounts)
 *       must never reach a real deployment.</li>
 *   <li><b>{@code prod} requires its settings to be stated.</b> The signing
 *       secret, the database connection and the browser origins all have
 *       development defaults. Under {@code prod} a default is refused rather than
 *       used.</li>
 * </ul>
 *
 * <p>A refusal names the settings to fix and never echoes their values.
 */
public class DeploymentProfileGuard implements EnvironmentPostProcessor {

    /**
     * The fallback in {@code application-postgres.yml}. Right for a laptop; a
     * deployment has to name its own database.
     */
    static final String DEVELOPMENT_DATABASE_URL = "jdbc:postgresql://localhost:5432/careerflux";

    private static final int MINIMUM_SECRET_BYTES = 32;

    /** Hosts that only ever mean "this machine". */
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "0.0.0.0");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) {
            throw new IllegalStateException("No Spring profile is active, and CareerFlux no longer falls back "
                    + "to a development configuration. Set SPRING_PROFILES_ACTIVE to 'dev' for local "
                    + "development or 'prod' for a deployment.");
        }
        if (!DeploymentProfiles.isProduction(environment)) {
            return;
        }

        List<String> problems = new ArrayList<>();
        List<String> conflicting = Arrays.stream(active)
                .filter(DeploymentProfiles.NEVER_WITH_PRODUCTION::contains)
                .toList();
        if (!conflicting.isEmpty()) {
            problems.add("the 'prod' profile cannot be combined with " + String.join(", ", conflicting));
        }
        checkSigningSecret(environment, problems);
        checkDatabase(environment, problems);
        checkBrowserOrigins(environment, problems);

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start under the 'prod' profile: " + String.join("; ", problems) + ".");
        }
    }

    private static void checkSigningSecret(ConfigurableEnvironment environment, List<String> problems) {
        String secret = setting(environment, "careerflux.security.jwt.secret");
        if (secret == null) {
            problems.add("CAREERFLUX_JWT_SECRET is not set");
        } else if (JwtService.isPublishedDevelopmentSecret(secret)) {
            problems.add("CAREERFLUX_JWT_SECRET is the development value published in the repository");
        } else if (secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
            problems.add("CAREERFLUX_JWT_SECRET is shorter than " + MINIMUM_SECRET_BYTES + " characters");
        }
    }

    private static void checkDatabase(ConfigurableEnvironment environment, List<String> problems) {
        String url = setting(environment, "spring.datasource.url");
        if (url == null || DEVELOPMENT_DATABASE_URL.equals(url)) {
            problems.add("DATABASE_URL is not set");
        } else if (!url.startsWith("jdbc:postgresql:")) {
            problems.add("DATABASE_URL is not a PostgreSQL JDBC URL");
        }
        if (setting(environment, "spring.datasource.username") == null) {
            problems.add("DATABASE_USERNAME is not set");
        }
        if (setting(environment, "spring.datasource.password") == null) {
            problems.add("DATABASE_PASSWORD is not set");
        }
    }

    private static void checkBrowserOrigins(ConfigurableEnvironment environment, List<String> problems) {
        String origins = setting(environment, "careerflux.security.cors.allowed-origins");
        if (origins == null) {
            problems.add("CAREERFLUX_CORS_ORIGINS is not set");
            return;
        }
        for (String origin : origins.split(",")) {
            if (!origin.isBlank() && !isPublicOrigin(origin.strip())) {
                problems.add("CAREERFLUX_CORS_ORIGINS must list the deployment's public origins, "
                        + "not '*' or a local development address");
                return;
            }
        }
    }

    private static boolean isPublicOrigin(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return host != null && !LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /** A setting's value; null when it is absent, blank, or refers to a variable nobody set. */
    private static String setting(ConfigurableEnvironment environment, String key) {
        try {
            String value = environment.getProperty(key);
            return StringUtils.hasText(value) ? value.strip() : null;
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return null;
        }
    }
}
