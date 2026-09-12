package com.careerflux.config;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.springframework.core.env.Environment;

/**
 * What the active Spring profiles mean for safety, decided in one place.
 *
 * <p>Three classes used to work this out for themselves, and each treated "no
 * profile at all" as development: the published JWT secret was accepted, the H2
 * console and API documentation were opened, and password-reset tokens were
 * returned in responses. Only profiles that are actually <em>active</em> count
 * here. Spring's default profiles never do, so a forgotten variable can no longer
 * turn a deployment into a development machine.
 */
public final class DeploymentProfiles {

    /** The profile a real deployment runs under, enforced by {@link DeploymentProfileGuard}. */
    public static final String PRODUCTION = "prod";

    /** Where the published JWT fallback and open developer tooling are acceptable. */
    static final Set<String> DEVELOPMENT = Set.of("dev", "test");

    /**
     * Where a password-reset token may be returned in the response. There is no
     * email delivery yet, so a developer or a demonstration has no other way to
     * finish a reset.
     */
    static final Set<String> RESET_TOKEN_DISCLOSURE = Set.of("dev", "demo");

    /** Profiles whose shortcuts must never share a process with production. */
    static final Set<String> NEVER_WITH_PRODUCTION = Set.of("dev", "test", "demo");

    private DeploymentProfiles() {
    }

    public static boolean isProduction(Environment environment) {
        return active(environment).contains(PRODUCTION);
    }

    /** Explicitly dev or test, and not production. */
    public static boolean isDevelopment(Environment environment) {
        return !isProduction(environment) && active(environment).stream().anyMatch(DEVELOPMENT::contains);
    }

    /** Explicitly dev or demo, and never production, whatever else is active. */
    public static boolean mayDiscloseResetTokens(Environment environment) {
        return !isProduction(environment)
                && active(environment).stream().anyMatch(RESET_TOKEN_DISCLOSURE::contains);
    }

    private static List<String> active(Environment environment) {
        return Arrays.asList(environment.getActiveProfiles());
    }
}
