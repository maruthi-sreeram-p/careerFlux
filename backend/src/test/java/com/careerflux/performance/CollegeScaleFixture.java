package com.careerflux.performance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.CandidateSkill;
import com.careerflux.candidate.domain.CgpaSource;
import com.careerflux.candidate.domain.OnboardingStage;
import com.careerflux.candidate.domain.SkillOrigin;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.common.taxonomy.Seniority;
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

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A college the size CareerFlux is actually sold into.
 *
 * <p>Two thousand students across three departments and two batches, with the
 * spread a real institution has rather than two thousand identical rows: some
 * have finished their profile and some have barely started, skills vary, and
 * only part of the cohort has a CGPA on file. A uniform fixture would make
 * every measurement look better than production, because every query would hit
 * the same shape of data.
 *
 * <p>Passwords are not hashed. These accounts never sign in, and BCrypting two
 * thousand of them would put four minutes of setup in front of a measurement
 * that takes two seconds.
 */
@Component
public class CollegeScaleFixture {

    /** Not a valid BCrypt hash, so no seeded account can ever authenticate. */
    private static final String UNUSABLE_HASH = "not-a-usable-credential";

    private static final List<String> DICTIONARY = List.of(
            "Java", "Spring Boot", "SQL", "REST APIs", "Kafka", "AWS", "Docker",
            "Python", "React", "Kubernetes", "PostgreSQL", "Git");

    private final UserRepository users;
    private final CandidateProfileRepository profiles;
    private final CompanyRequirementRepository requirements;
    private final SkillResolver skillResolver;
    private final TestInstitutions institutions;

    public CollegeScaleFixture(UserRepository users,
                               CandidateProfileRepository profiles,
                               CompanyRequirementRepository requirements,
                               SkillResolver skillResolver,
                               TestInstitutions institutions) {
        this.users = users;
        this.profiles = profiles;
        this.requirements = requirements;
        this.skillResolver = skillResolver;
        this.institutions = institutions;
    }

    /**
     * @param students        every seeded student's user id
     * @param sampleStudentId one student, for measuring a single-student endpoint
     * @param requirementId   an open requirement covering the whole college
     */
    public record Seeded(List<UUID> students, UUID sampleStudentId, UUID requirementId) {
    }

    @Transactional
    public Seeded seed(int studentCount) {
        List<Skill> dictionary = DICTIONARY.stream()
                .map(name -> skillResolver.resolve(name).orElseThrow())
                .toList();

        Department[] departments = {
                institutions.exampleCse(), institutions.exampleMech(), institutions.exampleCse()};
        Batch[] batches = {institutions.exampleBatch2027(), institutions.exampleBatch2026()};

        List<UUID> studentIds = new ArrayList<>(studentCount);
        List<CandidateProfile> pending = new ArrayList<>();

        for (int i = 0; i < studentCount; i++) {
            User user = new User();
            user.setEmail("scale-student-" + i + "@example.com");
            user.setFullName("Student " + i);
            user.setPasswordHash(UNUSABLE_HASH);
            user.setRole(UserRole.STUDENT);
            user.setStatus(i % 50 == 0 ? UserStatus.DISABLED : UserStatus.ACTIVE);
            user.setInstitution(institutions.example());
            user.setDepartment(departments[i % departments.length]);
            user.setBatch(batches[i % batches.length]);
            users.save(user);
            studentIds.add(user.getId());

            CandidateProfile profile = new CandidateProfile();
            profile.setUser(user);
            profile.setInstitution(institutions.example());
            profile.setPrimaryRole(i % 3 == 0 ? "Backend Developer" : "Software Engineer");
            profile.setSeniority(Seniority.JUNIOR);
            profile.setYearsExperience(BigDecimal.valueOf(i % 4));
            profile.setLocation("Hyderabad, India");
            // A real college is not uniformly onboarded.
            profile.setOnboardingStage(i % 7 == 0
                    ? OnboardingStage.RESUME_UPLOAD : OnboardingStage.COMPLETE);
            profile.setProfileCompleteness(i % 7 == 0 ? 20 : 80);

            // Roughly two thirds have a CGPA on file, which is the state a
            // college reaches partway through collecting them.
            if (i % 3 != 0) {
                profile.recordVerifiedCgpa(new BigDecimal(String.format("%.2f", 6.0 + (i % 40) / 10.0)),
                        null);
            }

            // A varying slice of the dictionary, so scores spread out instead of
            // every candidate landing on the same number.
            int skillCount = 1 + (i % dictionary.size());
            for (int s = 0; s < skillCount; s++) {
                CandidateSkill skill = new CandidateSkill();
                skill.setCandidate(profile);
                skill.setSkill(dictionary.get((i + s) % dictionary.size()));
                skill.setOrigin(SkillOrigin.RESUME);
                profile.getSkills().add(skill);
            }
            pending.add(profile);

            if (pending.size() == 250) {
                profiles.saveAll(pending);
                profiles.flush();
                pending.clear();
            }
        }
        if (!pending.isEmpty()) {
            profiles.saveAll(pending);
            profiles.flush();
        }

        return new Seeded(studentIds, studentIds.get(0), seedRequirement(dictionary));
    }

    private UUID seedRequirement(List<Skill> dictionary) {
        CompanyRequirement requirement = new CompanyRequirement();
        requirement.setInstitution(institutions.example());
        requirement.setCompanyName("Scale Systems");
        requirement.setRoleTitle("Java Backend Developer");
        requirement.setStatus(RequirementStatus.OPEN);
        requirement.setMinExperienceYears(BigDecimal.ZERO);
        requirement.setMaxExperienceYears(BigDecimal.valueOf(4));
        requirement.setMinCgpa(new BigDecimal("7.00"));
        requirements.saveAndFlush(requirement);

        for (int i = 0; i < 5; i++) {
            CompanyRequirementSkill skill = new CompanyRequirementSkill();
            skill.setSkill(dictionary.get(i));
            skill.setTier(i < 3 ? SkillRequirement.REQUIRED : SkillRequirement.PREFERRED);
            requirement.addSkill(skill);
        }
        return requirements.saveAndFlush(requirement).getId();
    }

    /** A staff account that can actually sign in, for the endpoints under test. */
    @Transactional
    public User staff(String email, UserRole role, String passwordHash) {
        User user = new User();
        user.setEmail(email);
        user.setFullName("Scale Officer");
        user.setPasswordHash(passwordHash);
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institutions.example());
        return users.saveAndFlush(user);
    }
}
