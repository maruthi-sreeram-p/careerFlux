package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.engagement.domain.InteractionType;
import com.careerflux.engagement.domain.JobInteraction;
import com.careerflux.engagement.repository.JobInteractionRepository;
import com.careerflux.engagement.service.EngagementService;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.source.domain.Company;
import com.careerflux.source.repository.CompanyRepository;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * What staff learn about a student's dealings with public job postings:
 * nothing (Phase 2A, F1 and F2).
 *
 * <p>Which jobs a student viewed is private, full stop. Saves are private by
 * default until a product decision says otherwise. And CareerFlux cannot tell an
 * Apply click from an application actually made, so neither may reach a
 * coordinator as an application or be counted as one. The student is driven
 * through their own routes; both coordinator roles then look through every
 * staff view that could carry the activity.
 *
 * <p>The records are not deleted. They are the student's, and the student keeps
 * their own saved and applied lists; they are simply never part of a staff
 * response.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StaffActivityPrivacyIntegrationTest {

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
    private JobRepository jobRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private JobInteractionRepository interactions;

    @Autowired
    private EngagementService engagement;

    @Autowired
    private StaffScopeRepository staffScopeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    private String unique;
    private UUID studentId;
    private String studentToken;
    private String placementToken;
    private String departmentToken;
    private Job viewed;
    private Job saved;
    private Job applied;

    @BeforeEach
    void setUp() throws Exception {
        unique = Long.toString(System.nanoTime());
        viewed = job("Viewed Role " + unique);
        saved = job("Saved Role " + unique);
        applied = job("Applied Role " + unique);

        String email = "activity-" + unique + "@example.com";
        studentToken = register(email);
        User student = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        student.setDepartment(institutions.exampleCse());
        userRepository.saveAndFlush(student);
        studentId = student.getId();

        // What the student does. The view is recorded exactly as opening a job
        // page records it; saving and marking applied go through their routes.
        engagement.recordView(studentId, viewed.getId());
        send(post("/api/jobs/" + saved.getId() + "/save"), studentToken);
        send(post("/api/jobs/" + applied.getId() + "/applied"), studentToken);

        placementToken = login(staff("activity-pc-" + unique + "@example.com",
                UserRole.PLACEMENT_COORDINATOR));
        User coordinator = staff("activity-dc-" + unique + "@example.com", UserRole.DEPARTMENT_COORDINATOR);
        staffScopeRepository.saveAndFlush(StaffScope.forDepartment(
                coordinator, institutions.example(), institutions.exampleCse()));
        departmentToken = login(coordinator);
    }

    @Test
    @DisplayName("neither coordinator sees which jobs the student viewed, saved or applied to")
    void studentDetailCarriesNoJobActivity() throws Exception {
        for (String token : List.of(placementToken, departmentToken)) {
            JsonNode detail = json(get("/api/institution/students/" + studentId), token);

            assertThat(detail.get("activity").findValuesAsText("type"))
                    .noneMatch(type -> type.startsWith("JOB_"));
            assertThat(detail.toString())
                    .describedAs("not even the postings' titles")
                    .doesNotContain(viewed.getTitle(), saved.getTitle(), applied.getTitle());
        }
    }

    @Test
    @DisplayName("the dashboards count no views, saves or applications")
    void overviewCountsNoJobActivity() throws Exception {
        for (String token : List.of(placementToken, departmentToken)) {
            JsonNode overview = json(get("/api/institution/overview"), token);

            List<String> fields = new ArrayList<>();
            overview.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).noneMatch(name -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.contains("appl") || lower.contains("saved") || lower.contains("view");
            });
        }
    }

    @Test
    @DisplayName("the student still has their own saved and applied lists")
    void studentKeepsTheirOwnLists() throws Exception {
        assertThat(json(get("/api/jobs/saved"), studentToken).findValuesAsText("id"))
                .contains(saved.getId().toString());
        assertThat(json(get("/api/jobs/applied"), studentToken).findValuesAsText("id"))
                .contains(applied.getId().toString());
    }

    @Test
    @DisplayName("the activity is withheld from staff, not deleted from the student")
    void privateActivityIsKeptNotDeleted() {
        CandidateProfile profile = profileRepository.findByUserId(studentId).orElseThrow();

        assertThat(interactions.findByCandidateIdOrderByCreatedAtDesc(profile.getId()))
                .extracting(JobInteraction::getInteractionType)
                .contains(InteractionType.VIEWED, InteractionType.SAVED, InteractionType.APPLIED);
    }

    @Test
    @DisplayName("opening a job records a view and nothing more: it is never an application")
    void openingAJobIsNotApplying() throws Exception {
        Job opened = job("Opened Role " + unique);

        engagement.recordView(studentId, opened.getId());

        CandidateProfile profile = profileRepository.findByUserId(studentId).orElseThrow();
        assertThat(interactions.findByCandidateIdAndJobIdAndInteractionType(
                profile.getId(), opened.getId(), InteractionType.APPLIED)).isEmpty();
        assertThat(json(get("/api/jobs/applied"), studentToken).findValuesAsText("id"))
                .doesNotContain(opened.getId().toString());
    }

    // --------------------------------------------------------------- helpers

    private Job job(String title) {
        Company company = new Company();
        company.setName("Activity Co " + title);
        company.setSlug("activity-co-" + title.toLowerCase(Locale.ROOT).replace(' ', '-'));
        companyRepository.saveAndFlush(company);

        Job created = new Job();
        created.setCompany(company);
        created.setCanonicalKey("activity-job-" + title.toLowerCase(Locale.ROOT).replace(' ', '-'));
        created.setTitle(title);
        created.setNormalizedTitle(title.toLowerCase(Locale.ROOT));
        created.setSearchText(title.toLowerCase(Locale.ROOT));
        created.setStatus(JobStatus.OPEN);
        created.setFirstObservedAt(Instant.now());
        created.setLastObservedAt(Instant.now());
        return jobRepository.saveAndFlush(created);
    }

    private String register(String email) throws Exception {
        return json(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\",\"fullName\":\"Private Student\"}"
                        .formatted(email, PASSWORD)), null).get("accessToken").asText();
    }

    private User staff(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setFullName("Staff Member");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institutions.example());
        return userRepository.saveAndFlush(user);
    }

    private String login(User user) throws Exception {
        return json(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(user.getEmail(), PASSWORD)), null)
                .get("accessToken").asText();
    }

    private void send(MockHttpServletRequestBuilder request, String token) throws Exception {
        mockMvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().is2xxSuccessful());
    }

    private JsonNode json(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return objectMapper.readTree(mockMvc.perform(request)
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString());
    }
}
