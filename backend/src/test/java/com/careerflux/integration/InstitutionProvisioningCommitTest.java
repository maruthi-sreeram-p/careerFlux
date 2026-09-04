package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.careerflux.institution.repository.InstitutionRepository;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
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

/**
 * The two onboarding guarantees that only hold once data is actually committed.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}, for two different reasons
 * that happen to have the same fix.
 *
 * <p><b>Rollback.</b> Inside a shared test transaction the service's rollback is
 * invisible: its transaction joins the test's, nothing is committed or undone
 * until the test ends, and a query after the failure still sees the flushed row.
 * The first version of this test lived in the transactional class and failed for
 * exactly that reason — it was asserting something real about production that
 * the harness made unobservable.
 *
 * <p><b>Concurrency.</b> A second thread cannot see uncommitted rows at all, so
 * the collision the test exists to force would never happen.
 *
 * <p>This class commits, and cleans up after itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:institution-onboarding;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
class InstitutionProvisioningCommitTest {

    private static final String PASSWORD = "IntegrationTest123!";
    private static final String PATH = "/api/admin/institutions";
    private static final String OPERATOR = "commit-operator@careerflux.test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InstitutionRepository institutionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private com.careerflux.support.TestInstitutions institutions;

    @AfterEach
    void cleanUp() {
        // Committed data, so it has to go by hand. Users first: they point at
        // institutions.
        for (String email : List.of(OPERATOR, "clash@rollbackcollege.edu", "already-taken@example.com")) {
            userRepository.findByEmailIgnoreCase(email).ifPresent(userRepository::delete);
        }
        for (String slug : List.of("rollback-college", "race-college")) {
            institutionRepository.findBySlug(slug).ifPresent(institutionRepository::delete);
        }
    }

    @Test
    @DisplayName("a college is not left behind when its first administrator cannot be created")
    void failedAdminLeavesNoInstitution() throws Exception {
        // The institution is saved and flushed before the administrator is
        // built, so without a transaction spanning both, a duplicate email would
        // leave a college that nobody can sign in to and that blocks its own
        // name and domain from being used again.
        String token = signIn(seedOperator());
        seedTakenAddress("already-taken@example.com");

        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Rollback College","emailDomains":"rollbackcollege.edu",
                                 "initialAdmin":{"fullName":"Clash Name",
                                                 "email":"already-taken@example.com",
                                                 "password":"RollbackAdmin!2026"}}
                                """))
                .andExpect(status().isConflict());

        assertThat(institutionRepository.findBySlug("rollback-college"))
                .describedAs("the college must not survive the administrator that failed")
                .isEmpty();
    }

    @Test
    @DisplayName("the same college onboarded twice at once leaves exactly one")
    void concurrentOnboardingLeavesOneCollege() throws Exception {
        // Two operators, or one operator and an impatient second click. Both
        // requests pass the existence check — it is a read followed by a write —
        // so the unique index on the slug is what actually decides, and the
        // service turns the collision into the same conflict the loser would
        // have been told a moment earlier.
        String token = signIn(seedOperator());

        CyclicBarrier startTogether = new CyclicBarrier(2);
        Callable<Integer> attempt = () -> {
            startTogether.await();
            return mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Race College","emailDomains":"racecollege.edu"}
                                    """))
                    .andReturn().getResponse().getStatus();
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = pool.invokeAll(List.of(attempt, attempt));
            List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());

            assertThat(statuses).contains(201);
            assertThat(statuses)
                    .describedAs("one wins, the other is told it already exists — never a 500 "
                            + "leaking a constraint violation")
                    .allSatisfy(code -> assertThat(code).isIn(201, 409));
            assertThat(institutionRepository.findAll().stream()
                    .filter(institution -> "race-college".equals(institution.getSlug())))
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // --------------------------------------------------------------- helpers

    private User seedOperator() {
        return userRepository.findByEmailIgnoreCase(OPERATOR).orElseGet(() -> {
            User operator = new User();
            operator.setEmail(OPERATOR);
            operator.setFullName("Commit Operator");
            operator.setPasswordHash(passwordEncoder.encode(PASSWORD));
            operator.setRole(UserRole.PLATFORM_ADMIN);
            operator.setStatus(UserStatus.ACTIVE);
            return userRepository.saveAndFlush(operator);
        });
    }

    private void seedTakenAddress(String email) {
        if (userRepository.findByEmailIgnoreCase(email).isEmpty()) {
            User existing = new User();
            existing.setEmail(email);
            existing.setFullName("Already Here");
            existing.setPasswordHash(passwordEncoder.encode(PASSWORD));
            existing.setRole(UserRole.STUDENT);
            existing.setStatus(UserStatus.ACTIVE);
            // ck_users_institution_required: an institutional role must belong
            // to a college. Only the platform operator may have none.
            existing.setInstitution(institutions.example());
            userRepository.saveAndFlush(existing);
        }
    }

    private String signIn(User user) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
