package com.careerflux.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.ai.dto.AiResumeReading;
import com.careerflux.ai.policy.AiProcessingPolicy;
import com.careerflux.ai.policy.AiPurpose;
import com.careerflux.ai.quota.AiQuotaService;
import com.careerflux.ai.quota.QuotaDecision;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.common.error.AiUnavailableException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The boundary between a student's resume and an AI provider (Phase 2B, F8 and
 * the L-4 consent gate), with the provider replaced by a client that records
 * exactly what it was sent.
 */
class ResumeAiBoundaryTest {

    private static final UUID STUDENT = UUID.randomUUID();

    private static final String RESUME = """
            Aarav Sharma
            aarav.sharma@gmail.com | +91 98765 43210 | Hyderabad, Telangana
            linkedin.com/in/aarav-sharma

            EXPERIENCE
            Software Engineer Intern, Zoho Corporation, Chennai
            Jun 2024 - Aug 2024

            SKILLS
            Java, Spring Boot, PostgreSQL
            """;

    /** Records every prompt instead of sending it anywhere. */
    static final class RecordingAiClient implements AiClient {
        final List<String> systemPrompts = new ArrayList<>();
        final List<String> userPrompts = new ArrayList<>();
        final List<Class<?>> types = new ArrayList<>();
        AiResumeReading reply = new AiResumeReading("Backend developer", "Builds payment APIs.",
                "Backend Developer", "INTERN", 0.5, List.of("Java", "Spring Boot"), List.of(), List.of());
        boolean fail;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String modelName() {
            return "recording-model";
        }

        @Override
        public <T> T structured(String systemPrompt, String userPrompt, Class<T> type) {
            systemPrompts.add(systemPrompt);
            userPrompts.add(userPrompt);
            types.add(type);
            if (fail) {
                throw new AiUnavailableException("The AI service could not complete that request.");
            }
            return type.cast(reply);
        }

        @Override
        public String text(String systemPrompt, String userPrompt) {
            userPrompts.add(userPrompt);
            return "narrative";
        }
    }

    private RecordingAiClient client;
    private AiQuotaService quota;
    private AiProcessingPolicy policy;
    private ResumeExtractionService service;

