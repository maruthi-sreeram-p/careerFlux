package com.careerflux.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import java.util.UUID;

import com.careerflux.security.JwtService;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * The source registry and the operational endpoints belong to the Portal Admin.
 *
 * <p>Both used to fall through to "anyone signed in": a student could list
 * every source with its policy record, including the staff who reviewed it, and
 * read the application's metrics.
 *
 * <p>Role names are the code's, not yet the product's (the role migration is a
 * later phase). {@code PLATFORM_ADMIN} is the Portal Admin. {@code
 * PLACEMENT_OFFICER} and {@code COLLEGE_ADMIN} are the two halves of the
 * product's Placement Coordinator, and the code's {@code PLACEMENT_COORDINATOR}
 * is the product's Department Coordinator.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OperationalEndpointAuthorizationIntegrationTest {

    private static final String[] SOURCE_READS = {
            "/api/sources",
            "/api/sources/stats",
            "/api/sources/adapters",
            "/api/sources/" + UUID.randomUUID()};

    private static final String[] OPERATIONAL_READS = {
            "/actuator/metrics",
            "/actuator/metrics/jvm.memory.used"};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TestInstitutions institutions;

    /** A signed-in account of the given role. College roles belong to a college. */
    private String signedIn(UserRole role) {
        User user = new User();
        user.setEmail(role.name().toLowerCase(Locale.ROOT).replace('_', '-') + "@example.com");
        user.setFullName("Operational endpoint " + role.name());
        user.setPasswordHash("not-used-because-this-test-issues-its-own-token");
        user.setRole(role);
        if (role != UserRole.PLATFORM_ADMIN) {
            user.setInstitution(institutions.example());
        }
        users.saveAndFlush(user);
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = UserRole.class,
            names = {"STUDENT", "PLACEMENT_COORDINATOR", "PLACEMENT_OFFICER", "COLLEGE_ADMIN"})
    @DisplayName("college accounts are refused the source registry")
    void collegeAccountsCannotReadSources(UserRole role) throws Exception {
        String token = signedIn(role);
        for (String path : SOURCE_READS) {
            mockMvc.perform(get(path).header("Authorization", token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("the Portal Admin can read the source registry")
    void portalAdminReadsSources() throws Exception {
        String token = signedIn(UserRole.PLATFORM_ADMIN);
        mockMvc.perform(get("/api/sources").header("Authorization", token)).andExpect(status().isOk());
        mockMvc.perform(get("/api/sources/stats").header("Authorization", token)).andExpect(status().isOk());
        mockMvc.perform(get("/api/sources/adapters").header("Authorization", token)).andExpect(status().isOk());
        // Past both guards: the answer is now about the source, not about access.
        mockMvc.perform(get("/api/sources/" + UUID.randomUUID()).header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an anonymous caller is asked to sign in")
    void anonymousCallersMustSignIn() throws Exception {
        for (String path : SOURCE_READS) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        for (String path : OPERATIONAL_READS) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = UserRole.class,
            names = {"STUDENT", "PLACEMENT_COORDINATOR", "PLACEMENT_OFFICER", "COLLEGE_ADMIN"})
    @DisplayName("college accounts are refused operational metrics")
    void collegeAccountsCannotReadMetrics(UserRole role) throws Exception {
        String token = signedIn(role);
        for (String path : OPERATIONAL_READS) {
            mockMvc.perform(get(path).header("Authorization", token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("the Portal Admin can read operational metrics")
    void portalAdminReadsMetrics() throws Exception {
        String token = signedIn(UserRole.PLATFORM_ADMIN);
        mockMvc.perform(get("/actuator/metrics").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
        mockMvc.perform(get("/actuator/metrics/jvm.memory.used").header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("endpoints describing the environment, beans or configuration are not published at all")
    void sensitiveEndpointsAreNotExposed() throws Exception {
        String token = signedIn(UserRole.PLATFORM_ADMIN);
        for (String path : new String[] {"/actuator/env", "/actuator/beans", "/actuator/configprops",
                "/actuator/loggers", "/actuator/mappings", "/actuator/threaddump", "/actuator/heapdump"}) {
            mockMvc.perform(get(path).header("Authorization", token))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("health and info answer anyone, and only the Portal Admin sees what health is made of")
    void healthDetailIsForThePortalAdmin() throws Exception {
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/health").header("Authorization", signedIn(UserRole.STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/health").header("Authorization", signedIn(UserRole.PLATFORM_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").exists());
    }
}
