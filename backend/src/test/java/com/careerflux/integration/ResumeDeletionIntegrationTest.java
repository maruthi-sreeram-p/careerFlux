package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
 * A student deleting their own resume (Phase 2B, R29 / PD-5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResumeDeletionIntegrationTest {

    private static final byte[] RESUME = """
            Sneha Kulkarni
            +91 90000 54321 | Pune, Maharashtra

            SKILLS
            Python, Django, PostgreSQL
            """.getBytes();

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

    private Account sneha;
    private String snehaToken;

    @BeforeEach
    void setUp() throws Exception {
        fixture.begin();
        sneha = fixture.student("Sneha Kulkarni", institutions.example(), institutions.exampleCse(),
                institutions.exampleBatch2026());
        snehaToken = fixture.login(mockMvc, sneha.email());
    }

    @AfterEach
    void tearDown() {
        fixture.cleanUp();
    }

    private JsonNode upload(String token) throws Exception {
        return objectMapper.readTree(mockMvc.perform(multipart("/api/candidate/resume")
                        .file(new MockMultipartFile("file", "sneha-kulkarni-cv.txt", "text/plain", RESUME))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    @DisplayName("the owner's delete removes the file, the row, its text and the proposal read from it")
    void ownerDeletesEverythingAboutTheResume() throws Exception {
        JsonNode uploaded = upload(snehaToken);
        UUID resumeId = UUID.fromString(uploaded.get("resume").get("id").asText());
        Path file = fixture.fileOf(resumeId);
        assertThat(Files.exists(file)).isTrue();
        assertThat(count("select count(*) from ai_profile_proposals where resume_id = ?", resumeId)).isPositive();

        mockMvc.perform(delete("/api/candidate/resumes/" + resumeId).header("Authorization", "Bearer " + snehaToken))
                .andExpect(status().isNoContent());

        assertThat(count("select count(*) from resumes where id = ?", resumeId)).isZero();
        assertThat(count("select count(*) from ai_profile_proposals where resume_id = ?", resumeId)).isZero();
        assertThat(Files.exists(file)).isFalse();
        mockMvc.perform(get("/api/candidate/resumes/" + resumeId + "/file")
                        .header("Authorization", "Bearer " + snehaToken))
                .andExpect(status().isNotFound());
        assertThat(count("select count(*) from audit_events where action = 'RESUME_DELETED' and entity_id = ? "
                + "and actor_user_id = ?", resumeId.toString(), sneha.userId())).isEqualTo(1);
    }

    @Test
    @DisplayName("nobody else can delete it: another student is told it does not exist, staff are refused")
    void nobodyElseCanDelete() throws Exception {
        JsonNode uploaded = upload(snehaToken);
        UUID resumeId = UUID.fromString(uploaded.get("resume").get("id").asText());
        Account other = fixture.student("Rohit Das", institutions.example(), institutions.exampleCse(),
                institutions.exampleBatch2026());

        mockMvc.perform(delete("/api/candidate/resumes/" + resumeId)
                        .header("Authorization", "Bearer " + fixture.login(mockMvc, other.email())))
                .andExpect(status().isNotFound());
        for (UserRole role : List.of(UserRole.PLACEMENT_COORDINATOR, UserRole.DEPARTMENT_COORDINATOR,
                UserRole.PORTAL_ADMIN)) {
            Account staff = fixture.staff(role, role == UserRole.PORTAL_ADMIN ? null : institutions.example(),
                    role == UserRole.DEPARTMENT_COORDINATOR ? institutions.exampleCse() : null);
            mockMvc.perform(delete("/api/candidate/resumes/" + resumeId)
                            .header("Authorization", "Bearer " + fixture.login(mockMvc, staff.email())))
                    .andExpect(status().isForbidden());
        }

        assertThat(count("select count(*) from resumes where id = ?", resumeId)).isEqualTo(1);
        assertThat(Files.exists(fixture.fileOf(resumeId))).isTrue();
    }

    @Test
    @DisplayName("the student's confirmed profile and their placement history are untouched")
    void profileAndPlacementHistorySurvive() throws Exception {
        mockMvc.perform(put("/api/candidate/profile").header("Authorization", "Bearer " + snehaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"headline\":\"Aspiring backend engineer\",\"skills\":[{\"name\":\"Python\"}]}"))
                .andExpect(status().isOk());
        Account coordinator = fixture.staff(UserRole.PLACEMENT_COORDINATOR, institutions.example(), null);
        String coordinatorToken = fixture.login(mockMvc, coordinator.email());
        UUID requirement = fixture.openRequirement(mockMvc, coordinatorToken, institutions.exampleCse());
        fixture.placementHistory(mockMvc, coordinatorToken, requirement, sneha, snehaToken);
        UUID resumeId = UUID.fromString(upload(snehaToken).get("resume").get("id").asText());

        mockMvc.perform(delete("/api/candidate/resumes/" + resumeId).header("Authorization", "Bearer " + snehaToken))
                .andExpect(status().isNoContent());

        JsonNode profile = objectMapper.readTree(mockMvc.perform(get("/api/candidate/profile")
                        .header("Authorization", "Bearer " + snehaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(profile.get("headline").asText()).isEqualTo("Aspiring backend engineer");
        assertThat(profile.get("skills").findValuesAsText("name")).contains("Python");
        assertThat(profile.get("resume").isNull()).describedAs("no older resume is promoted").isTrue();

        JsonNode history = objectMapper.readTree(mockMvc.perform(get("/api/requirements/" + requirement
                        + "/shortlist/" + sneha.profileId() + "/history")
                        .header("Authorization", "Bearer " + coordinatorToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(history).describedAs("invited, then the student's answer").hasSize(2);
    }

    @Test
    @DisplayName("deleting the active resume leaves older versions where they were, not promoted")
    void olderVersionsAreNotPromoted() throws Exception {
        UUID older = fixture.resume(sneha, "Older CV text", false, Instant.now().minus(Duration.ofDays(3)));
        UUID active = UUID.fromString(upload(snehaToken).get("resume").get("id").asText());

        mockMvc.perform(delete("/api/candidate/resumes/" + active).header("Authorization", "Bearer " + snehaToken))
                .andExpect(status().isNoContent());

        assertThat(count("select count(*) from resumes where id = ? and is_active = false", older)).isEqualTo(1);
        assertThat(Files.exists(fixture.fileOf(older))).isTrue();
    }
}
