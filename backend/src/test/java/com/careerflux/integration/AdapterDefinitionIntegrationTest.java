package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.careerflux.common.error.ConflictException;
import com.careerflux.source.adapter.AdapterRegistry;
import com.careerflux.source.adapter.GreenhouseAdapter;
import com.careerflux.source.adapter.SourceMetadata;
import com.careerflux.source.domain.AdapterDefinition;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.DiscoveryMethod;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.SourceAccessPolicy;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.repository.AdapterDefinitionRepository;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.source.service.AdapterDefinitionService;
import com.careerflux.source.service.AdapterDefinitionService.Definition;
import com.careerflux.source.service.SourceRegistryService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * An admin-defined adapter, from the console to a classified source.
 *
 * <p>Nothing here makes a request: defining, finding and classifying never fetch.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdapterDefinitionIntegrationTest {

    @Autowired
    private AdapterDefinitionService definitions;

    @Autowired
    private AdapterDefinitionRepository repository;

    @Autowired
    private AdapterRegistry registry;

    @Autowired
    private SourceRegistryService sources;

    @Autowired
    private JobSourceRepository sourceRepository;

    private static Definition feed(String key) {
        return new Definition(key, "Northgate feed", "Northgate's public jobs API.", null,
                "https://api.northgate-systems.in/v1/{token}/jobs",
                "data.jobs", "id", "title", "location.city", "description", "url", null,
                "posted", "type", "team");
    }

    @Test
    @DisplayName("a saved definition is an adapter the registry finds and lists, at once")
    void savedIsFound() {
        AdapterDefinition saved = definitions.create(feed(null), "admin@careerflux.local");

        assertThat(saved.getAdapterKey()).isEqualTo("custom-northgate-feed");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(registry.has("custom-northgate-feed")).isTrue();
        assertThat(registry.describeAll()).extracting(SourceMetadata::key).contains("custom-northgate-feed");
    }

    @Test
    @DisplayName("switched off, its sources have no adapter; switched on, they do again")
    void disableAndEnable() {
        definitions.create(feed("custom-ng"), "admin@careerflux.local");

        definitions.setEnabled("custom-ng", false, "admin@careerflux.local");
        assertThat(registry.find("custom-ng")).isEmpty();
        assertThat(registry.describeAll()).extracting(SourceMetadata::key).doesNotContain("custom-ng");

        definitions.setEnabled("custom-ng", true, "admin@careerflux.local");
        assertThat(registry.find("custom-ng")).isPresent();
    }

    @Test
    @DisplayName("a key is taken once, and a built-in adapter's name only ever makes a custom- key")
    void keys() {
        definitions.create(feed("custom-ng"), "admin@careerflux.local");
        assertThatThrownBy(() -> definitions.create(feed("custom-ng"), "admin@careerflux.local"))
                .isInstanceOf(ConflictException.class);

        AdapterDefinition named = definitions.create(feed("greenhouse"), "admin@careerflux.local");
        assertThat(named.getAdapterKey()).isEqualTo("custom-greenhouse");
        assertThat(registry.find("greenhouse")).get().isInstanceOf(GreenhouseAdapter.class);
    }

    @Test
    @DisplayName("the database refuses a key without the prefix, whatever writes it")
    void databaseEnforcesPrefix() {
        AdapterDefinition rogue = new AdapterDefinition();
        rogue.setAdapterKey("lever");
        rogue.setDisplayName("Not really Lever");
        rogue.setEndpointTemplate("https://api.northgate-systems.in/jobs");
        rogue.setIdPath("/id");
        rogue.setTitlePath("/title");
        rogue.setEnabled(true);
        rogue.setCreatedBy("test");
        rogue.setUpdatedBy("test");

        assertThatThrownBy(() -> repository.saveAndFlush(rogue))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a source registered under the key classifies to it and stops there for review")
    void sourceClassifies() {
        definitions.create(feed("custom-ng"), "admin@careerflux.local");
        JobSource source = discovered("custom-ng");

        var result = sources.classify(source.getId(), "admin@careerflux.local");

        assertThat(result.classified()).isTrue();
        JobSource classified = sourceRepository.findById(source.getId()).orElseThrow();
        assertThat(classified.getAdapterKey()).isEqualTo("custom-ng");
        assertThat(classified.getState())
                .describedAs("classified, not active: robots.txt and the terms review still stand")
                .isEqualTo(SourceState.CLASSIFIED);
    }

    @Test
    @DisplayName("a disabled definition classifies nothing")
    void disabledClassifiesNothing() {
        definitions.create(feed("custom-ng"), "admin@careerflux.local");
        definitions.setEnabled("custom-ng", false, "admin@careerflux.local");
        JobSource source = discovered("custom-ng");

        assertThat(sources.classify(source.getId(), "admin@careerflux.local").classified()).isFalse();
    }

    private JobSource discovered(String adapterKey) {
        long unique = System.nanoTime();
        JobSource source = new JobSource();
        source.setName("Northgate " + unique);
        source.setBaseUrl("https://api.northgate-systems.in/v1/northgate-" + unique + "/jobs");
        source.setSourceType(SourceType.UNKNOWN);
        source.setAtsProvider(AtsProvider.UNKNOWN);
        source.setAdapterKey(adapterKey);
        source.setExternalIdentifier("northgate");
        source.setDiscoveryMethod(DiscoveryMethod.MANUAL_SUBMISSION);
        source.setRateLimitPerMinute(20);
        source.setDiscoveredAt(Instant.now());
        source.setState(SourceState.DISCOVERED);
        source.setStateChangedAt(Instant.now());
        source.setAccessPolicy(new SourceAccessPolicy());
        return sourceRepository.saveAndFlush(source);
    }
}
