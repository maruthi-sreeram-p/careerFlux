package com.careerflux.ingestion.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.careerflux.job.domain.SkillRequirement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Skill tier classification.
 *
 * <p>The behaviour this replaces wrote every dictionary match as REQUIRED, which
 * produced a corpus of 7,609 job-skill rows without a single PREFERRED. The
 * tests below are the specific distinctions that were previously impossible.
 */
class SkillRequirementClassifierTest {

    private final SkillRequirementClassifier classifier = new SkillRequirementClassifier();

    private SkillRequirement tier(String skill, String text) {
        Map<String, SkillRequirement> result = classifier.classify(List.of(skill), text);
        return result.get(skill);
    }

    @Nested
    @DisplayName("section headings")
    class Sections {

        private static final String POSTING = """
                Backend Engineer

                About the team
                We are a small team building payments infrastructure.

                Requirements:
                - Strong Java and Spring Boot
                - PostgreSQL

                Nice to have:
                - Kafka
                - Redis

                Responsibilities
                You will collaborate with our Python team on shared services.
                """;

        @Test
        @DisplayName("a skill under Requirements is REQUIRED")
        void requirementsSection() {
            assertThat(tier("Java", POSTING)).isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Spring Boot", POSTING)).isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("PostgreSQL", POSTING)).isEqualTo(SkillRequirement.REQUIRED);
        }

        @Test
        @DisplayName("a skill under Nice to have is PREFERRED")
        void preferredSection() {
            assertThat(tier("Kafka", POSTING)).isEqualTo(SkillRequirement.PREFERRED);
            assertThat(tier("Redis", POSTING)).isEqualTo(SkillRequirement.PREFERRED);
        }

        @Test
        @DisplayName("a skill named only in the responsibilities is OPTIONAL")
        void contextualMentionIsOptional() {
            // The exact case from the brief: "collaborate with our Python team"
            // must not make Python a requirement.
            assertThat(tier("Python", POSTING)).isEqualTo(SkillRequirement.OPTIONAL);
        }

        @Test
        @DisplayName("a neutral heading ends the previous section")
        void neutralHeadingClosesTheSection() {
            // Python sits under "Responsibilities", after the Nice-to-have list.
            // Without closing that section it would inherit PREFERRED.
            assertThat(tier("Python", POSTING)).isNotEqualTo(SkillRequirement.PREFERRED);
        }

        @Test
        @DisplayName("Preferred qualifications is preferred, not required")
        void preferredQualificationsIsNotRequirements() {
            String text = """
                    Minimum qualifications:
                    - Java

                    Preferred qualifications:
                    - Kubernetes
                    """;
            assertThat(tier("Java", text)).isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Kubernetes", text)).isEqualTo(SkillRequirement.PREFERRED);
        }
    }

    @Nested
    @DisplayName("the shape real postings actually arrive in")
    class RealPostings {

        /**
         * Taken verbatim from a bundled fixture after HTML stripping: bullets
         * rendered as glyphs, headings in sentence case rather than title case.
         * An earlier heading test required most words capitalised, so it found
         * "Requirements" and missed "Nice to have" — and every preferred skill
         * in the corpus stayed classified as required.
         */
        private static final String STRIPPED_HTML = """
                Northwind Systems is looking for a Java Backend Developer to join the platform team.
                What you will do
                • Build and maintain REST APIs with Java and Spring Boot
                • Model data in PostgreSQL and tune the queries behind it
                Requirements
                • 1-3 years of professional backend experience
                • Strong Java fundamentals
                • Spring Boot and Spring Security in production
                • Comfortable with SQL and PostgreSQL
                Nice to have
                • AWS or another cloud provider
                • Kafka or another message broker
                • Docker
                """;

        @Test
        @DisplayName("sentence-case headings are recognised")
        void sentenceCaseHeadings() {
            assertThat(tier("Java", STRIPPED_HTML)).isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Spring Boot", STRIPPED_HTML)).isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("AWS", STRIPPED_HTML)).isEqualTo(SkillRequirement.PREFERRED);
            assertThat(tier("Kafka", STRIPPED_HTML)).isEqualTo(SkillRequirement.PREFERRED);
            assertThat(tier("Docker", STRIPPED_HTML)).isEqualTo(SkillRequirement.PREFERRED);
        }