    @BeforeEach
    void setUp() {
        client = new RecordingAiClient();
        quota = mock(AiQuotaService.class);
        when(quota.tryConsume(any())).thenReturn(QuotaDecision.allowed(10, 25));
        policy = mock(AiProcessingPolicy.class);
        CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);
        when(profiles.findKnownIdentityByUserId(STUDENT)).thenReturn(Optional.of(identity()));
        service = new ResumeExtractionService(client, quota, policy, new ResumeRedactor(), profiles);
    }

    private static CandidateProfileRepository.KnownIdentityView identity() {
        return new CandidateProfileRepository.KnownIdentityView() {
            @Override
            public String getFullName() {
                return "Aarav Sharma";
            }

            @Override
            public String getEmail() {
                return "aarav.sharma@gmail.com";
            }

            @Override
            public String getPhone() {
                return "+91 98765 43210";
            }
        };
    }

    @Test
    @DisplayName("without consent nothing is sent, no allowance is spent, and the upload still reads the resume")
    void noConsentNoCall() {
        when(policy.mayProcess(STUDENT, AiPurpose.RESUME_EXTRACTION)).thenReturn(false);

        var extraction = service.extract(STUDENT, RESUME);

        assertThat(client.userPrompts).isEmpty();
        verifyNoInteractions(quota);
        assertThat(extraction.aiAssisted()).isFalse();
        assertThat(extraction.notice()).isEqualTo(ResumeExtractionService.AI_NOT_PERMITTED_NOTICE);
        assertThat(extraction.resume().skills()).contains("Java", "Spring Boot", "PostgreSQL");
    }

    @Test
    @DisplayName("consent is asked before the allowance is charged")
    void gateComesBeforeQuota() {
        when(policy.mayProcess(STUDENT, AiPurpose.RESUME_EXTRACTION)).thenReturn(true);

        service.extract(STUDENT, RESUME);

        InOrder order = inOrder(policy, quota);
        order.verify(policy).mayProcess(STUDENT, AiPurpose.RESUME_EXTRACTION);
        order.verify(quota).tryConsume(STUDENT);
    }

    @Test
    @DisplayName("with consent, the provider receives the resume without a single identifier")
    void onlyRedactedTextIsSent() {
        when(policy.mayProcess(STUDENT, AiPurpose.RESUME_EXTRACTION)).thenReturn(true);

        service.extract(STUDENT, RESUME);

        assertThat(client.userPrompts).hasSize(1);
        String sent = client.userPrompts.get(0);
        assertThat(sent.toLowerCase()).doesNotContain("aarav").doesNotContain("sharma")
                .doesNotContain("gmail").doesNotContain("98765").doesNotContain("linkedin");
        assertThat(sent).isNotEqualTo("Resume text:\n\n" + RESUME)
                .contains("Zoho Corporation").contains("Java, Spring Boot, PostgreSQL");
        assertThat(sent).doesNotContainPattern("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
                .doesNotContainPattern("\\d{5}[\\s-]?\\d{5}");
    }

    @Test
    @DisplayName("text is redacted before it is shortened, so a cut can never leave half an identifier")
    void redactionComesBeforeTruncation() {
        when(policy.mayProcess(STUDENT, AiPurpose.RESUME_EXTRACTION)).thenReturn(true);
        // The address starts a few characters before the limit. Shortened first,
        // the model would receive "aarav", which no pattern recognises.
        String filler = "Java developer. ".repeat(ResumeExtractionService.MODEL_TEXT_LIMIT / 16);
        String text = filler.substring(0, ResumeExtractionService.MODEL_TEXT_LIMIT - 5)
                + "aarav.sharma@gmail.com and more text after it";

        CandidateProfileRepository noIdentity = mock(CandidateProfileRepository.class);
        ResumeExtractionService withoutIdentity =
                new ResumeExtractionService(client, quota, policy, new ResumeRedactor(), noIdentity);
        withoutIdentity.extract(STUDENT, text);

        assertThat(client.userPrompts.get(0)).doesNotContain("aarav");
    }

    @Test
    @DisplayName("the model is not asked for a name, email address or phone number")
    void promptDoesNotAskForIdentity() {
        when(policy.mayProcess(STUDENT, AiPurpose.RESUME_EXTRACTION)).thenReturn(true);

        service.extract(STUDENT, RESUME);

        assertThat(client.types).containsExactly(AiResumeReading.class);
        assertThat(Arrays.stream(AiResumeReading.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("fullName", "email", "phone", "location", "linkedinUrl", "githubUrl", "portfolioUrl");
        assertThat(client.systemPrompts.get(0).toLowerCase())
                .doesNotContain("full name").doesNotContain("email address").doesNotContain("phone number");
    }

    @Test
    @DisplayName("contact details on the proposal come from the local parser, and model text built on a placeholder is dropped")
    void identityIsReadLocally() {
        when(policy.mayProcess(STUDENT, AiPurpose.RESUME_EXTRACTION)).thenReturn(true);
        client.reply = new AiResumeReading("Backend developer", ResumeRedactor.CANDIDATE + " builds APIs.",
                "Backend Developer", null, null, List.of("Java"), List.of(), List.of());

        var extraction = service.extract(STUDENT, RESUME);

        assertThat(extraction.aiAssisted()).isTrue();
        assertThat(extraction.resume().email()).isEqualTo("aarav.sharma@gmail.com");
        assertThat(extraction.resume().headline()).isEqualTo("Backend developer");
        assertThat(extraction.resume().summary()).isNull();
    }

    @Test
    @DisplayName("a provider failure refunds the allowance and falls back to the local parser")
    void providerFailureFallsBack() {
        when(policy.mayProcess(STUDENT, AiPurpose.RESUME_EXTRACTION)).thenReturn(true);
        client.fail = true;

        var extraction = service.extract(STUDENT, RESUME);

        verify(quota).refund(STUDENT);
        assertThat(extraction.aiAssisted()).isFalse();
    }

    @Test
    @DisplayName("with AI unconfigured the gate and the allowance are never consulted")
    void unconfiguredNeverAsks() {
        AiProcessingPolicy untouched = mock(AiProcessingPolicy.class);
        ResumeExtractionService offline = new ResumeExtractionService(new UnavailableAiClient(), quota, untouched,
                new ResumeRedactor(), mock(CandidateProfileRepository.class));

        offline.extract(STUDENT, RESUME);

        verify(untouched, never()).mayProcess(any(), any());
        verifyNoInteractions(quota);
    }
}
