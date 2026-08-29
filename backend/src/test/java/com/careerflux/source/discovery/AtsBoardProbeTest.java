package com.careerflux.source.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pure parts of discovery: turning what somebody typed into a domain that
 * can be probed.
 *
 * <p>The probing itself talks to third-party APIs and is exercised against real
 * infrastructure rather than being asserted here against a mock, because a mock
 * of Greenhouse only proves the mock matches my belief about Greenhouse.
 */
class AtsBoardProbeTest {

    @Test
    @DisplayName("a domain is accepted however it was pasted")
    void normalizesTheUsualPasteVariants() {
        assertThat(AtsBoardProbe.normalizeDomain("razorpay.com")).isEqualTo("razorpay.com");
        assertThat(AtsBoardProbe.normalizeDomain("https://razorpay.com")).isEqualTo("razorpay.com");
        assertThat(AtsBoardProbe.normalizeDomain("https://www.razorpay.com/careers"))
                .isEqualTo("razorpay.com");
        assertThat(AtsBoardProbe.normalizeDomain("  RAZORPAY.COM  ")).isEqualTo("razorpay.com");
        assertThat(AtsBoardProbe.normalizeDomain("http://www.zoho.com/")).isEqualTo("zoho.com");
    }

    @Test
    @DisplayName("a subdomain is preserved, because the board may live there")
    void keepsSubdomains() {
        assertThat(AtsBoardProbe.normalizeDomain("careers.zoho.com")).isEqualTo("careers.zoho.com");
    }

    @Test
    @DisplayName("anything that is not a domain is rejected rather than probed")
    void rejectsNonDomains() {
        assertThat(AtsBoardProbe.normalizeDomain(null)).isEmpty();
        assertThat(AtsBoardProbe.normalizeDomain("")).isEmpty();
        assertThat(AtsBoardProbe.normalizeDomain("   ")).isEmpty();
        assertThat(AtsBoardProbe.normalizeDomain("not a domain")).isEmpty();
        assertThat(AtsBoardProbe.normalizeDomain("localhost")).isEmpty();
    }

    @Test
    @DisplayName("the seed list is domains, not board tokens")
    void seedListHoldsDomains() {
        // A board token here would mean somebody had already done discovery's
        // job by hand, which is the thing this whole mechanism exists to avoid.
        assertThat(IndianEmployerSeeds.all()).isNotEmpty();
        assertThat(IndianEmployerSeeds.all()).allSatisfy(domain ->
                assertThat(AtsBoardProbe.normalizeDomain(domain))
                        .as("%s should be a probeable domain", domain)
                        .isEqualTo(domain));
    }

    @Test
    @DisplayName("the seed list contains no aggregator we are not allowed to read")
    void seedListExcludesProhibitedAggregators() {
        // Probing these would only produce sources the policy engine must refuse.
        assertThat(IndianEmployerSeeds.all()).noneSatisfy(domain ->
                assertThat(domain).containsAnyOf(
                        "naukri", "linkedin", "indeed", "internshala", "monster", "shine.com"));
    }
}
