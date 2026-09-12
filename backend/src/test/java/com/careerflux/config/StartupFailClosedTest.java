package com.careerflux.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careerflux.CareerFluxApplication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

/**
 * The guard as a real start meets it: the application's own configuration
 * files, its spring.factories registration and its profile groups, nothing
 * mocked.
 *
 * <p>Every start here fails while the environment is being prepared, before any
 * bean exists, so none of them opens a database or binds a port.
 */
class StartupFailClosedTest {

    /**
     * An environment without the machine's variables or JVM properties, so a
     * SPRING_PROFILES_ACTIVE or CAREERFLUX_JWT_SECRET exported wherever the build
     * runs cannot change what these tests observe.
     */
    private static ConfigurableEnvironment isolated() {
        return new StandardEnvironment() {
            @Override
            protected void customizePropertySources(MutablePropertySources propertySources) {
            }
        };
    }

    private static void start(String... args) {
        SpringApplication application = new SpringApplication(CareerFluxApplication.class);
        application.setEnvironment(isolated());
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }

    @Test
    @DisplayName("refuses to start with no profile, where it used to become dev")
    void noProfile() {
        assertThatThrownBy(() -> start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Spring profile is active");
    }

    @Test
    @DisplayName("refuses prod while its settings are still the development defaults")
    void productionOnDevelopmentDefaults() {
        assertThatThrownBy(() -> start("--spring.profiles.active=prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start under the 'prod' profile")
                .hasMessageContaining("CAREERFLUX_JWT_SECRET")
                .hasMessageContaining("DATABASE_URL")
                .hasMessageContaining("DATABASE_USERNAME")
                .hasMessageContaining("DATABASE_PASSWORD")
                .hasMessageContaining("CAREERFLUX_CORS_ORIGINS");
    }

    @Test
    @DisplayName("refuses prod alongside the demo profile")
    void productionWithDemo() {
        assertThatThrownBy(() -> start("--spring.profiles.active=prod,demo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be combined with demo");
    }
}
