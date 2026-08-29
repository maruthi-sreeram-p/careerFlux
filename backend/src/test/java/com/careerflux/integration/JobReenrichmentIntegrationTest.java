package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.ingestion.service.JobReenrichmentService;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.skill.Skill;
import com.careerflux.skill.SkillRepository;
import com.careerflux.source.domain.Company;
import com.careerflux.source.repository.CompanyRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-enrichment must be able to replace the skills of a job that already has
 * some.
 *
 * <p>This is a regression test for the failure that stopped the rules-2 corpus
 * backfill dead: every one of the ten batches rolled back with
 * {@code duplicate key value violates unique constraint "uq_job_skills"}, and
 * nought of 1,948 jobs were re-enriched.
 *
 * <p>The cause was ordering inside a single transaction, not bad data.
 * {@code JobEnricher} replaces the skill set by clearing the collection and
 * adding the new rows, and Hibernate flushes the inserts for the new rows ahead
 * of the deletes for the cleared ones. Any skill the job keeps across the
 * reclassification is therefore inserted while its old row is still in the
 * table, and {@code (job_id, skill_id)} collides with itself. Ingestion never
 * met this because it only enriches jobs it has just created, which have no
 * skill rows to collide with — the bug could only appear on a backfill.
 *
 * <p>The reproduction is simply to run the backfill <em>twice</em>. The first
 * pass enriches jobs that have no skills and would succeed even against the
 * bug; the second pass re-enriches jobs that now do, which is exactly the
 * production case. That second pass doubles as the idempotency and
 * restartability check: a re-run must reach the same rows, not fail on them and
 * not double them.
 *
 * <p>The database is this test's own. Nested Spring contexts share one
 * in-memory database, and a re-enrichment run that walks every job would
 * otherwise sweep up rows other tests are relying on.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:reenrichment;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
class JobReenrichmentIntegrationTest {

    @Autowired
    private JobReenrichmentService reenrichment;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private SkillRepository skills;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void seedCorpus() {
        if (jobs.count() > 0) {
            return;
        }
        // The dictionary is seeded at startup by SkillDictionarySeeder; the
        // fixture descriptions below are written against skills it already
        // holds, so this only asserts the assumption rather than reseeding.
        assertThat(skills.findAll()).extracting(Skill::getCanonicalName)
                .contains("Java", "Spring Boot", "SQL", "AWS", "Communication");

        Company company = new Company();
        company.setName("Zenlayer Technologies");
        company.setSlug("zenlayer-technologies");
        companies.save(company);

        // Descriptions written so the classifier has both tiers to find: the
        // point is a job whose skills survive reclassification and therefore
        // collide on the second pass.
        for (int i = 0; i < 12; i++) {
            Job job = new Job();
            job.setCompany(company);
            job.setCanonicalKey("reenrich-fixture-" + i);
            job.setTitle("Backend Engineer " + i);
            job.setLocationRaw("Bengaluru, India");
            job.setDescription("""
                    Requirements:
                    - Strong experience with Java and Spring Boot
                    - Solid SQL fundamentals

                    Nice to have:
                    - Exposure to AWS
                    - Excellent communication skills
                    """);
            job.setWorkMode(WorkMode.ONSITE);
            job.setEmploymentType(EmploymentType.FULL_TIME);
            job.setSeniority(Seniority.MID);
            job.setStatus(JobStatus.OPEN);
            job.setFirstObservedAt(Instant.now());
            job.setLastObservedAt(Instant.now());
            jobs.save(job);
        }
    }

    @Test
    @DisplayName("re-enriching jobs that already have skills does not collide")
    void reenrichesJobsThatAlreadyHaveSkills() {
        // Batches deliberately smaller than the corpus so the paging loop runs
        // more than once, as it does in production.
        JobReenrichmentService.Result first = reenrichment.reenrichAll(5, false);
        assertThat(first.failedBatches()).isZero();
        assertThat(first.jobsProcessed()).isEqualTo(12);

        assertThat(skillFingerprint()).isNotEmpty();

        // The pass that reproduced the production failure.
        JobReenrichmentService.Result second = reenrichment.reenrichAll(5, false);
        assertThat(second.failures()).isEmpty();
        assertThat(second.failedBatches()).isZero();
        assertThat(second.jobsProcessed()).isEqualTo(12);
    }

    @Test
    @DisplayName("a re-run reaches the same rows rather than duplicating them")
    void isIdempotent() {
        reenrichment.reenrichAll(5, false);
        List<String> afterFirst = skillFingerprint();

        reenrichment.reenrichAll(5, false);
        assertThat(skillFingerprint()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("job identity and source fields are left alone")
    void preservesJobIdentity() {
        List<String> keysBefore = jobs.findAll().stream().map(Job::getCanonicalKey).sorted().toList();
        long countBefore = jobs.count();

        reenrichment.reenrichAll(5, false);

        assertThat(jobs.count()).isEqualTo(countBefore);
        assertThat(jobs.findAll().stream().map(Job::getCanonicalKey).sorted().toList())
                .isEqualTo(keysBefore);
    }

    /**
     * Every stored skill row, as comparable text. Read inside a transaction:
     * the collection is lazy, and the point of the comparison is the rows that
     * are actually in the database.
     */
    private List<String> skillFingerprint() {
        return new TransactionTemplate(transactionManager).execute(status ->
                jobs.findAll().stream()
                        .flatMap(job -> job.getSkills().stream()
                                .map(js -> job.getCanonicalKey() + "|"
                                        + js.getSkill().getCanonicalName() + "|"
                                        + js.getRequirement()))
                        .sorted()
                        .toList());
    }
}
