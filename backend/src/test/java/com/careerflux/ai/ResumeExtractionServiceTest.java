package com.careerflux.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.careerflux.ai.quota.AiQuotaService;
import com.careerflux.ai.dto.ExtractedResume;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deterministic fallback that runs when no model is configured.
 *
 * <p>What matters is that it is honest: it should find what it can and leave the
 * rest null, rather than filling a profile with confident guesses that a
 * candidate then has to hunt down and correct.
 */
class ResumeExtractionServiceTest {

    /**
     * With no model configured the quota is never consulted — there is nothing
     * to charge for — so a mock that would fail loudly if called is the right
     * stand-in here. Quota behaviour has its own tests.
     */
    private final ResumeExtractionService service = new ResumeExtractionService(
            new UnavailableAiClient(), org.mockito.Mockito.mock(AiQuotaService.class),
            org.mockito.Mockito.mock(com.careerflux.ai.policy.AiProcessingPolicy.class), new ResumeRedactor(),
            org.mockito.Mockito.mock(com.careerflux.candidate.repository.CandidateProfileRepository.class));

    private static final java.util.UUID USER = java.util.UUID.randomUUID();

    private static final String RESUME = """
            Maruthi Sreeram
            maruthi@example.com | +91 98765 43210 | Hyderabad, India
            linkedin.com/in/maruthi-sreeram | github.com/maruthisreeram

            SUMMARY
            Backend developer with 2 years of experience building REST APIs.

            SKILLS
            Java, Spring Boot, PostgreSQL, Docker, REST APIs, Git

            EXPERIENCE
            Software Engineer, Vertex Labs
            July 2024 - Present
            """;

    @Test
    @DisplayName("reports that it used the heuristic path and warns the candidate")
    void reportsItsOwnLimitations() {
        var extraction = service.extract(USER, RESUME);

        assertThat(extraction.aiAssisted()).isFalse();
        assertThat(extraction.engine()).isEqualTo("heuristic");
        assertThat(extraction.notice()).contains("AI is not configured");
    }

    @Test
    @DisplayName("finds contact details")
    void findsContactDetails() {
        ExtractedResume resume = service.heuristic(RESUME);

        assertThat(resume.fullName()).isEqualTo("Maruthi Sreeram");
        assertThat(resume.email()).isEqualTo("maruthi@example.com");
        assertThat(resume.linkedinUrl()).contains("linkedin.com/in/maruthi-sreeram");
        assertThat(resume.githubUrl()).contains("github.com/maruthisreeram");
        assertThat(resume.location()).isEqualTo("Hyderabad, India");
        assertThat(resume.phone()).isEqualTo("+91 98765 43210");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "+91 98765 43210", "98765 43210", "+91-98765-43210", "+91 98765-43210", "(+91) 98765 43210",
            "+91 9876543210", "+919876543210", "9876543210", "09876543210", "040 2345 6789",
            "040-23456789", "+1 415 555 0132"})
    @DisplayName("finds a phone number however it is grouped")
    void findsPhoneNumbersInTheFormatsPeopleWrite(String phone) {
        ExtractedResume resume = service.heuristic("Ananya Krishnan\nHyderabad, Telangana | " + phone
                + " | ananya@example.com\n");
        assertThat(resume.phone()).isEqualTo(phone);
    }

    @Test
    @DisplayName("finds the phone even when other numbers follow it on the same line")
    void findsPhoneBeforeTrailingNumbers() {
        assertThat(service.heuristic("Contact: 9876543210 2019-2023").phone()).isEqualTo("9876543210");
    }

    @Test
    @DisplayName("does not mistake years, marks or identity numbers for a phone")
    void ignoresNumbersThatAreNotPhones() {
        ExtractedResume resume = service.heuristic("""
                Ananya Krishnan
                B.Tech CSE, 2022-2026, CGPA 8.4
                Class XII 2020 - 2022, 94%
                Roll number 2022040123
                ID 1234 5678 9012
                """);
        assertThat(resume.phone()).isNull();
    }

    @Test
    @DisplayName("finds known skills and reads the stated years of experience")
    void findsSkillsAndExperience() {
        ExtractedResume resume = service.heuristic(RESUME);

        assertThat(resume.skills()).contains("Java", "Spring Boot", "PostgreSQL", "Docker", "Git");
        assertThat(resume.yearsExperience()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("matches skill terms only on word boundaries")
    void doesNotMatchSubstrings() {
        // "Go" must not be found inside "Google", and "R" must not match everything.
        ExtractedResume resume = service.heuristic("I worked at Google on a large program.");
        assertThat(resume.skills()).doesNotContain("Go", "R", "C");
    }

    @Test
    @DisplayName("leaves what it cannot determine as null")
    void leavesUnknownsNull() {
        ExtractedResume resume = service.heuristic("Just some text with no structure at all.");

        assertThat(resume.yearsExperience()).isNull();
        assertThat(resume.email()).isNull();
        assertThat(resume.seniority()).isNull();
        assertThat(resume.experiences()).isEmpty();
    }

    @Test
    @DisplayName("empty input produces an empty profile and says why, rather than throwing")
    void handlesEmptyInput() {
        var extraction = service.extract(USER, "   ");

        assertThat(extraction.resume().skills()).isEmpty();
        assertThat(extraction.notice()).contains("No readable text");
    }

    @Test
    @DisplayName("reports AI as unavailable when no model is configured")
    void reportsAiUnavailable() {
        assertThat(service.isAiAvailable()).isFalse();
        assertThat(service.engineName()).isEqualTo("heuristic");
    }
}
