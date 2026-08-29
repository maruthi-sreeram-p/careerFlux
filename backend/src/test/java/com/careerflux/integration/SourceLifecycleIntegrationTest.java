package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.careerflux.common.error.IllegalStateTransitionException;
import com.careerflux.common.error.SourcePolicyException;
import com.careerflux.source.adapter.LocalFixtureAdapter;
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
import com.careerflux.source.service.SourceLifecycleService;
import com.careerflux.source.service.SourceRegistryService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The policy gate as it behaves in the running application, not just as a pure
 * function. The important guarantee is that there is no path to ACTIVE that
 * skips it — including the service methods an operator's UI actually calls.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SourceLifecycleIntegrationTest {

    @Autowired
    private JobSourceRepository sourceRepository;

    @Autowired
    private SourceLifecycleService lifecycleService;

    @Autowired
    private SourceRegistryService registryService;

    private JobSource newSource(String url) {
        JobSource source = new JobSource();
        source.setName("Test source " + url);
        source.setBaseUrl(url);
        source.setSourceType(SourceType.LOCAL_FIXTURE);
        source.setAtsProvider(AtsProvider.NONE);
        source.setAdapterKey(LocalFixtureAdapter.KEY);
        source.setExternalIdentifier("sample-employers");
        source.setDiscoveryMethod(DiscoveryMethod.SEED);
        source.setRateLimitPerMinute(60);
        source.setDiscoveredAt(Instant.now());
        source.setState(SourceState.DISCOVERED);
        source.setStateChangedAt(Instant.now());
        source.setAccessPolicy(new SourceAccessPolicy());
        return sourceRepository.saveAndFlush(source);
    }

    private void satisfyPolicy(JobSource source) {
        SourceAccessPolicy policy = source.getAccessPolicy();
        policy.setRobotsStatus(RobotsStatus.ALLOWED);
        policy.setRobotsCheckedAt(Instant.now());
        policy.setTosStatus(TosStatus.PERMITTED);
        policy.setTosReviewedBy("ops@careerflux.local");
        policy.setTosReviewedAt(Instant.now());
        policy.setAccessPolicy(AccessPolicyType.PUBLIC_FEED);
        sourceRepository.saveAndFlush(source);
    }

    @Test
    @DisplayName("a source with an unsatisfied policy cannot be activated")
    void policyGateBlocksActivation() {
        JobSource source = newSource("local://test/blocked-by-policy");
        lifecycleService.transition(source, SourceState.CLASSIFIED, "test", "classified");
        lifecycleService.transition(source, SourceState.POLICY_REVIEW, "test", "in review");
        lifecycleService.transition(source, SourceState.APPROVED, "test", "approved by hand");

        assertThatThrownBy(() -> lifecycleService.transition(source, SourceState.ACTIVE, "test", "go"))
                .isInstanceOf(SourcePolicyException.class)
                .hasMessageContaining("access policy");

        assertThat(sourceRepository.findById(source.getId()).orElseThrow().getState())
                .isEqualTo(SourceState.APPROVED);
    }

    @Test
    @DisplayName("a source with a satisfied policy activates")
    void satisfiedPolicyActivates() {
        JobSource source = newSource("local://test/activates");
        satisfyPolicy(source);

        lifecycleService.transition(source, SourceState.CLASSIFIED, "test", "classified");
        lifecycleService.transition(source, SourceState.POLICY_REVIEW, "test", "in review");
        lifecycleService.transition(source, SourceState.APPROVED, "test", "approved");
        lifecycleService.transition(source, SourceState.ACTIVE, "test", "activated");

        assertThat(sourceRepository.findById(source.getId()).orElseThrow().getState())
                .isEqualTo(SourceState.ACTIVE);
    }

    @Test
    @DisplayName("an illegal transition is refused even when the policy would pass")
    void illegalTransitionsAreRefused() {
        JobSource source = newSource("local://test/illegal");
        satisfyPolicy(source);

        assertThatThrownBy(() -> lifecycleService.transition(source, SourceState.ACTIVE, "test", "skip"))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    @DisplayName("terms that prohibit automated access block the source outright")
    void prohibitedTermsBlockTheSource() {
        JobSource source = newSource("local://test/prohibited");
        lifecycleService.transition(source, SourceState.CLASSIFIED, "test", "classified");

        registryService.recordTermsReview(source.getId(), TosStatus.PROHIBITED,
                "https://example.invalid/terms", "Scraping is forbidden.", "ops@careerflux.local");

        assertThat(sourceRepository.findById(source.getId()).orElseThrow().getState())
                .isEqualTo(SourceState.BLOCKED);
    }

    @Test
    @DisplayName("an endpoint that turns out to need a login is blocked")
    void authenticationRequirementBlocksTheSource() {
        JobSource source = newSource("local://test/needs-login");
        lifecycleService.transition(source, SourceState.CLASSIFIED, "test", "classified");

        registryService.recordAccessCharacteristics(source.getId(), AccessPolicyType.RESTRICTED,
                true, false, false, false, null, "ops@careerflux.local");

        assertThat(sourceRepository.findById(source.getId()).orElseThrow().getState())
                .isEqualTo(SourceState.BLOCKED);
    }

    @Test
    @DisplayName("every transition is recorded with its reason and actor")
    void transitionsAreAudited() {
        JobSource source = newSource("local://test/audited");
        satisfyPolicy(source);
        lifecycleService.transition(source, SourceState.CLASSIFIED, "alice@careerflux.local", "matched adapter");

        var history = lifecycleService.history(source.getId());
        assertThat(history).isNotEmpty();
        var last = history.get(history.size() - 1);
        assertThat(last.getToState()).isEqualTo(SourceState.CLASSIFIED);
        assertThat(last.getActor()).isEqualTo("alice@careerflux.local");
        assertThat(last.getReason()).isEqualTo("matched adapter");
    }

    @Test
    @DisplayName("classification picks the adapter that claims the source")
    void classificationAssignsAnAdapter() {
        JobSource source = newSource("local://test/classify-me");
        var result = registryService.classify(source.getId(), "test");

        assertThat(result.classified()).isTrue();
        assertThat(result.metadata().key()).isEqualTo(LocalFixtureAdapter.KEY);
        assertThat(sourceRepository.findById(source.getId()).orElseThrow().getState())
                .isEqualTo(SourceState.CLASSIFIED);
    }

    @Test
    @DisplayName("a source no adapter recognises stays where it is")
    void unrecognisedSourceIsNotClassified() {
        JobSource source = newSource("https://unknown-ats.invalid/careers");
        source.setAdapterKey(null);
        sourceRepository.saveAndFlush(source);

        var result = registryService.classify(source.getId(), "test");

        assertThat(result.classified()).isFalse();
        assertThat(result.reason()).contains("No registered adapter");
        assertThat(sourceRepository.findById(source.getId()).orElseThrow().getState())
                .isEqualTo(SourceState.DISCOVERED);
    }
}
