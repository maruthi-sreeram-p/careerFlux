package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.time.Instant;
import java.util.List;

import com.careerflux.ingestion.domain.IngestionTrigger;
import com.careerflux.ingestion.pipeline.JobDeduplicator;
import com.careerflux.ingestion.pipeline.JobNormalizer;
import com.careerflux.ingestion.service.IngestionService;
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
import com.careerflux.source.service.SourceQueryService;
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
 * A source's job count is what it lists now, not everything it ever listed.
 *
 * <p>The registry showed AutoRABIT with 21 jobs while students saw 3: the count
 * was every posting the source had ever shown, the 18 it later withdrew included.
 *
 * <p>The spy beans match {@link IngestionTallyCharacterizationTest}'s so the
 * classes share one cached application context.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SourceActiveCountTest {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private JobSourceRepository sourceRepository;

    @Autowired
    private IngestionTestData testData;

    @Autowired
    private SourceQueryService queryService;

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
        company = "Active Count Employer " + unique;

        source = new JobSource();
        source.setName("Active count source " + unique);
        source.setBaseUrl("local://test/active-count-" + unique);
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

    private RawJobPosting posting(String externalId) {
        return RawJobPosting.builder(externalId)
                .title("Engineer " + externalId)
                .companyName(company)
                .locationText("Bengaluru, India")
                .descriptionHtml("<p>Build services in Java.</p>")
                .applyUrl("https://careers.nimbusdevops.co.in/jobs/" + externalId)
                .postedAt(Instant.parse("2026-09-15T00:00:00Z"))
                .rawPayload("{\"externalId\":\"" + externalId + "\"}")
                .build();
    }

    private void ingestShowing(RawJobPosting... postings) {
        doReturn(List.of(postings)).when(adapter).fetchJobs(any());
        ingestionService.ingest(source, IngestionTrigger.MANUAL);
    }

    @Test
    @DisplayName("a posting the source stopped listing no longer counts as one of its jobs")
    void countsOnlyWhatIsStillListed() {
        ingestShowing(posting("A-1"), posting("A-2"), posting("A-3"));
        assertThat(queryService.toSummary(source).activeJobCount()).isEqualTo(3);

        ingestShowing(posting("A-2"));

        assertThat(queryService.toSummary(source).activeJobCount()).isEqualTo(1);
    }
}
