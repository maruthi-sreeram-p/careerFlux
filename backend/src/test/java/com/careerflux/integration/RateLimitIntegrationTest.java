package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.careerflux.config.CareerFluxProperties;
import com.careerflux.security.ratelimit.RateLimitedAction;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * The limiter as a client meets it: real filter chain, real controller, real
 * status codes and headers.
 *
 * <p>{@link com.careerflux.security.ratelimit.RateLimiterTest} proves the
 * counting. This proves the wiring, which is the part that silently does
 * nothing when it is wrong: a filter registered in the wrong order, a limit that
 * never reaches an endpoint, an exception thrown somewhere no handler can see it
 * and surfacing as a 500.
 *
 * <p>The sign-in ceiling is lowered to three here so the case is short and
 * obvious. The shipped numbers are asserted separately, against the values
 * actually bound from {@code application.yml}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
        "careerflux.rate-limit.login-attempts=3",
        "careerflux.rate-limit.login-window=PT5M",
        "careerflux.rate-limit.discovery-requests=3",
        "careerflux.rate-limit.discovery-window=PT1M",
        "careerflux.rate-limit.shortlist-mutations=3",
        "careerflux.rate-limit.shortlist-window=PT1M",
        "careerflux.rate-limit.requirement-creations=3",
        "careerflux.rate-limit.requirement-window=PT1H"
})
class RateLimitIntegrationTest {

    private static final String PASSWORD = "IntegrationTest123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    @Autowired
    private CareerFluxProperties properties;

    // ------------------------------------------------------------ sign-in

    @Test
    @DisplayName("a burst of wrong passwords is refused with 429 and a Retry-After")
    void loginIsThrottled() throws Exception {
        seedStudent("throttled@example.com");

        // Wrong password: 403 by the application's own convention for a failed
        // sign-in. These are the attempts a guesser makes.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(loginWith("throttled@example.com", "WrongPassword" + i))
                    .andExpect(status().isForbidden());
        }

