package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.careerflux.engagement.service.EngagementService;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.privacy.erasure.AccountErasureService;
import com.careerflux.privacy.erasure.AccountErasureService.ErasureRunReport;
import com.careerflux.privacy.erasure.ErasureExecutor;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Account erasure (Phase 2B, F10 / R22 / R23 / L-2 / PD-7): a grace period that
 * removes nothing, then anonymisation in place that removes the student and
 * keeps the college's placement history.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountErasureIntegrationTest {

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
    private AccountErasureService erasures;

    @Autowired
    private EngagementService engagement;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private CompanyRepository companies;

    private Account kiran;
    private String kiranToken;
    private Account coordinator;
    private String coordinatorToken;
    private UUID companyId;
    private UUID jobId;

    @BeforeEach
    void setUp() throws Exception {
        fixture.begin();
        kiran = fixture.student("Kiran Rao", institutions.example(), institutions.exampleCse(),
                institutions.exampleBatch2026());
        kiranToken = fixture.login(mockMvc, kiran.email());
        coordinator = fixture.staff(UserRole.PLACEMENT_COORDINATOR, institutions.example(), null);
        coordinatorToken = fixture.login(mockMvc, coordinator.email());
    }

    @AfterEach
    void tearDown() {
        fixture.cleanUp();
        if (jobId != null) {
            jdbc.update("delete from jobs where id = ?", jobId);
            jdbc.update("delete from companies where id = ?", companyId);
        }
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String token, int expected) throws Exception {
        String body = mockMvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static Instant daysAfter(long days) {
        return Instant.now().plus(Duration.ofDays(days));
    }

    @Nested
    @DisplayName("requesting")
    class Requesting {

        @Test
        @DisplayName("a student's request removes nothing and waits out a thirty-day grace period")
        void graceFirst() throws Exception {
            JsonNode request = call(post("/api/candidate/erasure"), kiranToken, 200);

            assertThat(request.get("status").asText()).isEqualTo("GRACE_PERIOD");
            assertThat(request.get("cancellable").asBoolean()).isTrue();
            Instant requested = Instant.parse(request.get("requestedAt").asText());
            Instant graceEnds = Instant.parse(request.get("graceEndsAt").asText());
            assertThat(Duration.between(requested, graceEnds)).isEqualTo(Duration.ofDays(30));

            erasures.processDue(Instant.now(), false, 200);

            assertThat(call(get("/api/candidate/erasure"), kiranToken, 200).get("status").asText())
                    .isEqualTo("GRACE_PERIOD");
            assertThat(fixture.login(mockMvc, kiran.email())).isNotBlank();
        }

        @Test
        @DisplayName("asking twice is the same request")
        void idempotent() throws Exception {
            String first = call(post("/api/candidate/erasure"), kiranToken, 200).get("id").asText();
            String second = call(post("/api/candidate/erasure"), kiranToken, 200).get("id").asText();

            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("a request cancelled during the grace period is never carried out, and a new one can be made")
        void cancellation() throws Exception {
            call(post("/api/candidate/erasure"), kiranToken, 200);
            assertThat(call(post("/api/candidate/erasure/cancel"), kiranToken, 200).get("status").asText())
                    .isEqualTo("CANCELLED");

            erasures.processDue(daysAfter(31), false, 200);

            assertThat(jdbc.queryForObject("select email from users where id = ?", String.class, kiran.userId()))
                    .isEqualTo(kiran.email());
            assertThat(call(post("/api/candidate/erasure"), kiranToken, 200).get("status").asText())
                    .isEqualTo("GRACE_PERIOD");
        }

        @Test
        @DisplayName("their college's placement coordinator may request and cancel it")
        void placementCoordinatorMay() throws Exception {
            String path = "/api/institution/students/" + kiran.userId() + "/erasure";

            JsonNode request = call(post(path), coordinatorToken, 200);
            assertThat(request.get("requestedByRole").asText()).isEqualTo("PLACEMENT_COORDINATOR");
            assertThat(call(get(path), coordinatorToken, 200).get("status").asText()).isEqualTo("GRACE_PERIOD");
            assertThat(call(post(path + "/cancel"), coordinatorToken, 200).get("status").asText())
                    .isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("a department coordinator, the portal administrator, another college and another student may not")
        void nobodyElseMay() throws Exception {
            String path = "/api/institution/students/" + kiran.userId() + "/erasure";
            Account departmentCoordinator = fixture.staff(UserRole.DEPARTMENT_COORDINATOR, institutions.example(),
                    institutions.exampleCse());
            Account portalAdmin = fixture.staff(UserRole.PORTAL_ADMIN, null, null);
            Account rivalCoordinator = fixture.staff(UserRole.PLACEMENT_COORDINATOR, institutions.rival(), null);
            Account otherStudent = fixture.student("Vikram Singh", institutions.example(),
                    institutions.exampleCse(), institutions.exampleBatch2026());

            call(post(path), fixture.login(mockMvc, departmentCoordinator.email()), 403);
            call(post(path), fixture.login(mockMvc, portalAdmin.email()), 403);
            call(post(path), fixture.login(mockMvc, rivalCoordinator.email()), 404);
            call(post(path), fixture.login(mockMvc, otherStudent.email()), 403);

            assertThat(count("select count(*) from account_erasures where subject_user_id = ?", kiran.userId()))
                    .isZero();
        }
    }

    @Nested
    @DisplayName("carrying it out")
    class CarryingOut {

        private UUID requirementId;

        /** A student with something in every category erasure deals with. */
        private void giveKiranAHistory() throws Exception {
            call(put("/api/candidate/profile").contentType(MediaType.APPLICATION_JSON).content("""
                    {"headline":"Mechatronics enthusiast","phone":"+91 98480 22338","location":"Warangal",
                     "linkedinUrl":"https://linkedin.com/in/kiran-rao",
                     "skills":[{"name":"Java"},{"name":"SolidWorks Pro"}]}
                    """), kiranToken, 200);
            call(put("/api/candidate/academics").contentType(MediaType.APPLICATION_JSON).content("{\"cgpa\":7.9}"),
                    kiranToken, 200);
            call(put("/api/institution/students/" + kiran.userId() + "/academics")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"cgpa\":7.6}"), coordinatorToken, 200);
            mockMvc.perform(multipart("/api/candidate/resume").file(new MockMultipartFile("file", "kiran-rao.txt",
                            "text/plain", "Kiran Rao\n+91 98480 22338\n\nSKILLS\nJava, SQL\n".getBytes()))
                            .header("Authorization", "Bearer " + kiranToken))
                    .andExpect(status().isOk());
            String consents = mockMvc.perform(get("/api/consents").header("Authorization", "Bearer " + kiranToken))
                    .andReturn().getResponse().getContentAsString();
            String noticeId = objectMapper.readTree(consents).get("purposes").get(2).get("currentNotice").get("id")
                    .asText();
            call(post("/api/consents/ai-processing/accept").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"noticeVersionId\":\"" + noticeId + "\"}"), kiranToken, 200);

            requirementId = fixture.openRequirement(mockMvc, coordinatorToken, institutions.exampleCse());
            fixture.placementHistory(mockMvc, coordinatorToken, requirementId, kiran, kiranToken);

            Company company = new Company();
            company.setName("Warangal Robotics");
            company.setSlug("warangal-robotics-" + UUID.randomUUID());
            companyId = fixture.inTransaction(() -> companies.saveAndFlush(company).getId());
            jobId = fixture.inTransaction(() -> {
                Job job = new Job();
                job.setCompany(companies.findById(companyId).orElseThrow());
                job.setCanonicalKey("erasure-test-" + UUID.randomUUID());
                job.setTitle("Graduate Robotics Engineer");
                job.setNormalizedTitle("graduate robotics engineer");
                job.setSearchText("graduate robotics engineer");
                job.setStatus(JobStatus.OPEN);
                job.setFirstObservedAt(Instant.now());
                job.setLastObservedAt(Instant.now());
                return jobs.saveAndFlush(job).getId();
            });
            engagement.save(kiran.userId(), jobId, "Apply after exams");
            jdbc.update("insert into notifications (id, user_id, category, priority, title, created_at) "
                    + "values (?, ?, 'MATCH', 'HIGH', 'A new match', ?)", UUID.randomUUID(), kiran.userId(),
                    Timestamp.from(Instant.now()));
        }

        @Test
        @DisplayName("after the grace period the student's identity and data are gone and the placement history stays")
        void erasedButHistoryKept() throws Exception {
            giveKiranAHistory();
            call(post("/api/candidate/erasure"), kiranToken, 200);

            ErasureRunReport report = erasures.processDue(daysAfter(31), false, 200);
            assertThat(report.completed()).isPositive();

            // The account: a tombstone that can never be used again.
            Map<String, Object> account = jdbc.queryForMap("select email, full_name, status, sessions_valid_after "
                    + "from users where id = ?", kiran.userId());
            assertThat((String) account.get("email")).startsWith("erased+").endsWith("@erased.invalid");
            assertThat(account.get("full_name")).isEqualTo(ErasureExecutor.ERASED_NAME);
            assertThat(account.get("status")).isEqualTo("ERASED");
            assertThat(account.get("sessions_valid_after")).isNotNull();
            call(get("/api/candidate/profile"), kiranToken, 401);
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + kiran.email() + "\",\"password\":\"" + PrivacyFixture.PASSWORD
                                    + "\"}"))
                    .andExpect(status().isForbidden());

            // The profile: stripped, including both CGPAs.
            Map<String, Object> profile = jdbc.queryForMap("select headline, phone, location, linkedin_url, "
                    + "reported_cgpa, cgpa, cgpa_source from candidate_profiles where id = ?", kiran.profileId());
            assertThat(profile.values()).containsOnlyNulls();

            // Everything that belonged to the student alone.
            for (String table : List.of("resumes", "ai_profile_proposals", "candidate_skills",
                    "candidate_custom_skills", "candidate_preferences", "job_interactions", "job_matches")) {
                assertThat(count("select count(*) from " + table + " where candidate_id = ?", kiran.profileId()))
                        .describedAs(table).isZero();
            }
            assertThat(count("select count(*) from notifications where user_id = ?", kiran.userId())).isZero();
            assertThat(Files.exists(fixture.resumeRoot().resolve(kiran.profileId().toString()))).isFalse();

            // The college's placement history, exactly as it was, minus the student's name.
            assertThat(jdbc.queryForObject("select stage from company_requirement_shortlists where candidate_id = ?",
                    String.class, kiran.profileId())).isEqualTo("INTERESTED");
            List<Map<String, Object>> changes = jdbc.queryForList("""
                    select c.actor_kind, c.actor_label, c.note from placement_stage_changes c
                    join company_requirement_shortlists s on s.id = c.shortlist_id
                    where s.candidate_id = ? order by c.occurred_at
                    """, kiran.profileId());
            assertThat(changes).describedAs("invited, then the student's answer").hasSize(2);
            assertThat(changes).filteredOn(change -> "STUDENT".equals(change.get("actor_kind")))
                    .extracting(change -> change.get("actor_label")).containsOnly(ErasureExecutor.ERASED_NAME);
            assertThat(changes).filteredOn(change -> "STAFF".equals(change.get("actor_kind")))
                    .extracting(change -> change.get("actor_label")).containsOnly("Staff Member");
            assertThat(changes).extracting(change -> change.get("note")).contains("Invited to the aptitude round");
            assertThat(call(get("/api/requirements/" + requirementId + "/shortlist/" + kiran.profileId()
                    + "/history"), coordinatorToken, 200)).hasSize(2);
            // Still on the shortlist the college built, under the tombstone name,
            // and counted the same as it is listed.
            JsonNode shortlist = call(get("/api/requirements/" + requirementId + "/shortlist"), coordinatorToken, 200);
            assertThat(shortlist.get("content"))
                    .filteredOn(row -> row.get("candidateId").asText().equals(kiran.profileId().toString()))
                    .extracting(row -> row.get("fullName").asText()).containsExactly(ErasureExecutor.ERASED_NAME);
            assertThat(shortlist.get("content")).hasSize(shortlist.get("shortlistedCount").asInt());

            // Evidence kept, identity not.
            assertThat(count("select count(*) from consent_records where user_id = ?", kiran.userId())).isEqualTo(1);
            assertThat(count("select count(*) from audit_events where action = 'CONSENT_ACCEPTED' and actor_user_id = ?",
                    kiran.userId())).isEqualTo(1);
            assertThat(count("select count(*) from audit_events where action = 'ERASURE_COMPLETED' and entity_id = ? "
                    + "and institution_id = ?", kiran.userId().toString(), institutions.example().getId())).isEqualTo(1);
            String counts = jdbc.queryForObject("select category_counts from account_erasures where subject_user_id = ?",
                    String.class, kiran.userId());
            assertThat(counts).contains("\"resumes\":1").doesNotContain("Kiran").doesNotContain("@")
                    .doesNotContain("98480");
        }

        @Test
        @DisplayName("an erased student can no longer be discovered or shortlisted")
        void erasedStudentIsOutOfReach() throws Exception {
            giveKiranAHistory();
            call(post("/api/candidate/erasure"), kiranToken, 200);
            erasures.processDue(daysAfter(31), false, 200);

            JsonNode discovered = call(get("/api/requirements/" + requirementId + "/candidates?size=100"),
                    coordinatorToken, 200);
            assertThat(discovered.get("content").findValuesAsText("userId")).doesNotContain(kiran.userId().toString());

            UUID another = fixture.openRequirement(mockMvc, coordinatorToken, institutions.exampleCse());
            call(post("/api/requirements/" + another + "/shortlist").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"candidateId\":\"" + kiran.profileId() + "\"}"), coordinatorToken, 404);
        }

        @Test
        @DisplayName("in dry-run mode a due request is reported and left waiting, with nothing removed")
        void dryRunLeavesItWaiting() throws Exception {
            call(post("/api/candidate/erasure"), kiranToken, 200);

            ErasureRunReport report = erasures.processDue(daysAfter(31), true, 200);

            assertThat(report.dryRun()).isTrue();
            assertThat(report.due()).isPositive();
            assertThat(report.completed()).isZero();
            assertThat(jdbc.queryForObject("select status from account_erasures where subject_user_id = ?",
                    String.class, kiran.userId())).isEqualTo("GRACE_PERIOD");
            assertThat(jdbc.queryForObject("select email from users where id = ?", String.class, kiran.userId()))
                    .isEqualTo(kiran.email());
        }
    }
}
