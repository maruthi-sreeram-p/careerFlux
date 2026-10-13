package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.time.Instant;
import java.util.List;

import com.careerflux.ingestion.domain.IngestionRun;
import com.careerflux.ingestion.domain.IngestionTrigger;
import com.careerflux.ingestion.pipeline.JobDeduplicator;
import com.careerflux.ingestion.pipeline.JobNormalizer;
import com.careerflux.ingestion.service.IngestionService;
import com.careerflux.job.domain.Job;
import com.careerflux.job.repository.JobObservationRepository;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.source.adapter.LocalFixtureAdapter;
import com.careerflux.source.adapter.RawJobPosting;
import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.DiscoveryMethod;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.RobotsStatus;
import com.careerflux.source.domain.SourceAccessPolicy;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.domain.TosStatus;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.support.IngestionTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * A source seeing its own job again can supply the apply link the record lacks.
 *
 * <p>Careers pages whose markup named no url were ingested with no destination.
 * Once the adapter learned to use the posting's own page, every later sync showed
 * the same jobs with a link, and none of them received it: a repeat sighting only
 * rewrites a job when its content changed, and a link was not part of that.
 *
 * <p>The spy beans match {@link IngestionTallyCharacterizationTest}'s so both
 * classes share one cached application context.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RepeatObservationApplyUrlTest {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private JobSourceRepository sourceRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobObservationRepository observationRepository;

    @Autowired
    private IngestionTestData testData;

    @MockitoSpyBean
    private LocalFixtureAdapter adapter;

    @MockitoSpyBean
    private JobNormalizer normalizer;

    @MockitoSpyBean
    private JobDeduplicator deduplicator;

    private JobSource source;

    /** A company name no other test uses, so canonical keys cannot collide. */
    private String company;

    @BeforeEach
    void createSource() {
        long unique = System.nanoTime();
        company = "Apply Link Employer " + unique;

        source = new JobSource();
        source.setName("Apply link source " + unique);
        source.setBaseUrl("local://test/apply-link-" + unique);
        source.setSourceType(SourceType.LOCAL_FIXTURE);
        source.setAtsProvider(AtsProvider.NONE);
        source.setAdapterKey(LocalFixtureAdapter.KEY);
        source.setExternalIdentifier("sample-employers");
        source.setDiscoveryMethod(DiscoveryMethod.SEED);
        source.setRateLimitPerMinute(600);
        source.setDiscoveredAt(Instant.now());
        source.setState(SourceState.ACTIVE);
        source.setStateChangedAt(Instant.now());

        SourceAccessPolicy policy = new SourceAccessPolicy();
        policy.setRobotsStatus(RobotsStatus.NOT_PUBLISHED);
        policy.setTosStatus(TosStatus.PERMITTED);
        policy.setTosReviewedBy("test");
        policy.setTosReviewedAt(Instant.now());
        policy.setAccessPolicy(AccessPolicyType.PUBLIC_FEED);
        source.setAccessPolicy(policy);
        sourceRepository.saveAndFlush(source);
    }

    @AfterEach
    void removeAnythingIngestionCommittedOnItsOwn() {
        if (source != null) {
            testData.deleteSource(source.getId());
        }
    }

    private RawJobPosting posting(String applyUrl) {
        return RawJobPosting.builder("LINK-1")
                .title("Platform Engineer")
                .companyName(company)
                .locationText("Bengaluru, India")
                .descriptionHtml("<p>Build services in Java and Spring Boot.</p>")
                .applyUrl(applyUrl)
                .sourceUrl(applyUrl)
                .postedAt(Instant.parse("2026-09-15T00:00:00Z"))
                .rawPayload("{\"externalId\":\"LINK-1\",\"title\":\"Platform Engineer\"}")
                .build();
    }

    private IngestionRun ingestShowing(RawJobPosting posting) {
        doReturn(List.of(posting)).when(adapter).fetchJobs(any());
        return ingestionService.ingest(source, IngestionTrigger.MANUAL);
    }

    private Job theJob() {
        return observationRepository.findBySourceIdAndExternalJobId(source.getId(), "LINK-1")
                .map(observation -> jobRepository.findById(observation.getJob().getId()).orElseThrow())
                .orElseThrow(() -> new AssertionError("no observation for LINK-1"));
    }

    @Test
    @DisplayName("a link the job lacked is filled in when its own source shows it again")
    void missingLinkIsFilled() {
        ingestShowing(posting(null));
        assertThat(theJob().getApplyUrl()).isNull();

        IngestionRun second = ingestShowing(posting("https://careers.nimbusdevops.co.in/jobs/4821"));

        assertThat(theJob().getApplyUrl()).isEqualTo("https://careers.nimbusdevops.co.in/jobs/4821");
        assertThat(second.getUpdatedCount())
                .describedAs("a filled gap is not an edit by the employer")
                .isZero();
    }

    @Test
    @DisplayName("a link the job already has is never replaced by a repeat sighting")
    void knownLinkIsKept() {
        ingestShowing(posting("https://careers.nimbusdevops.co.in/jobs/4821"));

        ingestShowing(posting("https://careers.nimbusdevops.co.in/jobs/4821?ref=listing"));

        assertThat(theJob().getApplyUrl()).isEqualTo("https://careers.nimbusdevops.co.in/jobs/4821");
    }
}
