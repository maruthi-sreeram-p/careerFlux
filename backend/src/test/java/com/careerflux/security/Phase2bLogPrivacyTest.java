package com.careerflux.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.careerflux.ai.AiClient;
import com.careerflux.ai.dto.AiResumeReading;
import com.careerflux.ai.quota.AiQuotaService;
import com.careerflux.ai.quota.QuotaDecision;
import com.careerflux.privacy.RetentionScheduler;
import com.careerflux.privacy.erasure.AccountErasureService;
import com.careerflux.support.PrivacyFixture;
import com.careerflux.support.PrivacyFixture.Account;
import com.careerflux.support.TestInstitutions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole Phase 2B lifecycle of one student, with the log read afterwards:
 * consent, an AI reading of the resume, export, resume deletion, erasure and a
 * retention run. None of it may write the student's identity, their resume or
 * what was sent to the model into the log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class Phase2bLogPrivacyTest {

    private static final String PHONE = "+91 99887 76655";
    private static final String FILENAME = "harini-subramanian-resume.txt";
    private static final String TEXT_ONLY_MARKER = "OBSIDIANLARK";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PrivacyFixture fixture;

    @Autowired
    private TestInstitutions institutions;

    @Autowired
    private AccountErasureService erasures;

    @Autowired
    private RetentionScheduler scheduler;

    @MockitoBean
    private AiClient aiClient;

    @MockitoSpyBean
    private AiQuotaService quota;

    private Account harini;

    @BeforeEach
    void setUp() {
        fixture.begin();
        when(aiClient.isAvailable()).thenReturn(true);
        when(aiClient.modelName()).thenReturn("recording-model");
        when(aiClient.structured(anyString(), anyString(), eq(AiResumeReading.class))).thenReturn(
                new AiResumeReading("Embedded engineer", "Works on firmware.", "Embedded Engineer", null, null,
                        List.of("C", "RTOS"), List.of(), List.of()));
        doReturn(QuotaDecision.allowed(10, 25)).when(quota).tryConsume(any());
        harini = fixture.student("Harini Subramanian", institutions.example(), institutions.exampleCse(),
                institutions.exampleBatch2026());
    }

    @AfterEach
    void tearDown() {
        fixture.cleanUp();
    }

    @Test
    @DisplayName("no identity, resume text, prompt, token or password reaches the log across the lifecycle")
    void lifecycleLeavesNothingPersonalInTheLog(CapturedOutput output) throws Exception {
        String token = fixture.login(mockMvc, harini.email());
        String auth = "Bearer " + token;

        JsonNode consents = objectMapper.readTree(mockMvc.perform(get("/api/consents").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String noticeId = consents.get("purposes").get(2).get("currentNotice").get("id").asText();
        mockMvc.perform(post("/api/consents/ai-processing/accept").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"noticeVersionId\":\"" + noticeId + "\"}"))
                .andExpect(status().isOk());

        String resume = "Harini Subramanian\n" + harini.email() + " | " + PHONE + "\n\nSKILLS\nC, RTOS\n\n"
                + "Worked on " + TEXT_ONLY_MARKER + " firmware.\n";
        JsonNode uploaded = objectMapper.readTree(mockMvc.perform(multipart("/api/candidate/resume")
                        .file(new MockMultipartFile("file", FILENAME, "text/plain", resume.getBytes()))
                        .header("Authorization", auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(uploaded.get("aiAssisted").asBoolean()).isTrue();

        mockMvc.perform(get("/api/candidate/export").header("Authorization", auth)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/candidate/resumes/" + uploaded.get("resume").get("id").asText())
                .header("Authorization", auth)).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/candidate/erasure").header("Authorization", auth)).andExpect(status().isOk());
        erasures.processDue(Instant.now().plus(Duration.ofDays(31)), false, 200);
        scheduler.runOnce(Instant.now(), true);

        String log = output.getAll();
        assertThat(log)
                .doesNotContain(harini.email())
                .doesNotContain("Harini")
                .doesNotContain("Subramanian")
                .doesNotContain("99887")
                .doesNotContain("76655")
                .doesNotContain(FILENAME)
                .doesNotContain(TEXT_ONLY_MARKER)
                .doesNotContain("Resume text:")
                .doesNotContain(token)
                .doesNotContain(PrivacyFixture.PASSWORD);
        assertThat(log).describedAs("the lifecycle did run and log").contains("Erasure");
    }
}
