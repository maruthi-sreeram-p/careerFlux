package com.careerflux.source.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.careerflux.source.adapter.GreenhouseAdapter;
import com.careerflux.source.discovery.SourceDiscoveryService.DiscoveryRun;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.DiscoveryMethod;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.net.SafeUrlValidator;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.source.service.SourceRegistryService;
import com.careerflux.source.service.SourceRegistryService.RegistrationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * What discovery and manual registration leave in the registry (Phase 1).
 *
 * <p>The URL validator is mocked here, and only here, because {@code register}
 * resolves every host through DNS and these tests must not depend on the live
 * network. What this class checks is what gets <em>stored</em>. The validator's
 * own rules are covered, unmocked, by {@code SafeUrlValidatorTest},
 * {@code OutboundHardeningTest} and {@code CompanyDiscoveryIntegrationTest}; the
 * one thing asserted about it here is that the URL actually stored is the URL
 * that was validated.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SourceDiscoveryPersistenceIntegrationTest {

    @MockitoBean
    private SafeUrlValidator urlValidator;

    @Autowired
    private SourceRegistryService registry;

    @Autowired
    private JobSourceRepository sources;

    /** Discovery over fixed pages: the real service, the real registry, no network. */
    private SourceDiscoveryService discoveryOver(Map<String, String> pages) {
        Function<String, String> web = pages::get;
        return new SourceDiscoveryService(new AtsBoardProbe(web, new ObjectMapper()), registry);
    }

    private static final String AUTORABIT_CAREERS = """
            <a href="https://www.linkedin.com/company/autorabit">LinkedIn</a>
            <a href="https://autorabit.applytojob.com/apply/pCgw7Isara/Software-Engineer">Software Engineer</a>
            """;

    @Nested
    @DisplayName("discovering AutoRABIT")
    class AutoRabit {

        @Test
        @DisplayName("the JazzHR board is kept as a source, with its company, provider and board URL")
        void boardIsKept() {
            DiscoveryRun run = discoveryOver(Map.of("https://autorabit.com/careers", AUTORABIT_CAREERS))
                    .discover(List.of("autorabit.com"), List.of("AutoRABIT"), "test");

            assertThat(run.withoutBoard()).isEmpty();
            assertThat(run.registered()).hasSize(1);
            assertThat(run.registered().get(0).provider()).isEqualTo("JAZZHR");
            assertThat(run.registered().get(0).ingestible()).isFalse();

            JobSource source = sources.findByBaseUrl("https://autorabit.applytojob.com/apply").orElseThrow();
            assertThat(source.getAtsProvider()).isEqualTo(AtsProvider.JAZZHR);
            assertThat(source.getExternalIdentifier()).isEqualTo("autorabit");
            assertThat(source.getAdapterKey()).isNull();
            assertThat(source.getSourceType()).isEqualTo(SourceType.UNKNOWN);
            assertThat(source.getDiscoveryMethod()).isEqualTo(DiscoveryMethod.DOMAIN_INSPECTION);
            assertThat(source.getDiscoveryDetail()).contains("Linked from https://autorabit.com/careers");
            assertThat(source.getCompany().getName()).isEqualTo("AutoRABIT");
            assertThat(source.getCompany().getWebsite()).isEqualTo("https://autorabit.com");
        }

        @Test
        @DisplayName("it stays DISCOVERED: nothing reads it, so it cannot be classified, synced or activated")
        void itIsNotIngested() {
            discoveryOver(Map.of("https://autorabit.com/careers", AUTORABIT_CAREERS))
                    .discover(List.of("autorabit.com"), List.of("AutoRABIT"), "test");
            JobSource source = sources.findByBaseUrl("https://autorabit.applytojob.com/apply").orElseThrow();

            assertThat(source.getState()).isEqualTo(SourceState.DISCOVERED);
            assertThat(source.getLastSyncAttemptAt()).isNull();
            assertThat(source.getJobsIngestedTotal()).isZero();

            SourceRegistryService.ClassificationResult classified = registry.classify(source.getId(), "test");
            assertThat(classified.classified()).isFalse();
            assertThat(sources.findWithDetailById(source.getId()).orElseThrow().getState())
                    .isEqualTo(SourceState.DISCOVERED);
        }

        @Test
        @DisplayName("running discovery again reports it as already known rather than adding it twice")
        void rediscoveryIsIdempotent() {
            SourceDiscoveryService discovery =
                    discoveryOver(Map.of("https://autorabit.com/careers", AUTORABIT_CAREERS));
            discovery.discover(List.of("autorabit.com"), List.of("AutoRABIT"), "test");

            DiscoveryRun again = discovery.discover(List.of("autorabit.com"), List.of("AutoRABIT"), "test");

            assertThat(again.registered()).isEmpty();
            assertThat(again.alreadyKnown()).containsExactly("autorabit.com");
        }

        @Test
        @DisplayName("a portal that needs partner credentials is recorded the same way, and never activated")
        void icimsIsRecordedOnly() {
            discoveryOver(Map.of("https://acme.com/careers",
                    "<a href=\"https://careers-acme.icims.com/jobs/search\">Jobs</a>"))
                    .discover(List.of("acme.com"), List.of("Acme"), "test");

            JobSource source = sources.findByBaseUrl("https://careers-acme.icims.com").orElseThrow();
            assertThat(source.getAtsProvider()).isEqualTo(AtsProvider.ICIMS);
            assertThat(source.getAdapterKey()).isNull();
            assertThat(source.getState()).isEqualTo(SourceState.DISCOVERED);
            assertThat(source.getDiscoveryDetail()).contains("partner credentials");
        }
    }

    @Nested
    @DisplayName("discovering a board CareerFlux can read")
    class Readable {

        @Test
        @DisplayName("a Greenhouse board is registered at its API with the adapter, as before")
        void greenhouseAsBefore() {
            discoveryOver(Map.of(
                    "https://acme.com/careers", "<a href=\"https://boards.greenhouse.io/acme\">Jobs</a>",
                    "https://boards-api.greenhouse.io/v1/boards/acme/jobs", "{\"jobs\":[{\"id\":1}]}"))
                    .discover(List.of("acme.com"), List.of("Acme"), "test");

            JobSource source = sources.findByBaseUrl("https://boards-api.greenhouse.io/v1/boards/acme/jobs")
                    .orElseThrow();
            assertThat(source.getAdapterKey()).isEqualTo(GreenhouseAdapter.KEY);
            assertThat(source.getSourceType()).isEqualTo(SourceType.ATS_PUBLIC_API);
            assertThat(source.getState()).isEqualTo(SourceState.DISCOVERED);
        }
    }

    @Nested
    @DisplayName("registering a pasted board URL")
    class ManualRegistration {

        private RegistrationRequest pasted(String url, String adapterKey) {
            return new RegistrationRequest("Acme", url, null, null, adapterKey, null, null,
                    "Registered through the admin console.", 20, "Acme", null, null);
        }

        @Test
        @DisplayName("a JazzHR job link is recognised and recorded at its board, with no adapter")
        void jazzHrLink() {
            JobSource source = registry.register(
                    pasted("https://acme.applytojob.com/apply/abc123/Backend-Engineer", null), "admin");

            assertThat(source.getBaseUrl()).isEqualTo("https://acme.applytojob.com/apply");
            assertThat(source.getAtsProvider()).isEqualTo(AtsProvider.JAZZHR);
            assertThat(source.getExternalIdentifier()).isEqualTo("acme");
            assertThat(source.getAdapterKey()).isNull();
            assertThat(source.getState()).isEqualTo(SourceState.DISCOVERED);
            assertThat(source.getDiscoveryMethod()).isEqualTo(DiscoveryMethod.MANUAL_SUBMISSION);
            // The URL that was stored is the URL that was validated.
            verify(urlValidator).validate("https://acme.applytojob.com/apply");
        }

        @Test
        @DisplayName("a Greenhouse board URL gets its adapter, its API and its token filled in")
        void greenhouseBoardUrl() {
            JobSource source = registry.register(pasted("https://boards.greenhouse.io/acme", null), "admin");

            assertThat(source.getBaseUrl()).isEqualTo("https://boards-api.greenhouse.io/v1/boards/acme/jobs");
            assertThat(source.getAtsProvider()).isEqualTo(AtsProvider.GREENHOUSE);
            assertThat(source.getAdapterKey()).isEqualTo(GreenhouseAdapter.KEY);
            assertThat(source.getExternalIdentifier()).isEqualTo("acme");
            assertThat(source.getSourceType()).isEqualTo(SourceType.ATS_PUBLIC_API);
            assertThat(source.getState()).isEqualTo(SourceState.DISCOVERED);
        }

        @Test
        @DisplayName("an operator who chose an adapter gets exactly what they submitted")
        void operatorChoiceIsKept() {
            JobSource source = registry.register(
                    pasted("https://boards.greenhouse.io/acme", GreenhouseAdapter.KEY), "admin");

            assertThat(source.getBaseUrl()).isEqualTo("https://boards.greenhouse.io/acme");
            assertThat(source.getExternalIdentifier()).isNull();
        }

        @Test
        @DisplayName("a URL nobody recognises is registered as submitted, with nothing guessed")
        void unrecognisedIsUntouched() {
            JobSource source = registry.register(pasted("https://careers.acme.example/jobs", null), "admin");

            assertThat(source.getBaseUrl()).isEqualTo("https://careers.acme.example/jobs");
            assertThat(source.getAtsProvider()).isEqualTo(AtsProvider.UNKNOWN);
            assertThat(source.getAdapterKey()).isNull();
            assertThat(source.getState()).isEqualTo(SourceState.DISCOVERED);
        }
    }
}
