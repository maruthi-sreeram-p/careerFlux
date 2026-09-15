package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.careerflux.audit.AuditEvent;
import com.careerflux.audit.AuditEventRepository;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.CandidateSkill;
import com.careerflux.candidate.domain.CgpaSource;
import com.careerflux.candidate.domain.OnboardingStage;
import com.careerflux.candidate.domain.SkillOrigin;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.skill.SkillResolver;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * A student's own CGPA and the college's are two figures, and one never
 * replaces the other (Phase 2A, F6).
 *
 * <p>They used to share a field, so a student saving their own figure erased
 * the verified one. They are stored apart now: the student writes only theirs,
 * only placement staff write the college's, eligibility and staff filters read
 * only the college's, and every change to it is audited.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CgpaSeparationIntegrationTest {

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
    private AuditEventRepository auditRepository;

    @Autowired
    private SkillResolver skillResolver;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    private String unique;
    private User officer;
    private String officerToken;
    private UUID studentId;
    private String studentToken;
    private String studentLocalPart;

    @BeforeEach
    void setUp() throws Exception {
        unique = Long.toString(System.nanoTime());
        officer = staff("cgpa-pc-" + unique + "@example.com");
        officerToken = login(officer);

        studentLocalPart = "cgpa-student-" + unique;
        String email = studentLocalPart + "@example.com";
        studentToken = json(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\",\"fullName\":\"Cgpa Student\"}"
                        .formatted(email, PASSWORD)), null, 201).get("accessToken").asText();
        User student = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        student.setDepartment(institutions.exampleCse());
        student.setBatch(institutions.exampleBatch2027());
        userRepository.saveAndFlush(student);
        studentId = student.getId();

        // Enough of a profile for discovery to score them.
        CandidateProfile profile = profileRepository.findByUserId(studentId).orElseThrow();
        profile.setPrimaryRole("Backend Developer");
        profile.setSeniority(Seniority.JUNIOR);
        profile.setOnboardingStage(OnboardingStage.COMPLETE);
        skillResolver.lookup("Java").ifPresent(skill -> {
            CandidateSkill held = new CandidateSkill();
            held.setCandidate(profile);
            held.setSkill(skill);
            held.setOrigin(SkillOrigin.RESUME);
            profile.getSkills().add(held);
        });
        profileRepository.saveAndFlush(profile);
    }

    @Test
    @DisplayName("a student's own figure never replaces the college's")
    void studentCannotOverwriteVerified() throws Exception {
        recordVerified("8.10");
        recordOwn("9.90");

        JsonNode record = json(get(academics()), officerToken, 200);
        assertThat(record.get("verifiedCgpa").decimalValue()).isEqualByComparingTo("8.10");
        assertThat(record.get("reportedCgpa").decimalValue()).isEqualByComparingTo("9.90");
        assertThat(record.get("verified").asBoolean()).isTrue();

        CandidateProfile profile = profileRepository.findByUserId(studentId).orElseThrow();
        assertThat(profile.getVerifiedCgpa()).isEqualByComparingTo("8.10");
        assertThat(profile.getReportedCgpa()).isEqualByComparingTo("9.90");
        assertThat(profile.getCgpaSource()).isEqualTo(CgpaSource.INSTITUTION);
    }

    @Test
    @DisplayName("a student clearing their own figure leaves the college's alone")
    void studentClearingLeavesVerified() throws Exception {
        recordVerified("8.10");
        recordOwn("9.90");
        recordOwn("null");

        JsonNode record = json(get(academics()), officerToken, 200);
        assertThat(record.get("verifiedCgpa").decimalValue()).isEqualByComparingTo("8.10");
        assertThat(record.get("reportedCgpa").isNull()).isTrue();
    }

    @Test
    @DisplayName("the college recording its figure leaves the student's alone")
    void verifiedLeavesTheStudentsFigure() throws Exception {
        recordOwn("9.90");
        recordVerified("7.00");

        JsonNode record = json(get(academics()), officerToken, 200);
        assertThat(record.get("reportedCgpa").decimalValue()).isEqualByComparingTo("9.90");
        assertThat(record.get("verifiedCgpa").decimalValue()).isEqualByComparingTo("7.00");
    }

    @Test
    @DisplayName("nothing a student sends makes their figure verified")
    void studentCannotVerifyThemselves() throws Exception {
        json(put("/api/candidate/academics").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cgpa\":9.90,\"verified\":true,\"source\":\"INSTITUTION\",\"verifiedCgpa\":9.90}"),
                studentToken, 200);

        JsonNode own = json(get("/api/candidate/profile"), studentToken, 200);
        assertThat(own.get("reportedCgpa").decimalValue()).isEqualByComparingTo("9.90");
        assertThat(own.get("verifiedCgpa").isNull()).isTrue();
        assertThat(own.get("cgpaVerified").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("eligibility reads the college's figure, whatever the student says")
    void eligibilityUsesTheVerifiedFigure() throws Exception {
        String requirement = openRequirement("8.50");
        recordVerified("8.10");
        recordOwn("9.90");

        JsonNode candidate = candidate(requirement);
        assertThat(candidate.get("eligibility").asText()).isEqualTo("NOT_ELIGIBLE");
        assertThat(candidate.get("cgpa").decimalValue()).isEqualByComparingTo("8.10");
    }

    @Test
    @DisplayName("staff screens show the two figures apart")
    void staffSeeBothFiguresLabelled() throws Exception {
        recordVerified("8.10");
        recordOwn("9.90");

        JsonNode row = json(get("/api/institution/students?q=" + studentLocalPart), officerToken, 200)
                .get("content").get(0);
        assertThat(row.get("verifiedCgpa").decimalValue()).isEqualByComparingTo("8.10");
        assertThat(row.get("reportedCgpa").decimalValue()).isEqualByComparingTo("9.90");
        assertThat(row.get("normalisedCgpa").decimalValue()).isEqualByComparingTo("8.10");

        JsonNode detail = json(get("/api/institution/students/" + studentId), officerToken, 200);
        assertThat(detail.get("verifiedCgpa").decimalValue()).isEqualByComparingTo("8.10");
        assertThat(detail.get("reportedCgpa").decimalValue()).isEqualByComparingTo("9.90");
    }

    @Test
    @DisplayName("a CGPA filter ignores a figure the student gave themselves")
    void directoryFilterReadsOnlyTheVerifiedFigure() throws Exception {
        recordOwn("9.90");
        assertThat(names("q=" + studentLocalPart + "&minCgpa=7")).isEmpty();

        recordVerified("8.10");
        assertThat(names("q=" + studentLocalPart + "&minCgpa=7")).hasSize(1);
    }

    @Test
    @DisplayName("every change to the college's figure is audited, with the figures and without a name")
    void verifiedChangesAreAudited() throws Exception {
        recordVerified("8.10");
        recordVerified("8.30");
        recordVerified("null");

        List<AuditEvent> rows = auditRepository
                .findByInstitutionIdOrderByOccurredAtDesc(institutions.example().getId(), PageRequest.of(0, 500))
                .stream()
                .filter(event -> studentId.toString().equals(event.getEntityId()))
                .filter(event -> event.getAction().startsWith("VERIFIED_CGPA_"))
                .toList();

        assertThat(rows).extracting(AuditEvent::getAction)
                .containsExactlyInAnyOrder("VERIFIED_CGPA_RECORDED", "VERIFIED_CGPA_RECORDED", "VERIFIED_CGPA_CLEARED");
        assertThat(rows).extracting(AuditEvent::getDetail).containsExactlyInAnyOrder(
                "previous=none new=8.1 scale=10", "previous=8.1 new=8.3 scale=10",
                "previous=8.3 new=none scale=10");
        assertThat(rows).allSatisfy(event -> {
            assertThat(event.getActorUserId()).isEqualTo(officer.getId());
            assertThat(event.getActorRole()).isEqualTo("PLACEMENT_COORDINATOR");
            assertThat(event.getDetail()).doesNotContain("Cgpa Student").doesNotContain("@");
        });
    }

    // --------------------------------------------------------------- helpers

    private String academics() {
        return "/api/institution/students/" + studentId + "/academics";
    }

    private void recordVerified(String value) throws Exception {
        json(put(academics()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cgpa\":%s}".formatted(value)), officerToken, 200);
    }

    private void recordOwn(String value) throws Exception {
        json(put("/api/candidate/academics").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cgpa\":%s}".formatted(value)), studentToken, 200);
    }

    private List<String> names(String query) throws Exception {
        return json(get("/api/institution/students?" + query), officerToken, 200)
                .get("content").findValuesAsText("fullName");
    }

    private String openRequirement(String minCgpa) throws Exception {
        String id = json(post("/api/requirements").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"companyName":"Cgpa Co %s","roleTitle":"Java Backend Developer",
                         "minCgpa":%s,"skills":[{"skill":"Java","tier":"REQUIRED"}]}
                        """.formatted(unique, minCgpa)), officerToken, 201).get("id").asText();
        json(patch("/api/requirements/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"OPEN\"}"), officerToken, 200);
        return id;
    }

    private JsonNode candidate(String requirementId) throws Exception {
        JsonNode page = json(get("/api/requirements/" + requirementId + "/candidates?size=100"), officerToken, 200);
        for (JsonNode candidate : page.get("content")) {
            if (candidate.get("userId").asText().equals(studentId.toString())) {
                return candidate;
            }
        }
        throw new AssertionError("student not in the discovered cohort");
    }

    private User staff(String email) {
        User user = new User();
        user.setEmail(email);
        user.setFullName("Placement Coordinator");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(UserRole.PLACEMENT_COORDINATOR);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institutions.example());
        return userRepository.saveAndFlush(user);
    }

    private String login(User user) throws Exception {
        return json(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(user.getEmail(), PASSWORD)),
                null, 200).get("accessToken").asText();
    }

    private JsonNode json(MockHttpServletRequestBuilder request, String token, int expected) throws Exception {
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return objectMapper.readTree(mockMvc.perform(request)
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString());
    }

    @SuppressWarnings("unused")
    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
