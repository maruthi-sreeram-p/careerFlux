package com.careerflux.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import com.careerflux.common.TextUtils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Skill identity, without a database (Phase 2A, F5).
 *
 * <p>Skill slugs used to drop every character that was not a letter or a digit,
 * so C, C++ and C# were one skill. The skill slug now spells the two
 * distinguishing symbols out, and nothing else about slugging changes.
 */
class SkillIdentityTest {

    @Test
    @DisplayName("C, C++ and C# are three slugs")
    void theCFamilyIsThreeSkills() {
        assertThat(TextUtils.skillSlug("C")).isEqualTo("c");
        assertThat(TextUtils.skillSlug("C++")).isEqualTo("c-plus-plus");
        assertThat(TextUtils.skillSlug("C#")).isEqualTo("c-sharp");
        assertThat(Set.of(TextUtils.skillSlug("C"), TextUtils.skillSlug("C++"), TextUtils.skillSlug("C#")))
                .hasSize(3);
    }

    @Test
    @DisplayName("case and surrounding space do not make a fourth")
    void spellingDoesNotSplitThem() {
        assertThat(TextUtils.skillSlug("c++")).isEqualTo(TextUtils.skillSlug("C++"));
        assertThat(TextUtils.skillSlug("  C#  ")).isEqualTo(TextUtils.skillSlug("C#"));
    }

    @Test
    @DisplayName("other names with a plus or a hash stay apart the same way")
    void otherSymbolsAreSpelledOut() {
        assertThat(TextUtils.skillSlug("F#")).isEqualTo("f-sharp");
        assertThat(TextUtils.skillSlug("Notepad++")).isEqualTo("notepad-plus-plus");
    }

    @Test
    @DisplayName("everything without a plus or a hash slugs exactly as before")
    void everythingElseIsUnchanged() {
        for (String name : List.of("Spring Boot", "Node.js", ".NET", "Café Manager", "REST APIs",
                "CI/CD", "t-sql", "Java", "Machine Learning")) {
            assertThat(TextUtils.skillSlug(name)).describedAs(name).isEqualTo(TextUtils.slugify(name));
        }
    }

    @Test
    @DisplayName("slugify itself is unchanged, so job keys and college slugs are too")
    void slugifyIsUntouched() {
        // Documented rather than wished away: slugify still collapses these, and
        // job de-duplication keys depend on it staying that way.
        assertThat(TextUtils.slugify("C++")).isEqualTo("c");
        assertThat(TextUtils.slugify("Senior C++ Developer")).isEqualTo("senior-c-developer");
    }
}
