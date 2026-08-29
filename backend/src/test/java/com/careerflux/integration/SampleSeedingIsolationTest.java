package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.careerflux.bootstrap.SampleSourceSeeder;
import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.ingestion.service.IngestionService;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.source.domain.Company;
import com.careerflux.source.repository.CompanyRepository;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.source.service.SourceLifecycleService;
import com.careerflux.source.service.SourceRegistryService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Sample data must never appear because the application merely started.
 *
 * <p>Regression test for a real incident. The seeder ran on any {@code demo}
 * profile startup, so launching the application to demonstrate the demo
 * <em>login accounts</em> also inserted nine fixture postings into a database
 * holding 1,947 real ones. A profile name is not consent to write data.
 *
 * <p>The seeder is built per test with the configuration under examination
 * rather than through {@code @TestPropertySource}. Nested Spring contexts share
 * one in-memory database, so property-per-class variants leak state into each
 * other — and this test is specifically about what does and does not get
 * written.
 */
@SpringBootTest
@ActiveProfiles({"test", "demo"})
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:seeding-isolation;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
class SampleSeedingIsolationTest {

    private static final String FIXTURE_URL = "local://fixtures/sample-employers";

    @Autowired
    private JobSourceRepository sources;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private SourceRegistryService registryService;

    @Autowired
    private SourceLifecycleService lifecycleService;

    @Autowired
    private IngestionService ingestionService;

    private SampleSourceSeeder seederWith(boolean seedSampleJobs, boolean allowPopulated) {
        CareerFluxProperties properties = new CareerFluxProperties(null, null, null, null, null,
                new CareerFluxProperties.Demo(seedSampleJobs, allowPopulated));
        return new SampleSourceSeeder(sources, registryService, lifecycleService,
                ingestionService, jobs, properties);
    }

    private void run(SampleSourceSeeder seeder) {
        seeder.run(new DefaultApplicationArguments());
    }

    /**
     * Starts every case from an empty corpus.
     *
     * <p>This test owns its database precisely so it can do this. On the shared
     * one the populated-corpus guard fires for every case, which masks the
     * seeding gate entirely — a mutant with the gate removed passed all three
     * tests until this was fixed.
     */
    @BeforeEach
    void emptyTheCorpus() {
        jobs.deleteAll();
        sources.findByBaseUrl(FIXTURE_URL).ifPresent(sources::delete);
        jobs.flush();
    }

    @Test
    @DisplayName("the demo profile alone inserts nothing, however many times it starts")
    void demoProfileAloneDoesNotSeed() {
        // An empty corpus, so nothing but the seeding gate itself can stop this.
        // With the gate removed the seeder would happily fill the database, which
        // is exactly what it used to do.
        SampleSourceSeeder seeder = seederWith(false, false);

        run(seeder);
        run(seeder);
        run(seeder);

        assertThat(jobs.count())
                .as("starting the application must not add postings")
                .isZero();
        assertThat(sources.findByBaseUrl(FIXTURE_URL))
                .as("nor register the fixture source")
                .isEmpty();
    }

    @Test
    @DisplayName("an explicit request seeds, and repeating it does not duplicate")
    void explicitSeedingIsIdempotent() {
        SampleSourceSeeder seeder = seederWith(true, true);

        run(seeder);
        assertThat(sources.findByBaseUrl(FIXTURE_URL))
                .as("an explicit request does seed")
                .isPresent();
        long afterFirst = jobs.count();
        assertThat(afterFirst).isPositive();

        run(seeder);
        run(seeder);

        assertThat(jobs.count())
                .as("N sample jobs, not 3N — ingestion deduplicates by source and external id")
                .isEqualTo(afterFirst);
        assertThat(sources.findAll())
                .filteredOn(source -> FIXTURE_URL.equals(source.getBaseUrl()))
                .as("one fixture source, however many runs")
                .hasSize(1);
    }

    @Test
    @DisplayName("a corpus that already holds postings is left alone even when seeding is on")
    void refusesToSeedIntoAPopulatedCorpus() {
        realLookingJob();
        long before = jobs.count();

        run(seederWith(true, false));

        assertThat(jobs.count())
                .as("a database with other postings in it is not the one you meant to fill")
                .isEqualTo(before);
        assertThat(sources.findByBaseUrl(FIXTURE_URL)).isEmpty();
    }

    private void realLookingJob() {
        Company company = new Company();
        company.setName("Real Employer");
        company.setSlug("real-employer-" + System.nanoTime());
        companies.saveAndFlush(company);

        Job job = new Job();
        job.setCompany(company);
        job.setTitle("Backend Engineer");
        job.setNormalizedTitle("backend engineer");
        job.setCanonicalKey("real-" + System.nanoTime());
        job.setStatus(JobStatus.OPEN);
        job.setWorkMode(WorkMode.UNSPECIFIED);
        job.setEmploymentType(EmploymentType.FULL_TIME);
        job.setSeniority(Seniority.UNSPECIFIED);
        job.setFirstObservedAt(Instant.now());
        job.setLastObservedAt(Instant.now());
        jobs.saveAndFlush(job);
    }
}
