package com.careerflux.source.discovery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.careerflux.common.TextUtils;
import com.careerflux.source.domain.Company;
import com.careerflux.source.repository.CompanyRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns a company name into the domain that company actually owns.
 *
 * <p>This is the one step CareerFlux was missing. Everything downstream —
 * {@link AtsBoardProbe}, {@link SourceDiscoveryService}, the policy gate, the
 * whole ingestion pipeline — already works from a domain. An operator, though,
 * knows the employer they want on campus by name, not by the domain its careers
 * page happens to live on.
 *
 * <p><b>A name never becomes a URL directly.</b> "Meesho" does not turn into
 * {@code https://meesho.com} because the two strings look alike. Candidates are
 * constructed from a fixed set of suffixes, every one of them is validated as a
 * fetchable public address before it is touched, and a candidate only counts as
 * resolved once the site at the far end says the company's own name back. A
 * domain nobody verified never reaches the probe, and never becomes a source.
 *
 * <p><b>Deterministic on purpose.</b> No search API, no model, no ranking that
 * cannot be explained. The order is: what CareerFlux already knows, then the
 * market seed list, then constructed candidates, each verified. When that is not
 * enough the honest answers are AMBIGUOUS and NOT_FOUND, and the operator types
 * the domain themselves — which is a better outcome than a confident guess,
 * because a wrong domain means students being shown another company's jobs under
 * an employer's name.
 */
@Service
public class CompanyResolver {

    private static final Logger log = LoggerFactory.getLogger(CompanyResolver.class);

    /**
     * Suffixes tried for a constructed candidate, most likely first.
     *
     * <p>India-first, because that is the market CareerFlux serves: an Indian
     * employer is far more likely to be on {@code .in} or {@code .co.in} than on
     * the long tail of generic suffixes, and every extra suffix is another
     * request to somebody who did not ask to be probed.
     */
    static final List<String> SUFFIXES = List.of(".com", ".in", ".co.in", ".io");

    /** Corporate boilerplate that is part of a legal name but never of a domain. */
    private static final Set<String> NAME_NOISE = Set.of(
            "pvt", "private", "ltd", "limited", "inc", "incorporated", "llp", "llc",
            "corp", "corporation", "co", "company", "technologies", "technology",
            "solutions", "services", "systems", "labs", "software", "india", "global",
            "international", "group", "holdings");

    private final CompanyRepository companies;
    private final DomainVerifier verifier;

    public CompanyResolver(CompanyRepository companies, DomainVerifier verifier) {
        this.companies = companies;
        this.verifier = verifier;
    }

    /**
     * Works out which domain, if any, belongs to this company name.
     *
     * <p>Never registers anything and never probes for a job board. It answers
     * "which domain is this" and stops, so the operator can confirm before
     * CareerFlux goes looking for a board.
     */
    public Resolution resolve(String companyName) {
        if (!TextUtils.hasText(companyName)) {
            return Resolution.notFound("", "", "A company name is required.");
        }
        String name = companyName.strip();
        String slug = TextUtils.slugify(name);
        if (slug.isEmpty()) {
            return Resolution.notFound(name, slug,
                    "That name has no letters or digits to build a domain from.");
        }

        // 1. What CareerFlux already knows. A domain recorded against a company
        // was verified when it was recorded, so it does not get re-litigated.
        Optional<Company> known = companies.findBySlug(slug);
        if (known.isPresent() && TextUtils.hasText(known.get().getDomain())) {
            String domain = known.get().getDomain();
            return Resolution.resolved(name, slug,
                    List.of(Candidate.known(domain)),
                    "Already recorded against this company in the registry.");
        }

        // 2. The market seed list, matched on the domain's own first label.
        List<String> seeded = seedMatches(slug);
        if (seeded.size() == 1) {
            return Resolution.resolved(name, slug,
                    List.of(Candidate.seeded(seeded.get(0))),
                    "Matched an employer in the built-in market list.");
        }
        if (seeded.size() > 1) {
            return Resolution.ambiguous(name, slug,
                    seeded.stream().map(Candidate::seeded).toList(),
                    "More than one employer in the market list matches that name.");
        }

        // 3. Constructed candidates, each verified against the site itself.
        List<Candidate> verified = new ArrayList<>();
        for (String candidate : candidateDomains(slug)) {
            verifier.verify(candidate, name).ifPresent(verified::add);
        }

        if (verified.isEmpty()) {
            return Resolution.notFound(name, slug,
                    "No domain CareerFlux constructed for that name answered as that company. "
                            + "Enter the careers domain directly if you know it.");
        }
        if (verified.size() > 1) {
            // Two sites both claiming the name is exactly the case not to guess
            // at: an operator can tell meesho.com from meesho.in in a second,
            // and getting it wrong puts another company's jobs in front of
            // students under this employer's name.
            return Resolution.ambiguous(name, slug, verified,
                    "More than one domain answers as that company. Choose the right one.");
        }
        return Resolution.resolved(name, slug, verified,
                "One domain answered as that company.");
    }

    /**
     * Domains to try for a slug, in order.
     *
     * <p>Pure and deliberately small. The input is already reduced to letters and
     * digits by slugify, so nothing here can produce a path, a port, a userinfo
     * section or a second host — the constructed string is always a bare domain,
     * and it is validated again before anything fetches it.
     */
    static List<String> candidateDomains(String slug) {
        String core = stripNoise(slug);
        if (core.isEmpty()) {
            return List.of();
        }
        Set<String> domains = new LinkedHashSet<>();
        for (String suffix : SUFFIXES) {
            domains.add(core + suffix);
        }
        // A multi-word name is also worth trying without the separators:
        // "auto-rabit" is auto-rabit.com to nobody and autorabit.com to everyone.
        String squashed = core.replace("-", "");
        if (!squashed.equals(core) && !squashed.isEmpty()) {
            for (String suffix : SUFFIXES) {
                domains.add(squashed + suffix);
            }
        }
        return List.copyOf(domains);
    }

    /**
     * Drops the corporate furniture from a slug.
     *
     * <p>"Infosys Limited" is infosys.com, not infosys-limited.com. Every word is
     * kept if stripping would leave nothing, because a company genuinely called
     * "Systems" should still get a candidate.
     */
    static String stripNoise(String slug) {
        List<String> kept = new ArrayList<>();
        for (String part : slug.split("-")) {
            if (!part.isEmpty() && !NAME_NOISE.contains(part.toLowerCase(Locale.ROOT))) {
                kept.add(part);
            }
        }
        return kept.isEmpty() ? slug : String.join("-", kept);
    }

    /** Seed-list domains whose first label matches this slug. */
    private List<String> seedMatches(String slug) {
        String core = stripNoise(slug);
        String squashed = core.replace("-", "");
        List<String> matches = new ArrayList<>();
        for (String domain : IndianEmployerSeeds.all()) {
            String label = domain.split("\\.")[0].toLowerCase(Locale.ROOT);
            if (label.equals(core) || label.equals(squashed)) {
                matches.add(domain);
            }
        }
        return matches;
    }

    /** How a candidate domain came to be proposed. */
    public enum Evidence {
        /** Already recorded against this company in the registry. */
        REGISTRY,
        /** Present in the built-in market seed list. */
        SEED_LIST,
        /** Constructed from the name, then confirmed by the site itself. */
        VERIFIED_SITE
    }

    /** What the resolver concluded. */
    public enum Outcome {
        /** Exactly one domain, good enough to offer for confirmation. */
        RESOLVED,
        /** Several plausible domains. A person picks; CareerFlux does not. */
        AMBIGUOUS,
        /** Nothing found. The operator can still supply a domain directly. */
        NOT_FOUND
    }

    /**
     * One proposed domain.
     *
     * <p>{@code detail} exists so the screen can say <em>why</em> a domain is
     * being offered. An operator confirming a destination CareerFlux is about to
     * fetch deserves to know whether it came from the registry or from a guess
     * that happened to answer.
     */
    public record Candidate(String domain, Evidence evidence, String detail) {

        static Candidate known(String domain) {
            return new Candidate(domain, Evidence.REGISTRY, "Already known for this company.");
        }

        static Candidate seeded(String domain) {
            return new Candidate(domain, Evidence.SEED_LIST, "In the built-in market list.");
        }

        public static Candidate verified(String domain, String detail) {
            return new Candidate(domain, Evidence.VERIFIED_SITE, detail);
        }
    }

    /** The answer for one company name. */
    public record Resolution(String companyName, String slug, Outcome outcome,
                             List<Candidate> candidates, String detail) {

        static Resolution resolved(String name, String slug, List<Candidate> candidates, String detail) {
            return new Resolution(name, slug, Outcome.RESOLVED, List.copyOf(candidates), detail);
        }

        static Resolution ambiguous(String name, String slug, List<Candidate> candidates, String detail) {
            return new Resolution(name, slug, Outcome.AMBIGUOUS, List.copyOf(candidates), detail);
        }

        static Resolution notFound(String name, String slug, String detail) {
            return new Resolution(name, slug, Outcome.NOT_FOUND, List.of(), detail);
        }

        /** The single domain, when there is exactly one. */
        public Optional<String> singleDomain() {
            return outcome == Outcome.RESOLVED && candidates.size() == 1
                    ? Optional.of(candidates.get(0).domain())
                    : Optional.empty();
        }
    }

    /**
     * Confirms that a domain really is the company it was constructed for.
     *
     * <p>An interface so the rule can be tested without the network, and so the
     * only code that makes an outbound request during resolution sits in one
     * small implementation that goes through {@code SafeUrlValidator}.
     */
    public interface DomainVerifier {

        /**
         * @return the candidate when the site at this domain identifies itself as
         *         this company, empty otherwise
         */
        Optional<Candidate> verify(String domain, String companyName);
    }

    /** Logs at debug what was tried, which is the only trace a resolve leaves. */
    void logAttempt(String slug, List<String> tried) {
        log.debug("Resolving '{}' tried {}", slug, tried);
    }
}
