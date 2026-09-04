package com.careerflux.source.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.careerflux.source.discovery.CompanyResolver.Candidate;
import com.careerflux.source.discovery.CompanyResolver.Outcome;
import com.careerflux.source.domain.Company;
import com.careerflux.source.repository.CompanyRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Turning a typed company name into a domain.
 *
 * <p>The network is stubbed, so what is under test is the decision: which
 * candidates get built, in what order they are believed, and — the part that
 * matters most — when the resolver refuses to pick. A resolver that guesses
 * confidently is worse than one that says AMBIGUOUS, because the guess ends with
 * students being shown another company's jobs under an employer's name.
 */
class CompanyResolverTest {

    private final CompanyRepository companies = mock(CompanyRepository.class);

    /** A verifier that only believes the domains it was handed. */
    private static CompanyResolver.DomainVerifier verifierAccepting(String... domains) {
        List<String> accepted = List.of(domains);
        return (domain, companyName) -> accepted.contains(domain)
                ? Optional.of(Candidate.verified(domain, "stubbed"))
                : Optional.empty();
    }

    private CompanyResolver resolverWith(CompanyResolver.DomainVerifier verifier) {
        when(companies.findBySlug(anyString())).thenReturn(Optional.empty());
        return new CompanyResolver(companies, verifier);
    }

    // ------------------------------------------------------------ priority

    @Nested
    @DisplayName("the order the resolver believes things in")
    class Priority {

        @Test
        @DisplayName("a domain already in the registry wins without any fetching")
        void registryFirst() {
            Company known = new Company();
            known.setName("Meesho");
            known.setSlug("meesho");
            known.setDomain("meesho.com");
            when(companies.findBySlug("meesho")).thenReturn(Optional.of(known));

            // The verifier would refuse everything; it must never be consulted.
            var resolver = new CompanyResolver(companies, verifierAccepting());
            var resolution = resolver.resolve("Meesho");

            assertThat(resolution.outcome()).isEqualTo(Outcome.RESOLVED);
            assertThat(resolution.singleDomain()).contains("meesho.com");
            assertThat(resolution.candidates().get(0).evidence())
                    .isEqualTo(CompanyResolver.Evidence.REGISTRY);
        }

        @Test
        @DisplayName("a company known by name but with no domain falls through")
        void registryWithoutDomainDoesNotShortCircuit() {
            // This is the live situation: 21 companies exist, none has a domain.
            // They must not resolve to nothing on the strength of merely existing.
            Company known = new Company();
            known.setName("Meesho");
            known.setSlug("meesho");
            known.setDomain(null);
            when(companies.findBySlug("meesho")).thenReturn(Optional.of(known));

            var resolver = new CompanyResolver(companies, verifierAccepting("meesho.com"));
            var resolution = resolver.resolve("Meesho");

            assertThat(resolution.outcome()).isEqualTo(Outcome.RESOLVED);
            assertThat(resolution.singleDomain()).contains("meesho.com");
        }

        @Test
        @DisplayName("the seed list is consulted before anything is fetched")
        void seedListSecond() {
            var resolver = resolverWith(verifierAccepting("razorpay.in"));
            var resolution = resolver.resolve("Razorpay");

            // razorpay.com is in the seed list. The verifier only accepts .in,
            // so if the seed list were skipped this would resolve to .in.
            assertThat(resolution.outcome()).isEqualTo(Outcome.RESOLVED);
            assertThat(resolution.singleDomain()).contains("razorpay.com");
            assertThat(resolution.candidates().get(0).evidence())
                    .isEqualTo(CompanyResolver.Evidence.SEED_LIST);
        }

        @Test
        @DisplayName("a constructed candidate is used only once the site confirms it")
        void constructedLast() {
            var resolver = resolverWith(verifierAccepting("autorabit.com"));
            var resolution = resolver.resolve("AutoRABIT");

            assertThat(resolution.outcome()).isEqualTo(Outcome.RESOLVED);
            assertThat(resolution.singleDomain()).contains("autorabit.com");
            assertThat(resolution.candidates().get(0).evidence())
                    .isEqualTo(CompanyResolver.Evidence.VERIFIED_SITE);
        }
    }

    // ----------------------------------------------------------- refusals

    @Nested
    @DisplayName("when the resolver must not pick")
    class Refusals {

        @Test
        @DisplayName("two domains both answering as the company is ambiguous, not a coin toss")
        void ambiguousWhenSeveralVerify() {
            var resolver = resolverWith(verifierAccepting("acme.com", "acme.in"));
            var resolution = resolver.resolve("Acme");

            assertThat(resolution.outcome()).isEqualTo(Outcome.AMBIGUOUS);
            assertThat(resolution.candidates()).hasSize(2);
            assertThat(resolution.singleDomain()).isEmpty();
        }

