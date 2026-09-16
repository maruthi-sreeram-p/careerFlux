package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.careerflux.ai.AiClient;
import com.careerflux.ai.ResumeExtractionService;
import com.careerflux.ai.dto.AiResumeReading;
import com.careerflux.ai.quota.AiQuotaService;
import com.careerflux.ai.quota.QuotaDecision;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.job.domain.Job;
import com.careerflux.matching.domain.ConfidenceLevel;
import com.careerflux.matching.domain.EligibilityStatus;
import com.careerflux.matching.domain.MatchComponent;
import com.careerflux.matching.domain.MatchDimension;
import com.careerflux.matching.service.CandidateSnapshot;
import com.careerflux.matching.service.DimensionResult;
import com.careerflux.matching.service.MatchNarrator;
import com.careerflux.matching.service.MatchScorer;
import com.careerflux.support.PrivacyFixture;
import com.careerflux.support.PrivacyFixture.Account;
import com.careerflux.support.TestInstitutions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * No AI processing without consent (Phase 2B, L-4 / R30), with a provider that is
 * switched on and records every call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiConsentGateIntegrationTest {

    private static final String RESUME = """
            Meera Iyer
            meera.iyer@example.com | +91 91234 56789 | Pune, Maharashtra

            EXPERIENCE
            Data Engineering Intern, Infosys, Pune
            Jan 2025 - Jun 2025

            SKILLS
            Python, SQL, Docker
            """;

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
    private ResumeExtractionService extraction;

    @Autowired
    private MatchNarrator narrator;

    @Autowired
    private Environment environment;

    @MockitoBean
    private AiClient aiClient;

    @MockitoSpyBean
    private AiQuotaService quota;

    private Account meera;
    private String meeraToken;

    @BeforeEach
    void setUp() throws Exception {
        fixture.begin();
        when(aiClient.isAvailable()).thenReturn(true);
        when(aiClient.modelName()).thenReturn("recording-model");
        when(aiClient.structured(anyString(), anyString(), eq(AiResumeReading.class))).thenReturn(
                new AiResumeReading("Data engineer", "Builds data pipelines.", "Data Engineer", "INTERN", 0.5,
                        List.of("Python", "SQL"), List.of(), List.of()));
        when(aiClient.text(anyString(), anyString())).thenReturn("An explanation written by the model.");
        doReturn(QuotaDecision.allowed(10, 25)).when(quota).tryConsume(any());

        meera = fixture.student("Meera Iyer", institutions.example(), institutions.exampleCse(),
                institutions.exampleBatch2026());
        meeraToken = fixture.login(mockMvc, meera.email());
    }

    @AfterEach
    void tearDown() {
        fixture.cleanUp();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode upload(String token) throws Exception {
        String body = mockMvc.perform(multipart("/api/candidate/resume")
                        .file(new MockMultipartFile("file", "cv.txt", "text/plain", RESUME.getBytes()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void consent(String token) throws Exception {
        String overview = mockMvc.perform(get("/api/consents").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String noticeId = null;
        for (JsonNode state : objectMapper.readTree(overview).get("purposes")) {
            if (state.get("purpose").asText().equals("AI_PROCESSING")) {
                noticeId = state.get("currentNotice").get("id").asText();
            }
        }
        mockMvc.perform(post("/api/consents/ai-processing/accept").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("noticeVersionId", noticeId))))
                .andExpect(status().isOk());
    }

    private void withdraw(String token) throws Exception {
        mockMvc.perform(post("/api/consents/ai-processing/withdraw").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("without consent the upload succeeds, nothing is sent and no allowance is spent")
    void noConsentNoAi() throws Exception {
        JsonNode result = upload(meeraToken);

        verify(aiClient, never()).structured(anyString(), anyString(), any());
        verify(quota, never()).tryConsume(meera.userId());
        assertThat(result.get("aiAssisted").asBoolean()).isFalse();
        assertThat(result.get("notice").asText()).contains("AI processing is off");
        assertThat(result.get("resume").get("parseStatus").asText()).isNotEqualTo("FAILED");
    }

    @Test
    @DisplayName("with consent the resume is read by the model, and the model sees no identifier")
    void consentAllowsRedactedProcessing() throws Exception {
        consent(meeraToken);

        JsonNode result = upload(meeraToken);

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(aiClient).structured(anyString(), sent.capture(), eq(AiResumeReading.class));
        assertThat(result.get("aiAssisted").asBoolean()).isTrue();
        assertThat(sent.getValue().toLowerCase()).doesNotContain("meera").doesNotContain("iyer")
                .doesNotContain("example.com").doesNotContain("91234").contains("infosys");
    }

    @Test
    @DisplayName("withdrawing stops processing, and consenting again restores it")
    void withdrawalAndReconsent() throws Exception {
        consent(meeraToken);
        withdraw(meeraToken);

        upload(meeraToken);
        verify(aiClient, never()).structured(anyString(), anyString(), any());

        consent(meeraToken);
        upload(meeraToken);
        verify(aiClient, times(1)).structured(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("another student's consent does nothing for this one")
    void consentIsPerStudent() throws Exception {
        Account other = fixture.student("Rahul Nair", institutions.example(), institutions.exampleCse(),
                institutions.exampleBatch2026());
        consent(fixture.login(mockMvc, other.email()));

        upload(meeraToken);

        verify(aiClient, never()).structured(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a retried reading asks again, so one made after withdrawal is not sent")
    void retryRechecks() throws Exception {
        consent(meeraToken);
        extraction.extract(meera.userId(), RESUME);
        verify(aiClient, times(1)).structured(anyString(), anyString(), any());

        withdraw(meeraToken);
        clearInvocations(aiClient);
        extraction.extract(meera.userId(), RESUME);

        verify(aiClient, never()).structured(anyString(), anyString(), any());
        assertThat(environment.getProperty("spring.ai.retry.max-attempts", Integer.class))
                .describedAs("provider calls may not retry out of sight of the gate").isEqualTo(1);
    }

    @Test
    @DisplayName("queued match narratives ask when they run, not when they were queued")
    void queuedWorkRechecks() throws Exception {
        consent(meeraToken);
        CandidateSnapshot snapshot = snapshot(meera);
        var written = narrator.write(scorecard(), job(), snapshot);
        assertThat(written.engine()).isEqualTo("recording-model");

        withdraw(meeraToken);
        clearInvocations(aiClient, quota);
        var afterWithdrawal = narrator.write(scorecard(), job(), snapshot);

        verify(aiClient, never()).text(anyString(), anyString());
        verify(quota, never()).tryConsume(meera.userId());
        assertThat(afterWithdrawal.engine()).isEqualTo(MatchNarrator.RULES_ENGINE);
    }

    @Test
    @DisplayName("withdrawing removes unanswered AI readings and keeps readings the local parser made")
    void withdrawalDiscardsPendingAiReadings() throws Exception {
        consent(meeraToken);
        upload(meeraToken);
        assertThat(pendingProposals(meera, true)).isEqualTo(1);

        Account local = fixture.student("Anjali Menon", institutions.example(), institutions.exampleCse(),
                institutions.exampleBatch2026());
        String localToken = fixture.login(mockMvc, local.email());
        upload(localToken);
        consent(localToken);

        withdraw(meeraToken);
        withdraw(localToken);

        assertThat(pendingProposals(meera, true)).isZero();
        assertThat(pendingProposals(local, false)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_events where action = 'AI_PROPOSALS_DISCARDED' "
                + "and entity_id = ?", Long.class, meera.userId().toString())).isEqualTo(1);
        verify(aiClient, atLeastOnce()).structured(anyString(), contains("Resume text"), any());
    }

    private long pendingProposals(Account student, boolean aiAssisted) {
        return jdbc.queryForObject("select count(*) from ai_profile_proposals where candidate_id = ? "
                + "and status = 'PENDING' and ai_assisted = ?", Long.class, student.profileId(), aiAssisted);
    }

    private static CandidateSnapshot snapshot(Account student) {
        return new CandidateSnapshot(student.profileId(), student.userId(), student.fullName(), "Data Engineer",
                Seniority.INTERN, BigDecimal.ONE, "Pune", Set.of("python"), List.of("Data Engineer"),
                Set.of("Pune"), Set.of(), Set.of(), false, false, false);
    }

    private static Job job() {
        Job job = new Job();
        job.setTitle("Data Engineer");
        return job;
    }

    private static MatchScorer.Scorecard scorecard() {
        Map<MatchDimension, DimensionResult> dimensions = new EnumMap<>(MatchDimension.class);
        for (MatchDimension dimension : List.of(MatchDimension.SKILLS, MatchDimension.EXPERIENCE,
                MatchDimension.ROLE, MatchDimension.LOCATION, MatchDimension.SENIORITY)) {
            dimensions.put(dimension, DimensionResult.scored(80));
        }
        dimensions.put(MatchDimension.WORK_MODE, DimensionResult.unknownJob());
        return new MatchScorer.Scorecard(80, EligibilityStatus.ELIGIBLE, List.of(),
                new MatchScorer.Confidence(ConfidenceLevel.HIGH, 95, List.of()), dimensions,
                List.of(MatchComponent.strength(MatchDimension.SKILLS, "Python", null, 0)),
                List.of(), List.of(), List.of());
    }
}
