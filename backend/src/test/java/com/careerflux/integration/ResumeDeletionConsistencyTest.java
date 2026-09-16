package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import com.careerflux.ai.proposal.AiProfileProposalRepository;
import com.careerflux.candidate.service.ResumeStorageService;
import com.careerflux.support.PrivacyFixture;
import com.careerflux.support.PrivacyFixture.Account;
import com.careerflux.support.TestInstitutions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The file and the row of a resume cannot drift apart when deletion fails
 * part-way (Phase 2B, R29). Each failure is injected at the step it would really
 * happen at.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResumeDeletionConsistencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PrivacyFixture fixture;

    @Autowired
    private TestInstitutions institutions;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private ResumeStorageService storage;

    @MockitoSpyBean
    private AiProfileProposalRepository proposals;

    private Account student;
    private String token;
    private UUID resumeId;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        fixture.begin();
        student = fixture.student("Farhan Qureshi", institutions.example(), institutions.exampleCse(),
                institutions.exampleBatch2026());
        token = fixture.login(mockMvc, student.email());
        resumeId = fixture.resume(student, "Farhan Qureshi resume text", true, Instant.now());
        file = fixture.fileOf(resumeId);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanUp();
    }

    private long rows() {
        return jdbc.queryForObject("select count(*) from resumes where id = ?", Long.class, resumeId);
    }

    @Test
    @DisplayName("when the file cannot be moved out of reach, nothing is deleted at all")
    void fileMoveFailureChangesNothing() throws Exception {
        doThrow(new IllegalStateException("A stored resume could not be moved out of reach, so nothing was deleted."))
                .when(storage).quarantine(anyString());

        mockMvc.perform(delete("/api/candidate/resumes/" + resumeId).header("Authorization", "Bearer " + token))
                .andExpect(status().is5xxServerError());

        assertThat(rows()).isEqualTo(1);
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    @DisplayName("when the row cannot be deleted, the file is put back where it was")
    void databaseFailureRestoresTheFile() throws Exception {
        doThrow(new IllegalStateException("database unavailable")).when(proposals).findByResumeId(any());

        mockMvc.perform(delete("/api/candidate/resumes/" + resumeId).header("Authorization", "Bearer " + token))
                .andExpect(status().is5xxServerError());

        assertThat(rows()).isEqualTo(1);
        assertThat(Files.exists(file)).describedAs("the file must be back at its original path").isTrue();
        assertThat(storage.listQuarantined()).noneMatch(entry -> entry.lastModified().isAfter(Instant.now().minusSeconds(60)));
    }
}