        @Test
        @DisplayName("nothing verifying is NOT_FOUND, and says the operator can type one")
        void notFoundWhenNothingVerifies() {
            var resolver = resolverWith(verifierAccepting());
            var resolution = resolver.resolve("Nonexistent Company");

            assertThat(resolution.outcome()).isEqualTo(Outcome.NOT_FOUND);
            assertThat(resolution.candidates()).isEmpty();
            assertThat(resolution.detail()).contains("directly");
        }

        @Test
        @DisplayName("a blank or punctuation-only name is refused rather than guessed at")
        void unusableNames() {
            var resolver = resolverWith(verifierAccepting("com"));
            for (String name : List.of("", "   ", "!!!", "---")) {
                var resolution = resolver.resolve(name);
                assertThat(resolution.outcome())
                        .describedAs("name '%s'", name)
                        .isEqualTo(Outcome.NOT_FOUND);
                assertThat(resolution.candidates()).isEmpty();
            }
            assertThat(resolverWith(verifierAccepting()).resolve(null).outcome())
                    .isEqualTo(Outcome.NOT_FOUND);
        }

        @Test
        @DisplayName("a resolution never yields a single domain unless it is RESOLVED")
        void singleDomainOnlyWhenResolved() {
            assertThat(resolverWith(verifierAccepting("a.com", "a.in"))
                    .resolve("A Company").singleDomain()).isEmpty();
            assertThat(resolverWith(verifierAccepting())
                    .resolve("Nothing").singleDomain()).isEmpty();
        }
    }

    // -------------------------------------------------- candidate building

    @Nested
    @DisplayName("the domains that get constructed")
    class Candidates {

        @Test
        @DisplayName("India first, because that is the market this serves")
        void suffixOrder() {
            var domains = CompanyResolver.candidateDomains("meesho");
            assertThat(domains).startsWith("meesho.com", "meesho.in", "meesho.co.in");
        }

        @Test
        @DisplayName("a multi-word name is also tried without the separators")
        void squashedVariant() {
            var domains = CompanyResolver.candidateDomains("auto-rabit");
            assertThat(domains).contains("auto-rabit.com", "autorabit.com");
        }

        @Test
        @DisplayName("corporate boilerplate is dropped")
        void stripsNoise() {
            assertThat(CompanyResolver.stripNoise("infosys-limited")).isEqualTo("infosys");
            assertThat(CompanyResolver.stripNoise("acme-technologies-pvt-ltd")).isEqualTo("acme");
            assertThat(CompanyResolver.stripNoise("wipro")).isEqualTo("wipro");
        }

        @Test
        @DisplayName("boilerplate is stripped before the domain is built, not just in isolation")
        void noiseStrippingReachesTheCandidates() {
            // stripNoise() being correct on its own is not enough; candidateDomains
            // has to actually use it, or "Infosys Limited" is probed as
            // infosys-limited.com and never resolves.
            assertThat(CompanyResolver.candidateDomains("infosys-limited"))
                    .contains("infosys.com", "infosys.in")
                    .doesNotContain("infosys-limited.com");
            assertThat(CompanyResolver.candidateDomains("acme-technologies-pvt-ltd"))
                    .contains("acme.com")
                    .doesNotContain("acme-technologies-pvt-ltd.com");
        }

        @Test
        @DisplayName("a name made entirely of boilerplate keeps itself")
        void doesNotStripEverything() {
            // A company really called "Systems" must still get a candidate
            // rather than resolving to ".com".
            assertThat(CompanyResolver.stripNoise("systems")).isEqualTo("systems");
            assertThat(CompanyResolver.candidateDomains("systems")).contains("systems.com");
        }

        @Test
        @DisplayName("a candidate is always a bare domain, never a URL with extras")
        void candidatesAreBareDomains() {
            // slugify has already reduced the input to letters, digits and
            // hyphens, so nothing here can smuggle a path, port, userinfo or a
            // second host into the string a fetch is built from.
            for (String nasty : List.of("acme.com/../etc", "acme@evil.com", "acme:8080",
                    "acme com", "acme#frag", "acme?q=1")) {
                for (String domain : CompanyResolver.candidateDomains(
                        com.careerflux.common.TextUtils.slugify(nasty))) {
                    assertThat(domain)
                            .describedAs("from '%s'", nasty)
                            .doesNotContain("/").doesNotContain("@").doesNotContain(":")
                            .doesNotContain("?").doesNotContain("#").doesNotContain(" ");
                }
            }
        }

        @Test
        @DisplayName("an empty core produces no candidates at all")
        void emptyCore() {
            assertThat(CompanyResolver.candidateDomains("")).isEmpty();
        }
    }

