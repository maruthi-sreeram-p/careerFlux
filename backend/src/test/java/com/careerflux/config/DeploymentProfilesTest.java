package com.careerflux.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * What each combination of profiles is allowed to do.
 *
 * <p>The failure this guards against was quiet: three classes each decided for
 * themselves that "no profile" meant development, so a deployment that forgot
 * SPRING_PROFILES_ACTIVE accepted the published JWT secret, opened the H2
 * console and returned reset tokens, and nothing in the log said so.
 */
class DeploymentProfilesTest {

    private static MockEnvironment profiles(String... active) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        return environment;
    }

    @Test
    @DisplayName("no profile is not development, even when Spring's default profile is dev")
    void noProfileIsNotDevelopment() {
        MockEnvironment environment = profiles();
        // What `spring.profiles.default: dev` used to arrange. A default is not a choice.
        environment.setDefaultProfiles("dev");

        assertThat(DeploymentProfiles.isDevelopment(environment)).isFalse();
        assertThat(DeploymentProfiles.mayDiscloseResetTokens(environment)).isFalse();
        assertThat(DeploymentProfiles.isProduction(environment)).isFalse();
    }

    @Test
    @DisplayName("only an explicitly named dev or test profile is development")
    void developmentIsExplicit() {
        assertThat(DeploymentProfiles.isDevelopment(profiles("dev"))).isTrue();
        assertThat(DeploymentProfiles.isDevelopment(profiles("test"))).isTrue();
        assertThat(DeploymentProfiles.isDevelopment(profiles("dev", "kafka"))).isTrue();

        assertThat(DeploymentProfiles.isDevelopment(profiles("postgres"))).isFalse();
        assertThat(DeploymentProfiles.isDevelopment(profiles("demo"))).isFalse();
        assertThat(DeploymentProfiles.isDevelopment(profiles("prod"))).isFalse();
    }

    @Test
    @DisplayName("prod is never development, even with a development profile beside it")
    void productionWins() {
        assertThat(DeploymentProfiles.isProduction(profiles("prod", "postgres"))).isTrue();
        assertThat(DeploymentProfiles.isDevelopment(profiles("prod", "dev"))).isFalse();
        assertThat(DeploymentProfiles.isDevelopment(profiles("prod", "test"))).isFalse();
    }

    @Test
    @DisplayName("a reset token may be disclosed under dev or demo, and never under prod")
    void resetTokenDisclosure() {
        assertThat(DeploymentProfiles.mayDiscloseResetTokens(profiles("dev"))).isTrue();
        assertThat(DeploymentProfiles.mayDiscloseResetTokens(profiles("demo"))).isTrue();
        assertThat(DeploymentProfiles.mayDiscloseResetTokens(profiles("postgres", "demo"))).isTrue();

        assertThat(DeploymentProfiles.mayDiscloseResetTokens(profiles("prod"))).isFalse();
        assertThat(DeploymentProfiles.mayDiscloseResetTokens(profiles("prod", "demo"))).isFalse();
        assertThat(DeploymentProfiles.mayDiscloseResetTokens(profiles("prod", "dev"))).isFalse();
        assertThat(DeploymentProfiles.mayDiscloseResetTokens(profiles("postgres"))).isFalse();
        assertThat(DeploymentProfiles.mayDiscloseResetTokens(profiles("test"))).isFalse();
    }
}
