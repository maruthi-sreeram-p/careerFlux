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

    /**
     * A resume with the sections people actually write, used by the tests below.
     * It is fictional, and it is the layout this parser is expected to read: a
     * contact block, then headings, with the entries under them.
     */
    private static final String STRUCTURED = """
            Ananya Sharma
            Hyderabad, Telangana, India
            ananya.sharma@example.com | +91 98765 43210
            linkedin.com/in/ananya-sharma-dev | github.com/ananyasharma

            SUMMARY
            Final-year Computer Science undergraduate with 2 years of experience
            building backend services through internships.

            EDUCATION
            Northgate Institute of Technology, Hyderabad
            Bachelor of Technology, Computer Science and Engineering
            2022 - 2026 | CGPA: 8.7 / 10

            Sri Chaitanya Junior College, Hyderabad
            Intermediate (MPC), 2020 - 2022 | 95.4%

            EXPERIENCE
            Backend Engineering Intern, Zyntara Technologies, Bengaluru
            June 2025 - December 2025
            - Built REST APIs in Java and Spring Boot serving 40,000 daily requests.
            - Raised coverage from 38% to 81%.

            Software Engineering Intern, Vellore Analytics, Remote
            May 2024 - July 2024
            - Developed Python ETL scripts.

            PROJECTS
            CampusConnect, a placement portal built with Spring Boot and React.

            SKILLS
            Java, Spring Boot, Python, Docker
            """;

    @Test
    @DisplayName("reads the summary section as a paragraph")
    void readsTheSummary() {
        ExtractedResume resume = service.heuristic(STRUCTURED);

        assertThat(resume.summary())
                .startsWith("Final-year Computer Science undergraduate")
                .endsWith("through internships.")
                // The section is one paragraph, not the lines it was wrapped over.
                .doesNotContain("\n");
    }

    @Test
    @DisplayName("reads each education entry, including the degree, the years and the grade")
    void readsEducation() {
        ExtractedResume resume = service.heuristic(STRUCTURED);

        assertThat(resume.education()).hasSize(2);

        ExtractedResume.ExtractedEducation degree = resume.education().get(0);
        assertThat(degree.institution()).isEqualTo("Northgate Institute of Technology");
        assertThat(degree.degree()).isEqualTo("Bachelor of Technology");
        assertThat(degree.fieldOfStudy()).isEqualTo("Computer Science and Engineering");
        assertThat(degree.startYear()).isEqualTo(2022);
        assertThat(degree.endYear()).isEqualTo(2026);
        assertThat(degree.grade()).isEqualTo("CGPA: 8.7 / 10");

        ExtractedResume.ExtractedEducation school = resume.education().get(1);
        assertThat(school.institution()).isEqualTo("Sri Chaitanya Junior College");
        assertThat(school.degree()).isEqualTo("Intermediate (MPC)");
        assertThat(school.grade()).isEqualTo("95.4%");
    }

    @Test
    @DisplayName("reads each employment entry with its employer, dates and bullets")
    void readsExperience() {
        ExtractedResume resume = service.heuristic(STRUCTURED);

        assertThat(resume.experiences()).hasSize(2);

        ExtractedResume.ExtractedExperience recent = resume.experiences().get(0);
        assertThat(recent.title()).isEqualTo("Backend Engineering Intern");
        assertThat(recent.companyName()).isEqualTo("Zyntara Technologies");
        assertThat(recent.location()).isEqualTo("Bengaluru");
        assertThat(recent.startDate()).isEqualTo("2025-06");
        assertThat(recent.endDate()).isEqualTo("2025-12");
        assertThat(recent.current()).isFalse();
        assertThat(recent.description()).contains("Built REST APIs").contains("Raised coverage");

        ExtractedResume.ExtractedExperience earlier = resume.experiences().get(1);
        assertThat(earlier.companyName()).isEqualTo("Vellore Analytics");
        assertThat(earlier.startDate()).isEqualTo("2024-05");
        assertThat(earlier.endDate()).isEqualTo("2024-07");
    }

    @Test
    @DisplayName("takes the role and the level from the most recent job title")
    void readsRoleAndSeniority() {
        ExtractedResume resume = service.heuristic(STRUCTURED);

        assertThat(resume.primaryRole()).isEqualTo("Backend Engineering Intern");
        assertThat(resume.seniority()).isEqualTo("INTERN");
    }

    @Test
    @DisplayName("an open-ended role is current and has no end date")
    void readsAnOngoingRole() {
        ExtractedResume resume = service.heuristic("""
                EXPERIENCE
                Senior Software Engineer at Vertex Labs
                July 2024 - Present
                """);

        ExtractedResume.ExtractedExperience entry = resume.experiences().get(0);
        assertThat(entry.title()).isEqualTo("Senior Software Engineer");
        assertThat(entry.companyName()).isEqualTo("Vertex Labs");
        assertThat(entry.startDate()).isEqualTo("2024-07");
        assertThat(entry.endDate()).isNull();
        assertThat(entry.current()).isTrue();
        assertThat(resume.seniority()).isEqualTo("SENIOR");
    }

    @Test
    @DisplayName("reads an entry that leads with the employer rather than the role")
    void readsEmployerFirstLayout() {
        ExtractedResume resume = service.heuristic("""
                WORK EXPERIENCE
                Zyntara Technologies - Data Analyst
                2023 - 2024
                """);

        ExtractedResume.ExtractedExperience entry = resume.experiences().get(0);
        assertThat(entry.companyName()).isEqualTo("Zyntara Technologies");
        assertThat(entry.title()).isEqualTo("Data Analyst");
        assertThat(entry.startDate()).isEqualTo("2023");
    }

    @Test
    @DisplayName("separates entries that were written without a blank line between them")
    void separatesEntriesWithoutBlankLines() {
        ExtractedResume resume = service.heuristic("""
                EXPERIENCE
                Backend Intern, Zyntara Technologies
                June 2025 - December 2025
                - Built APIs.
                Data Intern, Vellore Analytics
                May 2024 - July 2024
                """);

        assertThat(resume.experiences()).hasSize(2);
        assertThat(resume.experiences().get(1).companyName()).isEqualTo("Vellore Analytics");
    }

    @Test
    @DisplayName("a section it does not read is not mistaken for employment")
    void doesNotReadProjectsAsEmployment() {
        ExtractedResume resume = service.heuristic(STRUCTURED);

        assertThat(resume.experiences())
                .extracting(ExtractedResume.ExtractedExperience::companyName)
                .doesNotContain("CampusConnect");
        assertThat(resume.education())
                .extracting(ExtractedResume.ExtractedEducation::institution)
                .doesNotContain("CampusConnect");
    }

    @Test
    @DisplayName("leaves the headline alone when the resume does not state one")
    void doesNotInventAHeadline() {
        // The summary is prose about the candidate, not a title they go by, and
        // promoting it would put a paragraph in a 200-character field.
        assertThat(service.heuristic(STRUCTURED).headline()).isNull();
    }

    @Test
    @DisplayName("reads a title line written under the name as the headline")
    void readsAStatedHeadline() {
        ExtractedResume resume = service.heuristic("""
                Ananya Sharma
                Backend Developer
                ananya@example.com
                """);

        assertThat(resume.headline()).isEqualTo("Backend Developer");
    }

    @Test
    @DisplayName("does not record a level the title does not state")
    void leavesSeniorityUnsetWhenTheTitleIsSilent() {
        ExtractedResume resume = service.heuristic("""
                EXPERIENCE
                Software Engineer, Vertex Labs
                2021 - 2024
                """);

        assertThat(resume.primaryRole()).isEqualTo("Software Engineer");
        // Four years of it does not make somebody MID. The resume did not say.
        assertThat(resume.seniority()).isNull();
    }

    @Test
    @DisplayName("a heading with nothing under it produces no entries and no summary")
    void emptySectionsProduceNothing() {
        ExtractedResume resume = service.heuristic("""
                SUMMARY

                EXPERIENCE

                EDUCATION
                """);

        assertThat(resume.summary()).isNull();
        assertThat(resume.experiences()).isEmpty();
        assertThat(resume.education()).isEmpty();
    }

    @Test
    @DisplayName("an education entry with no institution is dropped rather than half-proposed")
    void dropsEducationWithoutAnInstitution() {
        // The profile requires an institution, so an entry without one could not
        // be written even if the student accepted it.
        ExtractedResume resume = service.heuristic("""
                EDUCATION
                2022 - 2026
                """);

        assertThat(resume.education()).isEmpty();
    }
}