    // ------------------------------------------------------- verification

    @Nested
    @DisplayName("what counts as a site confirming its own name")
    class Verification {

        @Test
        @DisplayName("punctuation, spacing and case do not matter")
        void flattenedComparison() {
            assertThat(HttpDomainVerifier.mentions("<title>AutoRABIT | DevOps</title>", "AutoRABIT")).isTrue();
            assertThat(HttpDomainVerifier.mentions("Auto RABIT Inc.", "auto-rabit")).isTrue();
            assertThat(HttpDomainVerifier.mentions("MEESHO", "Meesho")).isTrue();
        }

        @Test
        @DisplayName("a page that does not name the company is not evidence")
        void requiresTheNameToAppear() {
            assertThat(HttpDomainVerifier.mentions(
                    "<title>Domain for sale</title>", "Meesho")).isFalse();
            assertThat(HttpDomainVerifier.mentions(
                    "<title>Welcome to nginx!</title>", "Meesho")).isFalse();
            assertThat(HttpDomainVerifier.mentions("", "Meesho")).isFalse();
            assertThat(HttpDomainVerifier.mentions(null, "Meesho")).isFalse();
        }

        @Test
        @DisplayName("a name too short to be evidence never matches")
        void tooShortToMatch() {
            // Two characters would match almost any page by accident.
            assertThat(HttpDomainVerifier.mentions("a page about hp printers", "hp")).isFalse();
            assertThat(HttpDomainVerifier.mentions("anything at all", "")).isFalse();
        }

        @Test
        @DisplayName("the title is pulled out of the markup")
        void extractsTitle() {
            assertThat(HttpDomainVerifier.titleOf("<html><head><title>Meesho</title></head>"))
                    .isEqualTo("Meesho");
            assertThat(HttpDomainVerifier.titleOf("<TITLE >\n  Zeta\n</TITLE>")).isEqualTo("Zeta");
            assertThat(HttpDomainVerifier.titleOf("<html>no title here</html>")).isEmpty();
        }

        @Test
        @DisplayName("comparable() keeps only letters and digits")
        void comparable() {
            assertThat(HttpDomainVerifier.comparable("Auto-RABIT, Inc.")).isEqualTo("autorabitinc");
            assertThat(HttpDomainVerifier.comparable("  ")).isEmpty();
        }
    }

    // ------------------------------------------------------------ linkage

    @Nested
    @DisplayName("company identity")
    class Identity {

        @Test
        @DisplayName("names that differ only in case or punctuation are one company")
        void slugIsTheIdentity() {
            Company known = new Company();
            known.setName("Meesho");
            known.setSlug("meesho");
            known.setDomain("meesho.com");
            known.setId(UUID.randomUUID());
            when(companies.findBySlug("meesho")).thenReturn(Optional.of(known));

            var resolver = new CompanyResolver(companies, verifierAccepting());
            for (String spelling : List.of("Meesho", "meesho", "  MEESHO  ")) {
                assertThat(resolver.resolve(spelling).singleDomain())
                        .describedAs("spelling '%s'", spelling)
                        .contains("meesho.com");
            }
        }

        @Test
        @DisplayName("the slug is reported so callers link to the right company")
        void reportsTheSlug() {
            var resolution = resolverWith(verifierAccepting()).resolve("Acme Technologies Pvt Ltd");
            assertThat(resolution.slug()).isEqualTo("acme-technologies-pvt-ltd");
            assertThat(resolution.companyName()).isEqualTo("Acme Technologies Pvt Ltd");
        }
    }

    @Test
    @DisplayName("no two seeded employers share a first label")
    void seedLabelsAreUnique() {
        // The resolver treats a single seed match as authoritative and only
        // reports AMBIGUOUS when two seeds collide. That collision branch is
        // unreachable while this holds — so if someone adds a seed that collides
        // with an existing one, this fails and says the branch now matters.
        var labels = IndianEmployerSeeds.all().stream()
                .map(domain -> domain.split("\\.")[0].toLowerCase(java.util.Locale.ROOT))
                .toList();
        assertThat(labels).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the seed list is only matched on a domain's own first label")
    void seedMatchingIsNotSubstring() {
        // "Zoho" must not match "zomato.com", and a name that is a substring of
        // a seeded domain must not be claimed by it.
        var resolver = resolverWith(verifierAccepting());
        Map<String, Outcome> expectations = Map.of(
                "Zo", Outcome.NOT_FOUND,
                "Razor", Outcome.NOT_FOUND);
        expectations.forEach((name, expected) ->
                assertThat(resolver.resolve(name).outcome())
                        .describedAs("name '%s'", name)
                        .isEqualTo(expected));
    }
}
