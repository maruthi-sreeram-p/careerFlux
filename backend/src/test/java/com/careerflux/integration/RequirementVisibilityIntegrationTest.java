package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.Institution;
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
 * One visibility rule for a requirement, on every route that describes one
 * (Phase 2A, F12).
 *
 * <p>A department coordinator outside the departments a requirement targets
 * was refused the requirement itself but could read its company, role, target
 * departments, minimum CGPA and shortlist count through discovery, the
 * shortlist and placement history — each of which checked only the college.
 * Every one of them now asks the same question and gives the same answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RequirementVisibilityIntegrationTest {

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

    private String unique;
    private String placementToken;
    private String coordinatorToken;
    private UUID mechCandidate;
    private UUID cseStudent;
    private UUID cseCandidate;
    private String hiddenId;
    private String visibleId;

    @BeforeEach
    void setUp() throws Exception {
        unique = Long.toString(System.nanoTime());
        placementToken = login(staff("vis-pc", UserRole.PLACEMENT_COORDINATOR, institutions.example()));

        User coordinator = staff("vis-dc", UserRole.DEPARTMENT_COORDINATOR, institutions.example());
        staffScopeRepository.saveAndFlush(StaffScope.forDepartment(
                coordinator, institutions.example(), institutions.exampleCse()));
        coordinatorToken = login(coordinator);

        mechCandidate = profileRepository.findByUserId(student("vis-mech", institutions.exampleMech()))
                .orElseThrow().getId();
        cseStudent = student("vis-cse", institutions.exampleCse());
        cseCandidate = profileRepository.findByUserId(cseStudent).orElseThrow().getId();

        // Aimed at Mechanical only: outside the coordinator's department.
        hiddenId = openRequirement("Hidden Co " + unique, institutions.exampleMech(), "7.5");
        expect(post("/api/requirements/" + hiddenId + "/shortlist").contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateId\":\"%s\"}".formatted(mechCandidate)), placementToken, 201);

        // Aimed at their own department: the control.
        visibleId = openRequirement("Visible Co " + unique, institutions.exampleCse(), null);
    }

    @Test
    @DisplayName("the requirement itself is refused, and left out of the list")
    void directReadIsRefused() throws Exception {
        assertThat(body(get("/api/requirements/" + hiddenId), coordinatorToken, 404))
                .doesNotContain("Hidden Co");
        assertThat(json(get("/api/requirements?size=100"), coordinatorToken, 200)
                .get("content").findValuesAsText("id")).doesNotContain(hiddenId);
    }

    @Test
    @DisplayName("discovery refuses it rather than describing it over an empty list")
    void discoveryIsRefused() throws Exception {
        String refused = body(get("/api/requirements/" + hiddenId + "/candidates"), coordinatorToken, 404);

        assertThat(refused).doesNotContain("Hidden Co").doesNotContain("7.5").doesNotContain("Mechanical");
    }

    @Test
    @DisplayName("the shortlist refuses it, count and all")
    void shortlistIsRefused() throws Exception {
        String refused = body(get("/api/requirements/" + hiddenId + "/shortlist"), coordinatorToken, 404);

        assertThat(refused).doesNotContain("Hidden Co").doesNotContain("shortlistedCount");
    }

    @Test
    @DisplayName("placement history refuses it")
    void historyIsRefused() throws Exception {
        expect(get("/api/requirements/" + hiddenId + "/shortlist/" + mechCandidate + "/history"),
                coordinatorToken, 404);
    }

    @Test
    @DisplayName("and nothing can be written against it")
    void writesAreRefused() throws Exception {
        expect(post("/api/requirements/" + hiddenId + "/shortlist").contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateId\":\"%s\"}".formatted(cseCandidate)), coordinatorToken, 404);
        expect(patch("/api/requirements/" + hiddenId + "/shortlist/" + mechCandidate + "/stage")
                .contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"INVITED\"}"),
                coordinatorToken, 404);
        expect(delete("/api/requirements/" + hiddenId + "/shortlist/" + mechCandidate), coordinatorToken, 404);

        assertThat(json(get("/api/requirements/" + hiddenId + "/shortlist"), placementToken, 200)
                .get("content").findValuesAsText("candidateId"))
                .containsExactly(mechCandidate.toString());
    }

    @Test
    @DisplayName("a requirement aimed at their own department is open on every route")
    void theControlIsVisibleEverywhere() throws Exception {
        expect(get("/api/requirements/" + visibleId), coordinatorToken, 200);
        assertThat(json(get("/api/requirements/" + visibleId + "/candidates?size=100"), coordinatorToken, 200)
                .get("content").findValuesAsText("userId")).contains(cseStudent.toString());
        expect(get("/api/requirements/" + visibleId + "/shortlist"), coordinatorToken, 200);
    }

    @Test
    @DisplayName("the placement coordinator sees both, and another college sees neither")
    void theRuleIsOnlyAboutDepartments() throws Exception {
        expect(get("/api/requirements/" + hiddenId), placementToken, 200);
        expect(get("/api/requirements/" + hiddenId + "/candidates"), placementToken, 200);

        String rival = login(staff("vis-rival", UserRole.PLACEMENT_COORDINATOR, institutions.rival()));
        expect(get("/api/requirements/" + hiddenId), rival, 404);
        expect(get("/api/requirements/" + visibleId + "/candidates"), rival, 404);
    }

    // --------------------------------------------------------------- helpers

    private UUID student(String name, Department department) throws Exception {
        String email = name + "-" + unique + "@example.com";
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\",\"fullName\":\"%s\"}"
                                .formatted(email, PASSWORD, name)))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.setDepartment(department);
        user.setBatch(institutions.exampleBatch2026());
        userRepository.saveAndFlush(user);
        return user.getId();
    }

    private String openRequirement(String company, Department department, String minCgpa) throws Exception {
        String body = "{\"companyName\":\"%s\",\"roleTitle\":\"Backend Engineer\",\"departmentIds\":[\"%s\"]%s}"
                .formatted(company, department.getId(), minCgpa == null ? "" : ",\"minCgpa\":" + minCgpa);
        String id = json(post("/api/requirements").contentType(MediaType.APPLICATION_JSON).content(body),
                placementToken, 201).get("id").asText();
        expect(patch("/api/requirements/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"OPEN\"}"), placementToken, 200);
        return id;
    }

    private User staff(String name, UserRole role, Institution institution) {
        User user = new User();
        user.setEmail(name + "-" + unique
                + (institution.getId().equals(institutions.rival().getId()) ? "@rival.edu" : "@example.com"));
        user.setFullName("Staff Member");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institution);
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
