package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.CandidateSkill;
import com.careerflux.candidate.domain.OnboardingStage;
import com.careerflux.candidate.domain.SkillOrigin;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.matching.service.MatchingService;
import com.careerflux.skill.SkillResolver;
import com.careerflux.source.domain.Company;
import com.careerflux.source.repository.CompanyRepository;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Matching must consider the whole corpus, not the first page of it.
 *
 * <p>This is a regression test for a bug that made the product look empty. The
 * service fetched one page of jobs ordered by {@code lastObservedAt} and scored
 * only those, behind a constant named {@code MATCH_BATCH_SIZE} that implied a
 * loop which did not exist. Against a corpus of 1,919 open jobs a candidate was
 * scored on 400 of them — and because the ordering was recency, not relevance,
 * a backend developer was scored against 1 of the 61 backend roles. Every match
 * fell below the visibility floor, so the dashboard and recommendations were
 * empty while the pipeline reported success.
 *
 * <p>The batch size is set very small here so the paging loop has to run several
 * times. A test that fitted inside one page would pass against the bug.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "careerflux.matching.batch-size=3",
        "careerflux.matching.max-jobs-per-candidate=1000"
})
@Transactional
class MatchingCoverageIntegrationTest {

    private static final int JOB_COUNT = 11;

    @Autowired
    private MatchingService matchingService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private CandidateProfileRepository profileRepository;

    @Autowired
    private CandidateProfileService profileService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    @Autowired
    private SkillResolver skillResolver;

    @Test
    @DisplayName("every open job is scored, not just the first page")
    void scoresTheWholeCorpus() {
        CandidateProfile candidate = candidate();
        long openJobs = seedJobs();

        MatchingService.MatchRun run = matchingService.recomputeForCandidate(candidate.getId());

        assertThat(run.jobsScored())
                .as("a candidate must be scored against every open job, not one page of them")
                .isEqualTo((int) openJobs);
        assertThat(run.jobsScored()).isGreaterThan(3);
    }

    @Test
    @DisplayName("a closed job is not scored")
    void closedJobsAreExcluded() {
        CandidateProfile candidate = candidate();
        seedJobs();
        Job closed = jobRepository.findAll().get(0);
        closed.setStatus(JobStatus.CLOSED);
        jobRepository.saveAndFlush(closed);

        long open = jobRepository.findAll().stream()
                .filter(job -> job.getStatus() == JobStatus.OPEN || job.getStatus() == JobStatus.REOPENED)
                .count();

        MatchingService.MatchRun run = matchingService.recomputeForCandidate(candidate.getId());
        assertThat(run.jobsScored()).isEqualTo((int) open);
    }

    @Test
    @DisplayName("rerunning does not accumulate duplicate matches")
    void rematchIsIdempotent() {
        CandidateProfile candidate = candidate();
        long openJobs = seedJobs();

        matchingService.recomputeForCandidate(candidate.getId());
        MatchingService.MatchRun second = matchingService.recomputeForCandidate(candidate.getId());

        assertThat(second.jobsScored()).isEqualTo((int) openJobs);
    }

    // ------------------------------------------------------------------

    private CandidateProfile candidate() {
        User user = new User();
        user.setEmail("coverage-" + System.nanoTime() + "@example.com");
        user.setFullName("Coverage Tester");
        user.setPasswordHash(passwordEncoder.encode("IntegrationTest123!"));
        user.setInstitution(institutions.example());
        userRepository.saveAndFlush(user);

        CandidateProfile profile = profileService.createForUser(user);
        profile.setPrimaryRole("Backend Developer");
        profile.setSeniority(Seniority.JUNIOR);
        profile.setYearsExperience(java.math.BigDecimal.valueOf(2));
        profile.setLocation("Bengaluru, India");
        profile.setOnboardingStage(OnboardingStage.COMPLETE);

        for (String name : List.of("Java", "Spring Boot", "SQL")) {
            skillResolver.resolve(name).ifPresent(skill -> {
                CandidateSkill candidateSkill = new CandidateSkill();
                candidateSkill.setCandidate(profile);
                candidateSkill.setSkill(skill);
                candidateSkill.setOrigin(SkillOrigin.RESUME);
                profile.getSkills().add(candidateSkill);
            });
        }
        return profileRepository.saveAndFlush(profile);
    }

    /** @return how many open jobs now exist, including anything other tests left behind */
    private long seedJobs() {
        Company company = new Company();
        company.setName("Coverage Corp");
        company.setSlug("coverage-corp-" + System.nanoTime());
        companyRepository.saveAndFlush(company);

        for (int i = 0; i < JOB_COUNT; i++) {
            Job job = new Job();
            job.setCompany(company);
            job.setTitle("Backend Developer " + i);
            job.setNormalizedTitle("backend developer " + i);
            job.setDescription("Java and Spring Boot services in Bengaluru.");
            job.setCity("Bengaluru");
            job.setStatus(JobStatus.OPEN);
            job.setWorkMode(WorkMode.UNSPECIFIED);
            job.setEmploymentType(EmploymentType.FULL_TIME);
            job.setSeniority(Seniority.JUNIOR);
            job.setCanonicalKey(("coverage-" + i + "-" + System.nanoTime()).toLowerCase(Locale.ROOT));
            job.setFirstObservedAt(Instant.now());
            job.setLastObservedAt(Instant.now());
            jobRepository.save(job);
        }
        jobRepository.flush();

        return jobRepository.findAll().stream()
                .filter(job -> job.getStatus() == JobStatus.OPEN || job.getStatus() == JobStatus.REOPENED)
                .count();
    }
}
