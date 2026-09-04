package com.careerflux.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.careerflux.ingestion.domain.IngestionTrigger;
import com.careerflux.ingestion.service.IngestionService;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.source.adapter.LocalFixtureAdapter;
import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.DiscoveryMethod;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.RobotsStatus;
import com.careerflux.source.domain.SourceAccessPolicy;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.domain.TosStatus;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.support.IngestionTestData;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * An administrator has no candidate profile.
 *
 * <p>Every catalogue read has to cope with that, because an operator browsing
 * the corpus is a normal thing to do. This started as a real bug: the job detail
 * page returned 404 for an admin, because recording the page view required a
 * profile and threw when there was none.
 *
 * <p>Personal routes are the other half of the rule. Searching the corpus is
 * something an operator legitimately does; "my saved jobs" is not, and those
 * routes refuse rather than inventing an empty answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminBrowsingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JobSourceRepository sourceRepository;

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private JobRepository jobRepository;

    private String adminToken;
    private String jobId;


    /** Kept so the cleanup below can find what this test ingested. */
    private JobSource ingestedSource;

    @Autowired
    private IngestionTestData testData;

    /**
     * Effective only once ingestion commits independently.
     *
     * <p>This class isolates itself by rolling back, which covers everything
     * ingestion writes today because it all joins the test's transaction. When
     * the fetch moves out of that transaction the ingested rows will commit on
     * their own and the rollback will stop reaching them. The cleanup runs in its
     * own transaction, so it removes exactly those and cannot see — or disturb —
     * the rows the rollback still owns.
     */
    @AfterEach
    void removeAnythingIngestionCommittedOnItsOwn() {
        if (ingestedSource != null) {
            testData.deleteSource(ingestedSource.getId());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        User admin = new User();
        admin.setEmail("browsing-admin@example.com");
        admin.setFullName("Operator");
        admin.setPasswordHash(passwordEncoder.encode("AdminPassword123!"));
        admin.setRole(UserRole.PLATFORM_ADMIN);
        userRepository.saveAndFlush(admin);

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"browsing-admin@example.com","password":"AdminPassword123!"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        adminToken = objectMapper.readTree(response).get("accessToken").asText();

        JobSource source = new JobSource();
        source.setName("Fixture feed");
        source.setBaseUrl("local://test/admin-browsing-" + System.nanoTime());
        source.setSourceType(SourceType.LOCAL_FIXTURE);
        source.setAtsProvider(AtsProvider.NONE);
        source.setAdapterKey(LocalFixtureAdapter.KEY);
        source.setExternalIdentifier("sample-employers");
        source.setDiscoveryMethod(DiscoveryMethod.SEED);
        source.setRateLimitPerMinute(600);
        source.setDiscoveredAt(Instant.now());
        source.setState(SourceState.ACTIVE);
        source.setStateChangedAt(Instant.now());

        SourceAccessPolicy policy = new SourceAccessPolicy();
        policy.setRobotsStatus(RobotsStatus.NOT_PUBLISHED);
        policy.setTosStatus(TosStatus.PERMITTED);
        policy.setTosReviewedBy("test");
        policy.setTosReviewedAt(Instant.now());
        policy.setAccessPolicy(AccessPolicyType.PUBLIC_FEED);
        source.setAccessPolicy(policy);
        sourceRepository.saveAndFlush(source);
        this.ingestedSource = source;

        ingestionService.ingest(source, IngestionTrigger.MANUAL);
        jobId = jobRepository.findAll().get(0).getId().toString();
    }

    @Test
    @DisplayName("an admin can open a job detail page without a candidate profile")
    void jobDetailWorksWithoutACandidateProfile() throws Exception {
        mockMvc.perform(get("/api/jobs/" + jobId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.title").isNotEmpty())
                .andExpect(jsonPath("$.provenance").isArray())
                // No profile means no match, which is reported as absent rather than zero.
                .andExpect(jsonPath("$.summary.match").doesNotExist());
    }

    @Test
    @DisplayName("an admin can search jobs")
    void searchWorksWithoutACandidateProfile() throws Exception {
        mockMvc.perform(get("/api/jobs?size=5").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("the personal saved and applied lists are refused, not answered emptily")
    void personalListsAreNotForAdministrators() throws Exception {
        // These used to return an empty array, on the theory that a read should
        // degrade rather than break. The permission model gives a better answer:
        // "my saved jobs" is a student's list, and a platform administrator does
        // not have one. An empty array would imply they had a list that happened
        // to be empty. A 403 says the route is not theirs, which is the truth.
        mockMvc.perform(get("/api/jobs/saved").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/jobs/applied").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the dashboard renders for an account with no candidate profile")
    void dashboardWorksWithoutACandidateProfile() throws Exception {
        mockMvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.notice").isNotEmpty())
                .andExpect(jsonPath("$.recommendations").isEmpty());
    }

    @Test
    @DisplayName("saving a job is refused for an account that is not a student")
    void writesAreRefusedForNonStudents() throws Exception {
        // Catalogue reads degrade gracefully; a personal write does not. This
        // is now stopped by SELF_JOBS_MANAGE before it reaches the service, so
        // the answer is 403 rather than the old "you have no profile" 404.
        mockMvc.perform(post("/api/jobs/" + jobId + "/save")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }
}
