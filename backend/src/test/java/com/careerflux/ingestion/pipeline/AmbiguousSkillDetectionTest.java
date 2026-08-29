package com.careerflux.ingestion.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.careerflux.skill.Skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Skill names that are also ordinary English words.
 *
 * <p>"Go" is the case the corpus audit surfaced. Of its standalone occurrences,
 * roughly 325 were "go-to-market" and 26 were "go live" — canonicalisation turns
 * the hyphen into a space, so a sales phrase read as a programming-language
 * requirement. These tests pin both directions: the false friends must not
 * produce the skill, and the genuine mentions must still be found.
 */
class AmbiguousSkillDetectionTest {

    private final JobEnricher enricher =
            new JobEnricher(null, null, null, new SkillRequirementClassifier());

    private static final List<Skill> DICTIONARY = List.of(
            skill("Go", "go"), skill("Java", "java"), skill("Python", "python"));

    private static Skill skill(String name, String slug) {
        Skill entity = new Skill();
        entity.setCanonicalName(name);
        entity.setSlug(slug);
        return entity;
    }

    private List<String> detect(String text) {
        return enricher.matchDictionarySkills(text, DICTIONARY);
    }

    @Nested
    @DisplayName("false friends must not produce the language")
    class FalseFriends {

        @Test
        @DisplayName("go-to-market is a sales phrase")
        void goToMarket() {
            assertThat(detect("Own the go-to-market strategy for the region.")).doesNotContain("Go");
            assertThat(detect("Partner with our technical go-to-market team.")).doesNotContain("Go");
            assertThat(detect("Drive go to market motions across segments.")).doesNotContain("Go");
        }

        @Test
        @DisplayName("go-live is a deployment event")
        void goLive() {
            assertThat(detect("Own onboarding through go-live and healthy consumption."))
                    .doesNotContain("Go");
            assertThat(detect("Track go live status for each customer.")).doesNotContain("Go");
        }

        @Test
        @DisplayName("ordinary verb uses are not the language")
        void verbUses() {
            assertThat(detect("You will go deep on customer problems.")).doesNotContain("Go");
            assertThat(detect("We go beyond the brief.")).doesNotContain("Go");
        }
    }

    @Nested
    @DisplayName("genuine mentions must still be found")
    class GenuineMentions {

        @Test
        @DisplayName("named in a language list")
        void languageList() {
            assertThat(detect("Proficiency in Python, Java, Bash, and Go with strong fundamentals."))
                    .contains("Go", "Java", "Python");
            assertThat(detect("Backend services in Node.js, Go, or Java."))
                    .contains("Go");
        }

        @Test
        @DisplayName("named as the role's language")
        void roleLanguage() {
            assertThat(detect("We are hiring a Go developer for the platform team.")).contains("Go");
            assertThat(detect("Go programming experience is essential.")).contains("Go");
            assertThat(detect("You will write Go services that scale.")).contains("Go");
            assertThat(detect("Experience with Go in production.")).contains("Go");
        }

        @Test
        @DisplayName("Golang alone is an alias problem, not an ambiguity one")
        void golangIsNotDetectedAsGo() {
            // "Golang" contains no standalone "go", so dictionary matching never
            // sees it in the first place. Recognising it belongs in the skill
            // alias table rather than in this guard, and is a separate gap.
            assertThat(detect("Golang experience required.")).doesNotContain("Go");
        }

        @Test
        @DisplayName("a posting can contain both a false friend and a genuine mention")
        void bothInOnePosting() {
            // The evidence is per posting, not per occurrence: one genuine
            // mention is enough, and a sales phrase elsewhere does not cancel it.
            String posting = """
                    Own the go-to-market strategy for developer tools.
                    Requirements: Experience with Go and Java.
                    """;
            assertThat(detect(posting)).contains("Go", "Java");
        }
    }

    @Test
    @DisplayName("unambiguous skills are unaffected by the guard")
    void unambiguousSkillsAreUntouched() {
        assertThat(detect("We work with Java and Python here.")).contains("Java", "Python");
    }
}
