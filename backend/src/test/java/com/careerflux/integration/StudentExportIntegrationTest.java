package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.careerflux.engagement.service.EngagementService;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.source.domain.Company;
import com.careerflux.source.repository.CompanyRepository;
import com.careerflux.support.PrivacyFixture;
import com.careerflux.support.PrivacyFixture.Account;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A student's own data export (Phase 2B, R27 / PD-10): everything they may see,
 * nothing they may not, and nobody else's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentExportIntegrationTest {

    /** Appears only inside the resume's extracted text, which must never be exported. */
    private static final String TEXT_ONLY_MARKER = "QUASARFROST";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PrivacyFixture fixture;

    @Autowired
    private TestInstitutions institutions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EngagementService engagement;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private CompanyRepository companies;

    private Account priya;
    private String priyaToken;
    private Account arjun;
    private String arjunToken;
    private UUID jobId;
    private UUID companyId;

    @BeforeEach
    void setUp() throws Exception {
        fixture.begin();
        priya = fixture.student("Priya Natarajan", institutions.example(), institutions.exampleCse(),
                institutions.exampleBatch2026());
        priyaToken = fixture.login(mockMvc, priya.email());
        arjun = fixture.student("Arjun Mehta", institutions.example(), institutions.exampleCse(),
                institutions.exampleBatch2026());
        arjunToken = fixture.login(mockMvc, arjun.email());

        send(put("/api/candidate/profile").contentType(MediaType.APPLICATION_JSON)
                .content("{\"headline\":\"Final-year CSE student\",\"skills\":[{\"name\":\"Java\"},"
                        + "{\"name\":\"Power BI\"}]}"), priyaToken, 200);
        send(put("/api/candidate/academics").contentType(MediaType.APPLICATION_JSON).content("{\"cgpa\":8.7}"),
                priyaToken, 200);
        Account coordinator = fixture.staff(UserRole.PLACEMENT_COORDINATOR, institutions.example(), null);
        String coordinatorToken = fixture.login(mockMvc, coordinator.email());
        send(put("/api/institution/students/" + priya.userId() + "/academics")
                .contentType(MediaType.APPLICATION_JSON).content("{\"cgpa\":8.1}"), coordinatorToken, 200);

        mockMvc.perform(multipart("/api/candidate/resume").file(new MockMultipartFile("file", "cv.txt",
                        "text/plain", ("Priya Natarajan\n\nSKILLS\nJava, SQL\n\nThis line exists only inside the "
                                + "resume text so its appearance anywhere would mean the text leaked: "
                                + TEXT_ONLY_MARKER + ".\n").getBytes()))
                        .header("Authorization", "Bearer " + priyaToken))
                .andExpect(status().isOk());

        UUID requirement = fixture.openRequirement(mockMvc, coordinatorToken, institutions.exampleCse());
        fixture.placementHistory(mockMvc, coordinatorToken, requirement, priya, priyaToken);

        Company company = new Company();
        company.setName("Hyderabad Analytics");
        company.setSlug("hyderabad-analytics-" + UUID.randomUUID());
        companyId = fixture.inTransaction(() -> companies.saveAndFlush(company).getId());
        jobId = fixture.inTransaction(() -> {
            Job job = new Job();
            job.setCompany(companies.findById(companyId).orElseThrow());
            job.setCanonicalKey("export-test-" + UUID.randomUUID());
            job.setTitle("Graduate Data Analyst");
            job.setNormalizedTitle("graduate data analyst");
            job.setSearchText("graduate data analyst");
            job.setStatus(JobStatus.OPEN);
            job.setFirstObservedAt(Instant.now());
            job.setLastObservedAt(Instant.now());
            return jobs.saveAndFlush(job).getId();
        });
        engagement.save(priya.userId(), jobId, "Great fit for me");
    }

    @AfterEach
    void tearDown() {
        fixture.cleanUp();
        jdbc.update("delete from jobs where id = ?", jobId);
        jdbc.update("delete from companies where id = ?", companyId);
    }

    private String send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                        String token, int expected) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    private String exportAs(String token) throws Exception {
        return mockMvc.perform(get("/api/candidate/export").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"careerflux-my-data.json\""))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("it holds the student's own profile, private skills, both CGPAs, resumes, activity, placements and consent")
    void includesTheirOwnData() throws Exception {
        String body = exportAs(priyaToken);
        JsonNode export = objectMapper.readTree(body);

        assertThat(export.get("account").get("email").asText()).isEqualTo(priya.email());
        assertThat(export.get("profile").get("headline").asText()).isEqualTo("Final-year CSE student");
        assertThat(export.get("profile").get("skills").findValuesAsText("name")).contains("Java", "Power BI");
        assertThat(export.get("profile").get("reportedCgpa").decimalValue()).isEqualByComparingTo("8.7");
        assertThat(export.get("profile").get("verifiedCgpa").decimalValue()).isEqualByComparingTo("8.1");

        assertThat(export.get("resumes")).hasSize(1);
        send(get(export.get("resumes").get(0).get("downloadPath").asText()), priyaToken, 200);

        JsonNode interaction = export.get("jobInteractions").get(0);
        assertThat(interaction.get("type").asText()).isEqualTo("SAVED");
        assertThat(interaction.get("jobTitle").asText()).isEqualTo("Graduate Data Analyst");
        assertThat(interaction.get("note").asText()).isEqualTo("Great fit for me");

        JsonNode placement = export.get("placements").get(0);
        assertThat(placement.get("history")).hasSize(2);
        assertThat(placement.get("history").findValuesAsText("yourNote")).contains("Looking forward to it");

        assertThat(export.get("aiProposals")).isNotEmpty();
        assertThat(export.get("notIncluded")).isNotEmpty();
        assertThat(jdbc.queryForObject("select count(*) from audit_events where action = 'DATA_EXPORTED' "
                + "and entity_id = ?", Long.class, priya.userId().toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("it leaves out resume text, staff notes and names, college assessments and other students")
    void excludesWhatIsNotTheirs() throws Exception {
        String body = exportAs(priyaToken);

        assertThat(body)
                .doesNotContain(TEXT_ONLY_MARKER)
                .doesNotContain("extractedText")
                .doesNotContain("Invited to the aptitude round")
                .doesNotContain("Staff Member")
                .doesNotContain(arjun.email())
                .doesNotContain("Arjun Mehta")
                .doesNotContain("\"minCgpa\"")
                .doesNotContain("\"compatibility\"")
                .doesNotContain("\"eligibility\"")
                .doesNotContain("\"score\"");
    }

    @Test
    @DisplayName("each student's export is their own")
    void eachStudentGetsTheirOwn() throws Exception {
        JsonNode export = objectMapper.readTree(exportAs(arjunToken));

        assertThat(export.get("account").get("email").asText()).isEqualTo(arjun.email());
        assertThat(export.toString()).doesNotContain(priya.email()).doesNotContain("Priya Natarajan");
    }

    @Test
    @DisplayName("staff cannot export, for themselves or for a student")
    void staffAreRefused() throws Exception {
        for (UserRole role : List.of(UserRole.PLACEMENT_COORDINATOR, UserRole.DEPARTMENT_COORDINATOR,
                UserRole.PORTAL_ADMIN)) {
            Account staff = fixture.staff(role, role == UserRole.PORTAL_ADMIN ? null : institutions.example(),
                    role == UserRole.DEPARTMENT_COORDINATOR ? institutions.exampleCse() : null);
            mockMvc.perform(get("/api/candidate/export")
                            .header("Authorization", "Bearer " + fixture.login(mockMvc, staff.email())))
                    .andExpect(status().isForbidden());
        }
    }
}
