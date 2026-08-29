package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.OnboardingStage;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.requirement.repository.CompanyRequirementRepository;
import com.careerflux.shortlist.repository.ShortlistRepository;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
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
 * Two people, or two clicks, shortlisting the same student at the same moment.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}. The rest of the shortlist
 * tests run inside a transaction that rolls back, which is right for them and
 * useless here: threads outside that transaction cannot see its uncommitted
 * rows, so the race would never reach the code being tested. This class commits
 * its data and cleans up afterwards instead.
 *
 * <p>What is being proven is that the guarantee lives in the database. The
 * service checks for an existing entry before inserting, and that check is a
 * read followed by a write — two requests can both pass it. The unique
 * constraint is what actually holds, and the service turns the resulting
 * collision into the same conflict the loser would have been told a moment
 * earlier. A disabled button in a browser is not a guarantee.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:shortlist-race;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
class ShortlistConcurrencyTest {

    private static final String PASSWORD = "IntegrationTest123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandidateProfileRepository profileRepository;

    @Autowired
    private CandidateProfileService profileService;

    @Autowired
    private ShortlistRepository shortlists;

    @Autowired
    private CompanyRequirementRepository requirements;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    @AfterEach
    void cleanUp() {
        // Committed data, so it has to be removed by hand.
        shortlists.deleteAll();
        requirements.deleteAll();
        profileRepository.deleteAll();
        userRepository.findByEmailIgnoreCase("race-officer@example.com")
                .ifPresent(userRepository::delete);
        userRepository.findByEmailIgnoreCase("race-student@example.com")
                .ifPresent(userRepository::delete);
    }

    @Test
    @DisplayName("two simultaneous requests leave exactly one shortlist row")
    void concurrentShortlistLeavesOneRow() throws Exception {
        String officer = seedOfficer();
        UUID candidate = seedCandidate();
        String requirementId = seedOpenRequirement(officer);

        // Both threads wait here, so they are released together rather than one
        // finishing before the other starts.
        CyclicBarrier startTogether = new CyclicBarrier(2);
        Callable<Integer> attempt = () -> {
            startTogether.await();
            return mockMvc.perform(post("/api/requirements/" + requirementId + "/shortlist")
                            .header("Authorization", "Bearer " + officer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"candidateId\":\"%s\"}".formatted(candidate)))
                    .andReturn().getResponse().getStatus();
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = pool.invokeAll(List.of(attempt, attempt));
            List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());

            // One wins. The other is told the candidate is already there — never
            // a second row, and never a 500 leaking a constraint violation.
            assertThat(statuses).contains(201);
            assertThat(statuses).allSatisfy(code -> assertThat(code).isIn(201, 409));
            assertThat(shortlists.countByRequirementId(UUID.fromString(requirementId)))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // --------------------------------------------------------------- helpers

    private String seedOfficer() throws Exception {
        User officer = new User();
        officer.setEmail("race-officer@example.com");
        officer.setFullName("Race Officer");
        officer.setPasswordHash(passwordEncoder.encode(PASSWORD));
        officer.setRole(UserRole.PLACEMENT_OFFICER);
        officer.setStatus(UserStatus.ACTIVE);
        officer.setInstitution(institutions.example());
        userRepository.saveAndFlush(officer);

        return objectMapper.readTree(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"race-officer@example.com","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("accessToken").asText();
    }

    private UUID seedCandidate() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"race-student@example.com","password":"%s",
                                 "fullName":"Race Student"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isCreated());

        User user = userRepository.findByEmailIgnoreCase("race-student@example.com").orElseThrow();
        user.setDepartment(institutions.exampleCse());
        user.setBatch(institutions.exampleBatch2027());
        userRepository.saveAndFlush(user);

        CandidateProfile profile = profileRepository.findByUserId(user.getId())
                .orElseGet(() -> profileService.createForUser(user));
        profile.setPrimaryRole("Backend Developer");
        profile.setSeniority(Seniority.JUNIOR);
        profile.setYearsExperience(BigDecimal.valueOf(2));
        profile.setLocation("Hyderabad, India");
        profile.setOnboardingStage(OnboardingStage.COMPLETE);
        // No skills. This test is about the race, and scope membership is
        // decided by department and batch — touching the lazy skill collection
        // outside a transaction would only add a failure unrelated to the race.
        return profileRepository.saveAndFlush(profile).getId();
    }

    private String seedOpenRequirement(String officer) throws Exception {
        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/api/requirements")
                        .header("Authorization", "Bearer " + officer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyName":"Race Systems","roleTitle":"Java Backend Developer",
                                 "skills":[{"skill":"Java","tier":"REQUIRED"}]}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        String id = created.get("id").asText();
        mockMvc.perform(patch("/api/requirements/" + id)
                        .header("Authorization", "Bearer " + officer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isOk());
        return id;
    }
}
