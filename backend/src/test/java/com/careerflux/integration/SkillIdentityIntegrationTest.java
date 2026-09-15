package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.careerflux.skill.Skill;
import com.careerflux.skill.SkillRepository;
import com.careerflux.skill.SkillResolver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * C, C++ and C# against the real, seeded dictionary (Phase 2A, F5).
 *
 * <p>The seeder used to give "C#" and "C++" the same slug and create whichever
 * an unordered map offered first. Both now exist, apart, with their aliases
 * pointing at the right one.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SkillIdentityIntegrationTest {

    @Autowired
    private SkillResolver resolver;

    @Autowired
    private SkillRepository skills;

    @Test
    @DisplayName("C++ and C# are two dictionary skills, and each alias finds its own")
    void cPlusPlusAndCSharpAreDistinct() {
        Skill cpp = resolver.lookup("C++").orElseThrow();
        Skill csharp = resolver.lookup("C#").orElseThrow();

        assertThat(cpp.getId()).isNotEqualTo(csharp.getId());
        assertThat(cpp.getCanonicalName()).isEqualTo("C++");
        assertThat(csharp.getCanonicalName()).isEqualTo("C#");

        assertThat(resolver.lookup("cpp").map(Skill::getId)).contains(cpp.getId());
        assertThat(resolver.lookup("cplusplus").map(Skill::getId)).contains(cpp.getId());
        assertThat(resolver.lookup("c plus plus").map(Skill::getId)).contains(cpp.getId());
        assertThat(resolver.lookup("csharp").map(Skill::getId)).contains(csharp.getId());
        assertThat(resolver.lookup("c sharp").map(Skill::getId)).contains(csharp.getId());
    }

    @Test
    @DisplayName("C is neither of them")
    void cIsNotCPlusPlusOrCSharp() {
        // A one-letter name is not accepted as a skill at all, which is why C
        // never resolves to anything — and so can never land on C++ or C#.
        assertThat(resolver.lookup("C")).isEmpty();
        assertThat(resolver.resolve("C")).isEmpty();
        assertThat(skills.findBySlug("c")).describedAs("nothing holds the old shared slug").isEmpty();
    }

    @Test
    @DisplayName("a posting naming C++ finds the dictionary entry rather than making another")
    void resolvingFindsTheExistingEntry() {
        Skill cpp = resolver.lookup("C++").orElseThrow();
        long before = skills.count();

        assertThat(resolver.resolve("C++").map(Skill::getId)).contains(cpp.getId());
        assertThat(resolver.resolve("c++").map(Skill::getId)).contains(cpp.getId());
        assertThat(skills.count()).isEqualTo(before);
    }
}