        @Test
        @DisplayName("a bullet is never mistaken for a heading")
        void bulletsAreNotHeadings() {
            // "• Docker" is short and unpunctuated; only the leading glyph
            // distinguishes it from a heading.
            assertThat(tier("Docker", STRIPPED_HTML)).isEqualTo(SkillRequirement.PREFERRED);
        }

        @Test
        @DisplayName("a real posting produces all three tiers")
        void allThreeTiers() {
            Map<String, SkillRequirement> tiers = classifier.classify(
                    List.of("Java", "Spring Boot", "PostgreSQL", "AWS", "Kafka", "Docker", "REST APIs"),
                    STRIPPED_HTML);
            assertThat(tiers.values()).contains(SkillRequirement.REQUIRED, SkillRequirement.PREFERRED);
            assertThat(tiers.get("REST APIs"))
                    .as("named under what-you-will-do, not asked for")
                    .isEqualTo(SkillRequirement.OPTIONAL);
        }
    }

    @Nested
    @DisplayName("defects found by the corpus audit")
    class AuditRegressions {

        @Test
        @DisplayName("a cue in one bullet does not reach a skill in the next")
        void cueDoesNotCrossBulletBoundaries() {
            // Kubernetes is named in a bullet that asks for nothing; the cue
            // belongs to the bullet after it. Canonicalisation used to erase the
            // line break between them, so the window read straight across and
            // made Kubernetes a hard requirement.
            String posting = """
                    • Our platform runs on Kubernetes today.
                    • Proficiency in Python is what this role needs.
                    """;
            assertThat(tier("Kubernetes", posting))
                    .as("a cue in the next bullet must not reach back")
                    .isEqualTo(SkillRequirement.OPTIONAL);
            assertThat(tier("Python", posting))
                    .as("the cue still applies inside its own bullet")
                    .isEqualTo(SkillRequirement.REQUIRED);
        }

        @Test
        @DisplayName("a company blurb does not become a requirement")
        void blurbLanguageDoesNotLeak() {
            String posting = """
                    At Twilio we are shaping the future of communications.
                    Proficiency in Python is what this role needs.
                    """;
            // "Communication" appears only in the marketing sentence; the cue
            // belongs to the sentence after it.
            assertThat(tier("Communication", posting)).isNotEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Python", posting)).isEqualTo(SkillRequirement.REQUIRED);
        }

        @Test
        @DisplayName("experience with X is requirement language")
        void experienceWithIsARequirement() {
            // Present in 1,443 of 1,947 real postings and previously unmatched,
            // which sent most genuinely required skills to OPTIONAL.
            assertThat(tier("Java", "Experience with Java in a production setting."))
                    .isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Kubernetes", "Hands-on experience with Kubernetes."))
                    .isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Python", "Expertise in Python and its ecosystem."))
                    .isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("SQL", "Working knowledge of SQL."))
                    .isEqualTo(SkillRequirement.REQUIRED);
        }

        @Test
        @DisplayName("preference language still beats the new requirement phrases")
        void preferenceStillWinsTies() {
            // "familiarity with" and "experience with" both match; the posting
            // plainly means the skill is optional.
            assertThat(tier("Kafka", "Familiarity with Kafka is a plus."))
                    .isEqualTo(SkillRequirement.PREFERRED);
            assertThat(tier("Redis", "Experience with Redis would be a bonus."))
                    .isEqualTo(SkillRequirement.PREFERRED);
        }

