package com.careerflux.bootstrap;

import java.time.Instant;
import java.util.Optional;

import com.careerflux.config.CareerFluxProperties;
import com.careerflux.job.repository.JobRepository;

import com.careerflux.ingestion.domain.IngestionTrigger;
import com.careerflux.ingestion.service.IngestionService;
import com.careerflux.source.adapter.LocalFixtureAdapter;
import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.DiscoveryMethod;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.PolicyDecision;
import com.careerflux.source.domain.RobotsStatus;
import com.careerflux.source.domain.SourceAccessPolicy;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.domain.TosStatus;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.source.service.SourceLifecycleService;
import com.careerflux.source.service.SourceRegistryService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers the bundled sample source and ingests it, so a fresh checkout has
 * something to look at without hitting anybody's servers.
 *
 * <p><b>Off unless explicitly asked for.</b> This used to run whenever the
 * {@code demo} profile was active, which conflated two unrelated intents: "give
 * me demo login accounts" and "insert sample job postings". Starting the
 * application to show somebody the demo accounts therefore added nine fixture
 * postings to a database holding 1,947 real ones. A profile name is too blunt an
 * instrument to authorise writing data.
 *
 * <p>Two gates now stand in front of it, both off by default:
 *
 * <ul>
 *   <li>{@code careerflux.demo.seed-sample-jobs} — insert sample data at all;
 *   <li>{@code careerflux.demo.allow-seeding-into-populated-corpus} — do so even
 *       when the database already holds postings from other sources, which is
 *       almost never what somebody means.
 * </ul>
 *
 * <p>Running it twice is a no-op. The source is created only when absent, and
 * ingestion deduplicates by source and external id, so a second run reports
 * every posting as already seen rather than inserting it again.
 *
 * <p>Everything it creates is typed {@link SourceType#LOCAL_FIXTURE} and is
 * labelled as sample data throughout the UI — it is never presented as a real
 * employer feed, and its health and provenance records describe the fixture
 * honestly rather than imitating a live source.
 */
@Component
@Profile("demo")
@Order(3)
public class SampleSourceSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleSourceSeeder.class);
    private static final String FIXTURE_URL = "local://fixtures/sample-employers";

    private final JobSourceRepository sourceRepository;
    private final SourceRegistryService registryService;
    private final SourceLifecycleService lifecycleService;
    private final IngestionService ingestionService;
    private final JobRepository jobRepository;
    private final CareerFluxProperties properties;

    public SampleSourceSeeder(JobSourceRepository sourceRepository,
                              SourceRegistryService registryService,
                              SourceLifecycleService lifecycleService,
                              IngestionService ingestionService,
                              JobRepository jobRepository,
                              CareerFluxProperties properties) {
        this.sourceRepository = sourceRepository;
        this.registryService = registryService;
        this.lifecycleService = lifecycleService;
        this.ingestionService = ingestionService;
        this.jobRepository = jobRepository;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.demo().seedSampleJobs()) {
            log.info("Sample job seeding is off. Set careerflux.demo.seed-sample-jobs=true "
                    + "to register the bundled fixture source and ingest it.");
            return;
        }

        Optional<JobSource> existing = sourceRepository.findByBaseUrl(FIXTURE_URL);
        if (existing.isPresent()) {
            // Idempotent: re-ingest rather than returning early. Deduplication is
            // by source and external id, so this reports the postings as already
            // seen instead of inserting them a second time.
            var repeat = ingestionService.ingest(existing.get(), IngestionTrigger.MANUAL);
            log.info("Sample source already registered; re-ingested: {} raw, {} new, {} duplicates",
                    repeat.getRawCount(), repeat.getNewCount(), repeat.getDuplicateCount());
            return;
        }

        long existingJobs = jobRepository.count();
        if (existingJobs > 0 && !properties.demo().allowSeedingIntoPopulatedCorpus()) {
            log.warn("Refusing to seed sample jobs: this database already holds {} postings. "
                    + "Set careerflux.demo.allow-seeding-into-populated-corpus=true if that is "
                    + "genuinely what you want.", existingJobs);
            return;
        }

        JobSource source = new JobSource();
        source.setName("Sample employer feed (local fixture)");
        source.setBaseUrl(FIXTURE_URL);
        source.setSourceType(SourceType.LOCAL_FIXTURE);
        source.setAtsProvider(AtsProvider.NONE);
        source.setAdapterKey(LocalFixtureAdapter.KEY);
        source.setExternalIdentifier("sample-employers");
        source.setDiscoveryMethod(DiscoveryMethod.SEED);
        source.setDiscoveryDetail("Bundled with the application for local development.");
        source.setRateLimitPerMinute(600);
        source.setDiscoveredAt(Instant.now());
        source.setState(SourceState.DISCOVERED);
        source.setStateChangedAt(Instant.now());

        // The policy record is filled in honestly: this reads a file on disk, so
        // there is no robots.txt to check and no third-party terms to review.
        SourceAccessPolicy policy = new SourceAccessPolicy();
        policy.setRobotsStatus(RobotsStatus.NOT_PUBLISHED);
        policy.setRobotsRule("Local fixture; no HTTP request is made.");
        policy.setRobotsCheckedAt(Instant.now());
        policy.setTosStatus(TosStatus.PERMITTED);
        policy.setTosNotes("Bundled sample data shipped with CareerFlux. No third-party terms apply.");
        policy.setTosReviewedAt(Instant.now());
        policy.setTosReviewedBy("careerflux-seed");
        policy.setAccessPolicy(AccessPolicyType.PUBLIC_FEED);
        policy.setAllowedFields("title, company, location, description, employment type, apply URL");
        policy.setDecision(PolicyDecision.APPROVED);
        policy.setDecisionReason("Local fixture data bundled with the build.");
        policy.setDecidedBy("careerflux-seed");
        policy.setVerifiedAt(Instant.now());
        source.setAccessPolicy(policy);

        sourceRepository.save(source);
        lifecycleService.recordEvent(source, null, SourceState.DISCOVERED, "careerflux-seed",
                "Bundled sample source registered");

        // Walk the real lifecycle rather than writing ACTIVE straight into the row:
        // the policy gate should be seen to pass.
        lifecycleService.transition(source, SourceState.CLASSIFIED, "careerflux-seed",
                "Matched the local fixture adapter.");
        lifecycleService.transition(source, SourceState.POLICY_REVIEW, "careerflux-seed",
                "Policy record completed.");
        lifecycleService.transition(source, SourceState.APPROVED, "careerflux-seed",
                "Approved: bundled fixture data.");
        lifecycleService.transition(source, SourceState.ACTIVE, "careerflux-seed",
                "Activated for local development.");

        var run = ingestionService.ingest(source, IngestionTrigger.ACTIVATION);
        log.info("Sample source ingested: {} raw, {} new, {} duplicates",
                run.getRawCount(), run.getNewCount(), run.getDuplicateCount());
    }
}
