package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.CandidateSkill;
import com.careerflux.candidate.domain.OnboardingStage;
import com.careerflux.candidate.domain.SkillOrigin;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.discovery.dto.DiscoveryDtos.CandidatePage;
import com.careerflux.discovery.service.CandidateDiscoveryService;
import com.careerflux.institution.domain.Batch;
import com.careerflux.institution.domain.Department;
import com.careerflux.job.domain.SkillRequirement;
import com.careerflux.requirement.domain.CompanyRequirement;
import com.careerflux.requirement.domain.CompanyRequirementSkill;
import com.careerflux.requirement.domain.RequirementStatus;
import com.careerflux.requirement.repository.CompanyRequirementRepository;
import com.careerflux.skill.Skill;
import com.careerflux.skill.SkillResolver;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;

import com.careerflux.security.AuthenticatedUser;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Candidate discovery at the scale a college actually runs at.
 *
 * <p>The naive implementation of this feature is a loop that reads each
 * student's skills and preferences one at a time. That is what
 * {@code MatchingService.snapshot} does — correctly, for one student — and it
 * would be five queries per student here: ten thousand round trips for a
 * two-thousand-student college, most of the response time spent waiting on the
 * database rather than scoring.
 *
 * <p>So the thing asserted is the query count, not just the clock. A wall-time
 * assertion on a shared machine is flaky and proves little; a query count is
 * deterministic and catches the regression that actually matters. If someone
 * later replaces the batch load with a per-candidate one, this fails loudly
 * instead of the feature quietly becoming slow in production.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:discovery-perf;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class CandidateDiscoveryPerformanceTest {

    private static final int STUDENTS = 2000;

    /**
     * The batch load is a fixed handful of queries regardless of cohort size:
     * the cohort ids, the profiles, the skills, the preferences, plus the
     * requirement and the caller's scope. The ceiling is deliberately loose —
     * the point is that it does not scale with the number of students.
     */
    private static final int QUERY_CEILING = 40;

    /** Not a valid BCrypt hash, so no seeded account can ever authenticate. */
    private static final String UNUSABLE_HASH = "not-a-usable-credential";

    @Autowired
    private CandidateDiscoveryService discovery;

    @Autowired
    private CompanyRequirementRepository requirements;

    @Autowired
    private CandidateProfileRepository profiles;

    @Autowired
    private UserRepository users;

    @Autowired
    private SkillResolver skillResolver;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @Transactional
    @DisplayName("two thousand students are discovered without a query per student")
    void scalesToACollege() {
        List<Skill> dictionary = List.of("Java", "Spring Boot", "SQL", "REST APIs", "Kafka",
                        "AWS", "Docker", "Python", "React", "Kubernetes").stream()
                .map(name -> skillResolver.resolve(name).orElseThrow())
                .toList();

        seedStudents(dictionary);
        UUID requirementId = seedRequirement(dictionary);
        authenticateAsOfficer();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        long startedAt = System.nanoTime();
        CandidatePage page = discovery.discover(requirementId, null, null, null, "match", 0, 25);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        long queries = statistics.getPrepareStatementCount();

        System.out.printf("%n=== candidate discovery, %d students ===%n", STUDENTS);
        System.out.printf("  considered      : %d%n", page.consideredStudents());
        System.out.printf("  scored + ranked : %d%n", page.totalElements());
        System.out.printf("  returned (page) : %d%n", page.content().size());
        System.out.printf("  elapsed         : %d ms%n", elapsedMs);
        System.out.printf("  SQL statements  : %d%n%n", queries);

        assertThat(page.consideredStudents()).isEqualTo(STUDENTS);
        assertThat(page.totalElements()).isEqualTo(STUDENTS);
        assertThat(page.content()).hasSize(25);

        // The assertion that matters: constant, not per-student.
        assertThat(queries)
                .describedAs("SQL statements for %d students — a per-candidate load would be "
                        + "roughly %d", STUDENTS, STUDENTS * 5)
                .isLessThan(QUERY_CEILING);

        // Ranked best-first, and every row carries both answers.
        assertThat(page.content().get(0).compatibility())
                .isGreaterThanOrEqualTo(page.content().get(24).compatibility());
        assertThat(page.content()).allSatisfy(candidate -> {
            assertThat(candidate.eligibility()).isNotBlank();
            assertThat(candidate.confidence()).isNotBlank();
        });
    }

    private void seedStudents(List<Skill> dictionary) {
        Department[] departments = {institutions.exampleCse(), institutions.exampleMech()};
        Batch[] batches = {institutions.exampleBatch2027(), institutions.exampleBatch2026()};

        List<CandidateProfile> batch = new ArrayList<>();
        for (int i = 0; i < STUDENTS; i++) {
            User user = new User();
            user.setEmail("perf-student-" + i + "@example.com");
            user.setFullName("Student " + i);
            // A constant, obviously-unusable hash. These students never sign in,
            // and BCrypting two thousand passwords would make seeding dominate
            // the measurement this test exists to take.
            user.setPasswordHash(UNUSABLE_HASH);
            user.setRole(UserRole.STUDENT);
            user.setStatus(UserStatus.ACTIVE);
            user.setInstitution(institutions.example());
            // Everyone in the targeted department and batch, so the whole cohort
            // is scored rather than filtered away before the interesting work.
            user.setDepartment(departments[0]);
            user.setBatch(batches[0]);
            users.save(user);

            CandidateProfile profile = new CandidateProfile();
            profile.setUser(user);
            profile.setInstitution(institutions.example());
            profile.setPrimaryRole(i % 3 == 0 ? "Backend Developer" : "Software Engineer");
            profile.setSeniority(Seniority.JUNIOR);
            profile.setYearsExperience(BigDecimal.valueOf(i % 4));
            profile.setLocation("Hyderabad, India");
            profile.setOnboardingStage(OnboardingStage.COMPLETE);

            // A varying slice of the dictionary, so scores spread out rather
            // than every candidate landing on the same number.
            for (int s = 0; s <= i % dictionary.size(); s++) {
                CandidateSkill skill = new CandidateSkill();
                skill.setCandidate(profile);
                skill.setSkill(dictionary.get(s));
                skill.setOrigin(SkillOrigin.RESUME);
                profile.getSkills().add(skill);
            }
            batch.add(profile);

            if (batch.size() == 200) {
                profiles.saveAll(batch);
                profiles.flush();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            profiles.saveAll(batch);
            profiles.flush();
        }
    }

    private UUID seedRequirement(List<Skill> dictionary) {
        CompanyRequirement requirement = new CompanyRequirement();
        requirement.setInstitution(institutions.example());
        requirement.setCompanyName("Scale Test Systems");
        requirement.setRoleTitle("Java Backend Developer");
        requirement.setStatus(RequirementStatus.OPEN);
        requirement.setMinExperienceYears(BigDecimal.ZERO);
        requirement.setMaxExperienceYears(BigDecimal.valueOf(4));
        requirement.getDepartments().add(institutions.exampleCse());
        requirement.setGraduationYear(institutions.exampleBatch2027().getGraduationYear());
        requirements.saveAndFlush(requirement);

        for (int i = 0; i < 4; i++) {
            CompanyRequirementSkill skill = new CompanyRequirementSkill();
            skill.setSkill(dictionary.get(i));
            skill.setTier(i < 3 ? SkillRequirement.REQUIRED : SkillRequirement.PREFERRED);
            requirement.addSkill(skill);
        }
        return requirements.saveAndFlush(requirement).getId();
    }

    /** A real officer in the security context, so the service resolves a real scope. */
    private void authenticateAsOfficer() {
        User officer = new User();
        officer.setEmail("perf-officer@example.com");
        officer.setFullName("Perf Officer");
        officer.setPasswordHash(passwordEncoder.encode("PerfTest123!"));
        officer.setRole(UserRole.PLACEMENT_COORDINATOR);
        officer.setStatus(UserStatus.ACTIVE);
        officer.setInstitution(institutions.example());
        users.saveAndFlush(officer);

        AuthenticatedUser principal = new AuthenticatedUser(officer);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
