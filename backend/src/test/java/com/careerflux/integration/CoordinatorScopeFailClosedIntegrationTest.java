package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.institution.domain.Batch;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * A department coordinator's scope fails closed (Phase 2A, F9).
 *
 * <p>The locked rule: a department coordinator is department-scoped, optionally
 * narrowed to batches inside those departments. A batch grant never grants
 * anything on its own. The public API cannot create a batch-only grant today,
 * which is exactly why these tests build one directly: the authorization logic
 * has to hold without leaning on that.
 *
 * <p>The cohort is three students — CSE 2026, MECH 2026 and CSE 2027 — against a
 * requirement open to the whole college, with two of them already shortlisted
 * by the placement coordinator.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CoordinatorScopeFailClosedIntegrationTest {

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
    private StaffScopeRepository staffScopeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    private record Student(UUID userId, UUID candidateId, String email) {
    }

    private String unique;
    private String placementToken;
    private String batchOnlyToken;
    private String narrowedToken;
    private Student cse2026;
    private Student mech2026;
    private Student cse2027;
    private String requirementId;

    @BeforeEach
    void setUp() throws Exception {
        unique = Long.toString(System.nanoTime());
        placementToken = login(staff("scope-pc", UserRole.PLACEMENT_COORDINATOR));

        cse2026 = student("scope-cse-2026", institutions.exampleCse(), institutions.exampleBatch2026());
        mech2026 = student("scope-mech-2026", institutions.exampleMech(), institutions.exampleBatch2026());
        cse2027 = student("scope-cse-2027", institutions.exampleCse(), institutions.exampleBatch2027());

        // Open to the whole college: the case where an empty department set used
        // to be read as "every department".
        requirementId = openRequirement("{\"companyName\":\"Scope Co %s\",\"roleTitle\":\"Backend\"}"
                .formatted(unique));
        shortlist(placementToken, mech2026.candidateId(), 201);
        shortlist(placementToken, cse2026.candidateId(), 201);

        User batchOnly = staff("scope-batch-only", UserRole.DEPARTMENT_COORDINATOR);
        staffScopeRepository.saveAndFlush(
                StaffScope.forBatch(batchOnly, institutions.example(), institutions.exampleBatch2026()));
        batchOnlyToken = login(batchOnly);

        User narrowed = staff("scope-narrowed", UserRole.DEPARTMENT_COORDINATOR);
        staffScopeRepository.saveAndFlush(
                StaffScope.forDepartment(narrowed, institutions.example(), institutions.exampleCse()));
        staffScopeRepository.saveAndFlush(
                StaffScope.forBatch(narrowed, institutions.example(), institutions.exampleBatch2026()));
        narrowedToken = login(narrowed);
    }

    @Nested
    @DisplayName("a coordinator holding only a batch grant")
    class BatchOnly {

        @Test
        @DisplayName("sees nobody in the directory, and opens nobody")
        void directoryIsEmpty() throws Exception {
            assertThat(json(get("/api/institution/students?size=100"), batchOnlyToken, 200)
                    .get("totalElements").asLong()).isZero();
            for (Student student : List.of(cse2026, mech2026, cse2027)) {
                expect(get("/api/institution/students/" + student.userId()), batchOnlyToken, 404);
            }
        }

        @Test
        @DisplayName("counts nobody on the dashboard, and is told they have no scope")
        void overviewCountsNobody() throws Exception {
            JsonNode overview = json(get("/api/institution/overview"), batchOnlyToken, 200);

            assertThat(overview.get("studentsInScope").asLong()).isZero();
            assertThat(overview.get("institutionWide").asBoolean()).isFalse();
            assertThat(overview.get("scopeLabel").asText()).isEqualTo("No scope granted");
        }

        @Test
        @DisplayName("sees no requirement, not even one open to the whole college")
        void seesNoRequirement() throws Exception {
            expect(get("/api/requirements/" + requirementId), batchOnlyToken, 404);
            assertThat(json(get("/api/requirements?size=100"), batchOnlyToken, 200)
                    .get("content").findValuesAsText("id")).doesNotContain(requirementId);
        }

        @Test
        @DisplayName("cannot discover, read the shortlist, shortlist, move a stage or read a history")
        void everyPlacementRouteIsRefused() throws Exception {
            String discovery = body(get("/api/requirements/" + requirementId + "/candidates"),
                    batchOnlyToken, 404);
            assertThat(discovery).doesNotContain("Scope Co");
            expect(get("/api/requirements/" + requirementId + "/shortlist"), batchOnlyToken, 404);
            shortlist(batchOnlyToken, cse2027.candidateId(), 404);
            expect(patch("/api/requirements/" + requirementId + "/shortlist/" + mech2026.candidateId()
                    + "/stage").contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"INVITED\"}"),
                    batchOnlyToken, 404);
            expect(get("/api/requirements/" + requirementId + "/shortlist/" + mech2026.candidateId()
                    + "/history"), batchOnlyToken, 404);

            // And nothing was written on the way to any of those refusals.
            JsonNode shortlist = json(get("/api/requirements/" + requirementId + "/shortlist"),
                    placementToken, 200);
            assertThat(shortlist.get("content").findValuesAsText("userId"))
                    .containsExactlyInAnyOrder(cse2026.userId().toString(), mech2026.userId().toString());
            assertThat(shortlist.get("content").findValuesAsText("placementStage")).containsOnly("SHORTLISTED");
        }
    }

    @Nested
    @DisplayName("a coordinator with a department and a batch")
    class DepartmentNarrowedToABatch {

        @Test
        @DisplayName("sees that batch inside the department in the directory, and no one else")
        void directoryIsTheSlice() throws Exception {
            List<String> emails = json(get("/api/institution/students?size=100"), narrowedToken, 200)
                    .get("content").findValuesAsText("email");

            assertThat(emails).contains(cse2026.email())
                    .doesNotContain(mech2026.email(), cse2027.email());
        }

        @Test
        @DisplayName("discovers that batch inside the department, and no one else")
        void discoveryIsTheSlice() throws Exception {
            List<String> discovered = json(get("/api/requirements/" + requirementId + "/candidates?size=100"),
                    narrowedToken, 200).get("content").findValuesAsText("userId");

            assertThat(discovered).contains(cse2026.userId().toString())
                    .doesNotContain(mech2026.userId().toString(), cse2027.userId().toString());
        }

        @Test
        @DisplayName("reads only their slice of the shortlist")
        void shortlistIsTheSlice() throws Exception {
            assertThat(json(get("/api/requirements/" + requirementId + "/shortlist"), narrowedToken, 200)
                    .get("content").findValuesAsText("userId"))
                    .containsExactly(cse2026.userId().toString());
        }

        @Test
        @DisplayName("cannot act on anybody outside the slice, and can act inside it")
        void actsOnlyInsideTheSlice() throws Exception {
            // Another batch in their own department, and their batch in another
            // department: both outside.
            shortlist(narrowedToken, cse2027.candidateId(), 404);
            expect(delete("/api/requirements/" + requirementId + "/shortlist/" + mech2026.candidateId()),
                    narrowedToken, 404);
            expect(patch("/api/requirements/" + requirementId + "/shortlist/" + mech2026.candidateId()
                    + "/stage").contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"INVITED\"}"),
                    narrowedToken, 404);
            expect(get("/api/requirements/" + requirementId + "/shortlist/" + mech2026.candidateId()
                    + "/history"), narrowedToken, 404);

            expect(get("/api/requirements/" + requirementId + "/shortlist/" + cse2026.candidateId()
                    + "/history"), narrowedToken, 200);
        }
    }

    // --------------------------------------------------------------- helpers

    private Student student(String name, Department department, Batch batch) throws Exception {
        String email = name + "-" + unique + "@example.com";
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\",\"fullName\":\"%s\"}"
                                .formatted(email, PASSWORD, name)))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.setDepartment(department);
        user.setBatch(batch);
        userRepository.saveAndFlush(user);
        UUID candidateId = profileRepository.findByUserId(user.getId()).orElseThrow().getId();
        return new Student(user.getId(), candidateId, email);
    }

    private String openRequirement(String body) throws Exception {
        String id = json(post("/api/requirements").contentType(MediaType.APPLICATION_JSON).content(body),
                placementToken, 201).get("id").asText();
        expect(patch("/api/requirements/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"OPEN\"}"), placementToken, 200);
        return id;
    }

    private void shortlist(String token, UUID candidateId, int expected) throws Exception {
        expect(post("/api/requirements/" + requirementId + "/shortlist").contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateId\":\"%s\"}".formatted(candidateId)), token, expected);
    }

    private User staff(String name, UserRole role) {
        User user = new User();
        user.setEmail(name + "-" + unique + "@example.com");
        user.setFullName("Staff Member");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institutions.example());
        return userRepository.saveAndFlush(user);
    }

    private String login(User user) throws Exception {
        return json(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(user.getEmail(), PASSWORD)),
                null, 200).get("accessToken").asText();
    }

    private void expect(MockHttpServletRequestBuilder request, String token, int expected) throws Exception {
        body(request, token, expected);
    }

    private String body(MockHttpServletRequestBuilder request, String token, int expected) throws Exception {
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode json(MockHttpServletRequestBuilder request, String token, int expected) throws Exception {
        return objectMapper.readTree(body(request, token, expected));
    }
}
