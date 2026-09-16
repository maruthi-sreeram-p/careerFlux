package com.careerflux.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.careerflux.ai.ResumeRedactor.KnownIdentity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What leaves CareerFlux for an AI provider, and what does not (Phase 2B, F8).
 *
 * <p>The resume below is shaped like the ones Indian engineering students
 * actually upload: a contact block at the top with a street address and a PIN
 * code, profile links, a date of birth and ID numbers in "personal details" at
 * the bottom, and a degree written "B.Tech".
 */
class ResumeRedactorTest {

    private final ResumeRedactor redactor = new ResumeRedactor();

    private static final KnownIdentity AARAV =
            new KnownIdentity("Aarav Sharma", "aarav.sharma@gmail.com", "+91 98765 43210");

    private static final String RESUME = """
            AARAV SHARMA
            aarav.sharma@gmail.com | +91 98765 43210 | Flat 12, Road No. 3, Banjara Hills
            Hyderabad, Telangana, 500034
            linkedin.com/in/aarav-sharma | https://github.com/aaravsharma | aaravsharma.dev
            Date of Birth: 14/08/2003
            Photo: aarav_photo.jpg

            SUMMARY
            Aarav is a backend developer who built payment APIs at Zoho.

            EXPERIENCE
            Software Engineer Intern, Zoho Corporation, Chennai
            Jun 2024 - Aug 2024
            Built REST APIs in Java and Spring Boot; reduced latency by 30%.

            EDUCATION
            B.Tech in Computer Science, JNTU Hyderabad, 2021-2025, CGPA 8.4/10

            SKILLS
            Java, Spring Boot, ASP.NET, Socket.io, PostgreSQL, C++, React

            PERSONAL DETAILS
            Aadhaar: 2345 6789 0123
            PAN: ABCDE1234F
            Permanent address: 4-12-7, Gandhi Nagar, Warangal
            Alternate phone: 040-23456789
            """;

    @Nested
    @DisplayName("identifiers")
    class Identifiers {

        @Test
        @DisplayName("none of the student's identifiers survive")
        void everyIdentifierIsRemoved() {
            String redacted = redactor.redact(RESUME, AARAV);

            assertThat(redacted.toLowerCase())
                    .doesNotContain("aarav")
                    .doesNotContain("sharma")
                    .doesNotContain("gmail")
                    .doesNotContain("98765")
                    .doesNotContain("43210")
                    .doesNotContain("banjara")
                    .doesNotContain("500034")
                    .doesNotContain("linkedin")
                    .doesNotContain("github")
                    .doesNotContain("14/08/2003")
                    .doesNotContain("photo.jpg")
                    .doesNotContain("2345 6789 0123")
                    .doesNotContain("abcde1234f")
                    .doesNotContain("gandhi nagar")
                    .doesNotContain("23456789");
            assertThat(redacted).contains(ResumeRedactor.CANDIDATE);
        }

        @Test
        @DisplayName("the account's own details are found however the resume writes them")
        void knownIdentityInOtherShapes() {
            String text = """
                    Sharma, Aarav
                    Reach me at AARAV.SHARMA@GMAIL.COM or 98765-43210
                    """;

            String redacted = redactor.redact(text, AARAV);

            assertThat(redacted.toLowerCase()).doesNotContain("aarav").doesNotContain("sharma")
                    .doesNotContain("98765").doesNotContain("43210");
        }

        @Test
        @DisplayName("identifiers are removed even when the account knows nothing about the student")
        void patternsWorkWithoutAKnownIdentity() {
            String redacted = redactor.redact(RESUME, KnownIdentity.none());

            assertThat(redacted).doesNotContain("aarav.sharma@gmail.com").doesNotContain("98765 43210")
                    .doesNotContain("linkedin.com").doesNotContain("ABCDE1234F").doesNotContain("040-23456789");
        }
    }

    @Nested
    @DisplayName("what a resume is read for")
    class WhatStays {

        @Test
        @DisplayName("employers, titles, dates, degrees, institutions and skills are kept")
        void workHistoryIsKept() {
            String redacted = redactor.redact(RESUME, AARAV);

            assertThat(redacted)
                    .contains("Software Engineer Intern")
                    .contains("Zoho Corporation")
                    .contains("Chennai")
                    .contains("Jun 2024 - Aug 2024")
                    .contains("B.Tech in Computer Science")
                    .contains("JNTU Hyderabad")
                    .contains("2021-2025")
                    .contains("CGPA 8.4/10")
                    .contains("reduced latency by 30%")
                    .contains("Java, Spring Boot, ASP.NET, Socket.io, PostgreSQL, C++, React");
        }

        @Test
        @DisplayName("a name part spelled like a technology is removed only as part of the full name")
        void technologiesThatAreAlsoNamesAreKept() {
            String text = """
                    Ram Kotlin
                    Laptop with 16GB RAM. Built Android apps in Kotlin.
                    """;

            String redacted = redactor.redact(text, new KnownIdentity("Ram Kotlin", null, null));

            assertThat(redacted).startsWith(ResumeRedactor.CANDIDATE).contains("16GB RAM").contains("apps in Kotlin");
        }

        @Test
        @DisplayName("years written in a row are not mistaken for an ID number")
        void yearsAreNotIdentityNumbers() {
            String redacted = redactor.redact("Hackathons: 2019 2020 2021\nInternships 2022-2023", KnownIdentity.none());

            assertThat(redacted).contains("2019 2020 2021").contains("2022-2023");
        }
    }

    @Test
    @DisplayName("the same input always gives the same output, and empty input passes through")
    void deterministic() {
        assertThat(redactor.redact(RESUME, AARAV)).isEqualTo(redactor.redact(RESUME, AARAV));
        assertThat(redactor.redact(null, AARAV)).isNull();
        assertThat(redactor.redact("  ", AARAV)).isEqualTo("  ");
    }
}
