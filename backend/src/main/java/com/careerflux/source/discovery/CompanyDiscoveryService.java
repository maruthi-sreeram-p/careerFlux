package com.careerflux.source.discovery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.careerflux.common.TextUtils;
import com.careerflux.source.discovery.CompanyResolver.Outcome;
import com.careerflux.source.discovery.CompanyResolver.Resolution;
import com.careerflux.source.net.SafeUrlValidator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Company name in, job source out — in two deliberate steps.
 *
 * <p><b>Step one resolves and stops.</b> Given names, it works out which domains
 * they might be and returns them. Nothing is probed for a job board and nothing
 * is registered. The operator sees what CareerFlux believes and why.
 *
 * <p><b>Step two runs only on domains a person confirmed.</b> Those go to the
 * existing {@link SourceDiscoveryService}, which is unchanged: same probe, same
 * registration, same lifecycle, same policy gate. A source discovered this way
 * lands at DISCOVERED exactly like a hand-submitted one and still has to pass
 * robots, terms and access review before it can be synced.
 *
 * <p>The split is the whole point. Resolution is a guess CareerFlux makes;
 * probing is a request it sends to somebody else's servers, and registration is
 * a claim that an employer's jobs belong in front of students. A person stands
 * between the guess and the consequences.
 */
@Service
public class CompanyDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(CompanyDiscoveryService.class);

    /** One request should not turn into an unbounded probing run. */
    static final int MAX_NAMES_PER_REQUEST = 25;

    private final CompanyResolver resolver;
    private final SourceDiscoveryService sourceDiscovery;
    private final SafeUrlValidator urlValidator;

    public CompanyDiscoveryService(CompanyResolver resolver,
                                   SourceDiscoveryService sourceDiscovery,
                                   SafeUrlValidator urlValidator) {
        this.resolver = resolver;
        this.sourceDiscovery = sourceDiscovery;
        this.urlValidator = urlValidator;
    }

    /**
     * Step one: which domain is each of these companies?
     *
     * <p>Read-only as far as CareerFlux's own data goes. It reaches out to
     * candidate domains to confirm they identify as the company, which is the
     * verification that stops an unchecked guess ever being offered, but it
     * writes nothing and contacts no job board.
     */
    public CompanyResolutionReport resolve(List<String> companyNames) {
        List<String> names = distinctNames(companyNames);
        List<Resolution> resolutions = new ArrayList<>();
        for (String name : names) {
            resolutions.add(resolver.resolve(name));
        }
        log.info("Resolved {} company names: {} resolved, {} ambiguous, {} not found",
                names.size(),
                countOf(resolutions, Outcome.RESOLVED),
                countOf(resolutions, Outcome.AMBIGUOUS),
                countOf(resolutions, Outcome.NOT_FOUND));
        return new CompanyResolutionReport(resolutions);
    }

    /**
     * Step two: probe the domains an operator confirmed, and register what has a
     * readable board.
     *
     * <p>Delegates wholesale to {@link SourceDiscoveryService}. Nothing about how
     * a source is found, registered or gated changes because the domain arrived
     * from a company name rather than being typed — that would be two discovery
     * paths with two sets of rules, and the second one would drift.
     *
     * @param confirmedDomains domains a person chose from a previous resolve
     * @param companyNames     optional display names, positionally matched
     */
    public SourceDiscoveryService.DiscoveryRun discoverConfirmed(List<String> confirmedDomains,
                                                                 List<String> companyNames,
                                                                 String actor) {
        List<String> safe = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (String domain : confirmedDomains == null ? List.<String>of() : confirmedDomains) {
            String normalized = AtsBoardProbe.normalizeDomain(domain == null ? "" : domain);
            // Confirmation is not authorisation to fetch anything: the domain
            // still has to be a public address, whoever typed it. A confirmed
            // domain arrives from a browser and is no more trusted than any
            // other operator input.
            if (!normalized.isEmpty() && urlValidator.isSafe("https://" + normalized + "/")) {
                safe.add(normalized);
            } else {
                refused.add(domain);
            }
        }

        if (!refused.isEmpty()) {
            log.warn("Refused {} confirmed domain(s) that are not publicly routable", refused.size());
        }
        if (safe.isEmpty()) {
            return new SourceDiscoveryService.DiscoveryRun(0, List.of(), List.of(), refused);
        }
        return sourceDiscovery.discover(safe, companyNames, actor);
    }

    /** Trims, drops blanks and duplicates, and caps the request. */
    private List<String> distinctNames(List<String> companyNames) {
        Set<String> unique = new LinkedHashSet<>();
        for (String name : companyNames == null ? List.<String>of() : companyNames) {
            if (TextUtils.hasText(name)) {
                unique.add(name.strip());
            }
            if (unique.size() >= MAX_NAMES_PER_REQUEST) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    private static long countOf(List<Resolution> resolutions, Outcome outcome) {
        return resolutions.stream().filter(r -> r.outcome() == outcome).count();
    }

    /**
     * What resolution produced, grouped the way the API reports it.
     *
     * <p>The three groups are not decoration: they are three different next
     * steps. A resolved name is ready to confirm, an ambiguous one needs a person
     * to choose, and one that was not found needs a domain typed in.
     */
    public record CompanyResolutionReport(List<Resolution> all) {

        public List<Resolution> resolved() {
            return withOutcome(Outcome.RESOLVED);
        }

        public List<Resolution> ambiguous() {
            return withOutcome(Outcome.AMBIGUOUS);
        }

        public List<Resolution> notFound() {
            return withOutcome(Outcome.NOT_FOUND);
        }

        private List<Resolution> withOutcome(Outcome outcome) {
            return all.stream().filter(r -> r.outcome() == outcome).toList();
        }

        /** Every domain a resolve turned up, for a caller that wants them flat. */
        public List<String> allCandidateDomains() {
            return all.stream()
                    .flatMap(r -> r.candidates().stream())
                    .map(CompanyResolver.Candidate::domain)
                    .distinct()
                    .toList();
        }

        public Optional<Resolution> forName(String companyName) {
            return all.stream()
                    .filter(r -> r.companyName().equalsIgnoreCase(companyName))
                    .findFirst();
        }
    }
}