        @Test
        @DisplayName("collaborative language is still not a requirement")
        void collaborationStillDoesNotRequire() {
            // The new phrases must not swallow the contextual case: this says
            // nothing about what the candidate needs to know.
            assertThat(tier("Python", "You will work with our Python team on shared tooling."))
                    .isEqualTo(SkillRequirement.OPTIONAL);
        }
    }

    @Nested
    @DisplayName("final requirement-phrasing pass")
    class FinalPhrasings {

        @Test
        @DisplayName("experience working on / in / with is requirement language")
        void experienceWorking() {
            assertThat(tier("Java", "Experience working on Java services in production."))
                    .isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Python", "Experience working in Python across data pipelines."))
                    .isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("AWS", "Experience working with AWS is required."))
                    .isEqualTo(SkillRequirement.REQUIRED);
        }

        @Test
        @DisplayName("deep understanding of is requirement language")
        void deepUnderstanding() {
            assertThat(tier("Kafka", "Deep understanding of Kafka and event streaming."))
                    .isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Kubernetes", "Deep knowledge of Kubernetes networking."))
                    .isEqualTo(SkillRequirement.REQUIRED);
        }

        @Test
        @DisplayName("ability to write / build / develop is requirement language")
        void abilityTo() {
            assertThat(tier("SQL", "Ability to write SQL against large datasets."))
                    .isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("React", "Ability to build React interfaces from a design."))
                    .isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Java", "Ability to develop Java services end to end."))
                    .isEqualTo(SkillRequirement.REQUIRED);
        }

        @Test
        @DisplayName("comfortable with and well-versed in are requirement language")
        void comfortableAndWellVersed() {
            assertThat(tier("Java", "Comfortable with Java and its ecosystem."))
                    .isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Docker", "Well-versed in Docker and container tooling."))
                    .isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Terraform", "Well versed in Terraform modules."))
                    .isEqualTo(SkillRequirement.REQUIRED);
        }

        @Test
        @DisplayName("preference language beats every new phrase")
        void preferenceStillWins() {
            assertThat(tier("AWS", "Experience working with AWS is a plus."))
                    .isEqualTo(SkillRequirement.PREFERRED);
            assertThat(tier("Kafka", "Deep understanding of Kafka is preferred."))
                    .isEqualTo(SkillRequirement.PREFERRED);
            assertThat(tier("Java", "Comfortable with Java would be a bonus."))
                    .isEqualTo(SkillRequirement.PREFERRED);
            assertThat(tier("Docker", "Well-versed in Docker is nice to have."))
                    .isEqualTo(SkillRequirement.PREFERRED);
            assertThat(tier("SQL", "Ability to write SQL is desirable."))
                    .isEqualTo(SkillRequirement.PREFERRED);
        }

        @Test
        @DisplayName("the new phrases do not swallow contextual mentions")
        void contextualMentionsSurvive() {
            // None of these say the candidate needs the technology.
            assertThat(tier("Python", "You will work with our Python team on tooling."))
                    .isEqualTo(SkillRequirement.OPTIONAL);
            assertThat(tier("Kubernetes", "Our platform is built on Kubernetes."))
                    .isEqualTo(SkillRequirement.OPTIONAL);
            assertThat(tier("Kafka", "We are migrating off Kafka this year."))
                    .isEqualTo(SkillRequirement.OPTIONAL);
        }

        @Test
        @DisplayName("a bare Required: heading is recognised")
        void bareRequiredHeading() {
            // 49 postings use this form. "requirements?" does not match "required",
            // so everything under it used to fall through to the sentence path.
            String posting = """
                    Senior Engineer

                    Required:
                    • Java and Spring Boot
                    • PostgreSQL

                    Preferred:
                    • Kafka
                    """;
            assertThat(tier("Java", posting)).isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("PostgreSQL", posting)).isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Kafka", posting)).isEqualTo(SkillRequirement.PREFERRED);
        }

        @Test
        @DisplayName("a long adjective-to-skills span still reads as a requirement")
        void longSoftSkillSpan() {
            // 558 postings have more than 30 characters between the adjective and
            // the word "skills", which the earlier bound could not span.
            assertThat(tier("Communication",
                    "Excellent problem-solving and communication skills are needed here."))
                    .isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Communication", "Strong communication skills."))
                    .isEqualTo(SkillRequirement.REQUIRED);
        }
    }

    @Nested
    @DisplayName("sentence language, where a posting has no headings")
    class Sentences {

        @Test
        @DisplayName("explicit requirement language produces REQUIRED")
        void requirementPhrases() {
            assertThat(tier("Java", "You must have Java to succeed here."))
                    .isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Python", "Python is required for this role."))
                    .isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("AWS", "At least 3 years of AWS in production."))
                    .isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Docker", "Strong experience with Docker."))
                    .isEqualTo(SkillRequirement.REQUIRED);
        }

        @Test
        @DisplayName("explicit preference language produces PREFERRED")
        void preferencePhrases() {
            assertThat(tier("Kafka", "Kafka is a plus."))
                    .isEqualTo(SkillRequirement.PREFERRED);
            assertThat(tier("Redis", "Nice to have: Redis."))
                    .isEqualTo(SkillRequirement.PREFERRED);
            assertThat(tier("GraphQL", "Ideally you have touched GraphQL before."))
                    .isEqualTo(SkillRequirement.PREFERRED);
        }

        @Test
        @DisplayName("a bare mention produces OPTIONAL")
        void bareMention() {
            assertThat(tier("Python", "We work with Python across the platform."))
                    .isEqualTo(SkillRequirement.OPTIONAL);
            assertThat(tier("Kubernetes", "Our stack uses Kubernetes."))
                    .isEqualTo(SkillRequirement.OPTIONAL);
            assertThat(tier("Terraform", "The team next door owns the Terraform modules."))
                    .isEqualTo(SkillRequirement.OPTIONAL);
        }

        @Test
        @DisplayName("preference language wins when both appear around one mention")
        void preferenceBeatsAmbientRequirementLanguage() {
            // The paragraph talks about required experience, but this specific
            // clause marks the skill as a bonus.
            assertThat(tier("Kafka",
                    "Required: 3 years of backend work. Kafka would be a bonus."))
                    .isEqualTo(SkillRequirement.PREFERRED);
        }
    }

    @Nested
    @DisplayName("across the whole document")
    class Aggregation {

        @Test
        @DisplayName("the strongest mention wins")
        void strongestMentionWins() {
            String text = """
                    Requirements:
                    - Java

                    About us
                    We work with Java and a little Scala.
                    """;
            // Mentioned twice, once as a requirement and once in passing.
            assertThat(tier("Java", text)).isEqualTo(SkillRequirement.REQUIRED);
            assertThat(tier("Scala", text)).isEqualTo(SkillRequirement.OPTIONAL);
        }

        @Test
        @DisplayName("a skill the text never mentions is OPTIONAL rather than an error")
        void absentSkill() {
            assertThat(tier("Rust", "A Java posting with no Rust anywhere."))
                    .isEqualTo(SkillRequirement.OPTIONAL);
        }

        @Test
        @DisplayName("empty input is handled without throwing")
        void emptyInput() {
            assertThat(classifier.classify(List.of(), "text")).isEmpty();
            assertThat(classifier.classify(List.of("Java"), null))
                    .containsEntry("Java", SkillRequirement.OPTIONAL);
            assertThat(classifier.classify(List.of("Java"), "   "))
                    .containsEntry("Java", SkillRequirement.OPTIONAL);
        }

        @Test
        @DisplayName("word boundaries are respected")
        void wordBoundaries() {
            // "Java" must not match inside "JavaScript".
            assertThat(tier("Java", "Requirements: JavaScript only."))
                    .isEqualTo(SkillRequirement.OPTIONAL);
        }

        @Test
        @DisplayName("classification preserves the order it was given")
        void preservesOrder() {
            Map<String, SkillRequirement> result = classifier.classify(
                    List.of("Java", "Kafka", "Python"),
                    "Requirements: Java. Kafka is a plus. We work with Python.");
            assertThat(result.keySet()).containsExactly("Java", "Kafka", "Python");
        }

        @Test
        @DisplayName("a realistic posting produces a mix, not all-REQUIRED")
        void producesAMixture() {
            String text = """
                    Senior Backend Engineer

                    What you'll need:
                    - 4+ years of Java
                    - Spring Boot in production
                    - PostgreSQL

                    Bonus points:
                    - Kafka
                    - Elasticsearch

                    Responsibilities
                    Partner with our Python and Go teams on shared tooling.
                    """;
            Map<String, SkillRequirement> tiers = classifier.classify(
                    List.of("Java", "Spring Boot", "PostgreSQL", "Kafka", "Elasticsearch", "Python", "Go"),
                    text);

            assertThat(tiers.values()).contains(
                    SkillRequirement.REQUIRED, SkillRequirement.PREFERRED, SkillRequirement.OPTIONAL);
            assertThat(tiers.values().stream().filter(t -> t == SkillRequirement.REQUIRED).count())
                    .as("not everything is required")
                    .isLessThan(tiers.size());
        }
    }
}