        mockMvc.perform(loginWith("throttled@example.com", "WrongPasswordAgain"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    @DisplayName("the ceiling holds once reached, so the correct password does not slip through either")
    void throttlingSurvivesACorrectPassword() throws Exception {
        // The limiter runs before the password is checked. A guesser who lands
        // on the right password on attempt four is still refused, which is the
        // whole point of charging before authenticating rather than after.
        seedStudent("correct-after-burst@example.com");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(loginWith("correct-after-burst@example.com", "Wrong" + i))
                    .andExpect(status().isForbidden());
        }

        mockMvc.perform(loginWith("correct-after-burst@example.com", PASSWORD))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("spelling the address differently does not buy more attempts")
    void loginIdentityCannotBeVariedToEscape() throws Exception {
        seedStudent("normalised@example.com");

        mockMvc.perform(loginWith("normalised@example.com", "Wrong1"))
                .andExpect(status().isForbidden());
        mockMvc.perform(loginWith("NORMALISED@example.com", "Wrong2"))
                .andExpect(status().isForbidden());
        mockMvc.perform(loginWith("Normalised@Example.com", "Wrong3"))
                .andExpect(status().isForbidden());

        mockMvc.perform(loginWith("nOrMaLiSeD@EXAMPLE.com", "Wrong4"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("a throttled sign-in says the same thing for a real account and an invented one")
    void throttlingDoesNotRevealWhetherAnAccountExists() throws Exception {
        seedStudent("real-account@example.com");

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(loginWith("real-account@example.com", "Wrong" + i));
        }
        String forReal = mockMvc.perform(loginWith("real-account@example.com", "Wrong"))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(loginWith("no-such-account@example.com", "Wrong" + i));
        }
        String forInvented = mockMvc.perform(loginWith("no-such-account@example.com", "Wrong"))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();

        assertThat(bodyWithoutTimestamps(forReal))
                .describedAs("a refusal must not become an account-existence oracle")
                .isEqualTo(bodyWithoutTimestamps(forInvented).replace("no-such-account", "real-account"));
        assertThat(forReal).doesNotContain("real-account@example.com");
    }

    @Test
    @DisplayName("one address being throttled does not throttle the next student on the same network")
    void oneStudentDoesNotLockOutTheCollege() throws Exception {
        // Every student on campus wifi shares an address. A limiter keyed on the
        // address alone would take the whole college offline the moment one
        // person fumbled their password, which is a worse outage than the attack
        // it prevents.
        seedStudent("first-student@example.com");
        seedStudent("second-student@example.com");

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(loginWith("first-student@example.com", "Wrong" + i));
        }
        mockMvc.perform(loginWith("first-student@example.com", PASSWORD))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(loginWith("second-student@example.com", PASSWORD))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------- ordinary use is unaffected

    @Test
    @DisplayName("ordinary use is nowhere near a ceiling")
    void legitimateTrafficIsUnaffected() throws Exception {
        // A student signing in and reading their own screens, as they would on
        // any morning. Nothing here should ever meet a limit.
        seedStudent("ordinary@example.com");
        String token = signIn("ordinary@example.com");

        for (int i = 0; i < 12; i++) {
            mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("reading a shortlist or a requirement list is not charged as a mutation")
    void readsAreNotChargedAsWrites() throws Exception {
        // Only the write paths carry the shortlist ceiling. Charging the read
        // would throttle a placement officer for scrolling.
        seedStudent("reader@example.com");
        String token = signIn("reader@example.com");

        for (int i = 0; i < 40; i++) {
            int read = i + 1;
            mockMvc.perform(get("/api/requirements").header("Authorization", "Bearer " + token))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .describedAs("read %d must not be rate limited", read)
                            .isNotEqualTo(429));
        }
    }

    // ----------------------------------------------------------- unauthenticated

    @Test
    @DisplayName("an unauthenticated call to a limited endpoint is still a 401, not a 429")
    void unauthenticatedRequestsAreNotCharged() throws Exception {
        // The filter passes anonymous requests through to the security chain.
        // Turning a 401 into a 429 would tell an unauthenticated caller that the
        // endpoint exists and is worth attacking.
        for (int i = 0; i < 40; i++) {
            mockMvc.perform(get("/api/requirements/" + UUID.randomUUID() + "/candidates"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ------------------------------------------------------------ configuration

    @Test
    @DisplayName("AI cost is still governed by the AI quota and not by a second ceiling")
    void aiKeepsItsOwnQuota() {
        // The brief for this work was explicit that AI must not acquire a
        // competing quota. The limited set is closed and none of it is an AI
        // ceiling: resume upload appears because 8 MB files land on disk whether
        // or not any AI runs, and exhausting the AI quota falls back to the
        // heuristic parser rather than failing the upload.
        assertThat(RateLimitedAction.values())
                .containsExactlyInAnyOrder(
                        RateLimitedAction.LOGIN,
                        RateLimitedAction.RESUME_UPLOAD,
                        RateLimitedAction.CANDIDATE_DISCOVERY,
                        RateLimitedAction.REQUIREMENT_CREATE,
                        RateLimitedAction.SHORTLIST_MUTATION);
    }

    // ---------------------------------------------------------------- routing

    @Test
    @DisplayName("candidate discovery is charged, matching a path with a wildcard in it")
    void discoveryIsCharged() throws Exception {
        // Charged in the filter, which runs before the controller -- so this
        // holds even though the requirement id is invented and the request would
        // never have found anything. That is the point: the work being bounded
        // is the request arriving, not the request succeeding.
        seedStudent("discovery-limited@example.com");
        String token = signIn("discovery-limited@example.com");
        String path = "/api/requirements/" + UUID.randomUUID() + "/candidates";

        for (int i = 0; i < 3; i++) {
            int attempt = i + 1;
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .describedAs("attempt %d is within the allowance", attempt)
                            .isNotEqualTo(429));
        }

        mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("creating a requirement is charged on an exact path")
    void requirementCreationIsCharged() throws Exception {
        seedStudent("requirement-limited@example.com");
        String token = signIn("requirement-limited@example.com");

        for (int i = 0; i < 3; i++) {
            int attempt = i + 1;
            mockMvc.perform(post("/api/requirements")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .describedAs("attempt %d is within the allowance", attempt)
                            .isNotEqualTo(429));
        }

        mockMvc.perform(post("/api/requirements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("withdrawing from a shortlist is charged, matching a path with two wildcards")
    void shortlistWithdrawalIsCharged() throws Exception {
        seedStudent("shortlist-limited@example.com");
        String token = signIn("shortlist-limited@example.com");
        String path = "/api/requirements/" + UUID.randomUUID()
                + "/shortlist/" + UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            int attempt = i + 1;
            mockMvc.perform(delete(path).header("Authorization", "Bearer " + token))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .describedAs("attempt %d is within the allowance", attempt)
                            .isNotEqualTo(429));
        }

        mockMvc.perform(delete(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("one account reaching a ceiling does not throttle another")
    void ceilingsAreChargedPerAccount() throws Exception {
        seedStudent("busy-officer@example.com");
        seedStudent("quiet-officer@example.com");
        String busy = signIn("busy-officer@example.com");
        String quiet = signIn("quiet-officer@example.com");
        String path = "/api/requirements/" + UUID.randomUUID() + "/candidates";

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + busy));
        }
        mockMvc.perform(get(path).header("Authorization", "Bearer " + busy))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(get(path).header("Authorization", "Bearer " + quiet))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .describedAs("a second account must be unaffected")
                        .isNotEqualTo(429));
    }

    // --------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginWith(
            String email, String password) throws Exception {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        java.util.Map.of("email", email, "password", password)));
    }

    private String signIn(String email) throws Exception {
        String body = mockMvc.perform(loginWith(email, PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private void seedStudent(String email) {
        User student = new User();
        student.setEmail(email);
        student.setFullName("Rate Limit Subject");
        student.setPasswordHash(passwordEncoder.encode(PASSWORD));
        student.setRole(UserRole.STUDENT);
        student.setStatus(UserStatus.ACTIVE);
        student.setInstitution(institutions.example());
        student.setDepartment(institutions.exampleCse());
        userRepository.saveAndFlush(student);
    }

    /** Strips the parts that legitimately differ between two responses. */
    private static String bodyWithoutTimestamps(String json) {
        return json.replaceAll("\"timestamp\":\"[^\"]+\"", "\"timestamp\":\"-\"");
    }
}
