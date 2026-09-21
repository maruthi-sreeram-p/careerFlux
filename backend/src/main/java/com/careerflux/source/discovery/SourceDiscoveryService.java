package com.careerflux.source.discovery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.careerflux.common.error.ConflictException;
import com.careerflux.source.discovery.AtsBoardProbe.DiscoveredBoard;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.service.SourceRegistryService;
import com.careerflux.source.service.SourceRegistryService.RegistrationRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns a list of company domains into registered job sources.
 *
 * <p>This is the entry point for source discovery: hand it domains, it finds
 * which of them publish a readable board and registers what it finds. Nothing
 * here activates anything. A discovered source lands at DISCOVERED with an empty
 * policy record, and still has to pass robots, terms review and access
 * classification before it can be ingested — the same path a hand-submitted
 * source takes. Discovery decides what <em>exists</em>, never what is allowed.
 *
 * <p>Runs sequentially and deliberately. Each domain costs a handful of outbound
 * requests to third parties who did not ask to be probed, so this is not
 * parallelised and not run on a tight schedule.
 */
@Service
public class SourceDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(SourceDiscoveryService.class);

    private final AtsBoardProbe probe;
    private final SourceRegistryService registryService;

    public SourceDiscoveryService(AtsBoardProbe probe, SourceRegistryService registryService) {
        this.probe = probe;
        this.registryService = registryService;
    }

    /**
     * Discovers boards for the given domains and registers each one found.
     *
     * @param companyNames optional display names, positionally matched to
     *                     {@code domains}; the domain is used when absent
     */
    public DiscoveryRun discover(List<String> domains, List<String> companyNames, String actor) {
        Set<String> unique = new LinkedHashSet<>();
        for (String domain : domains) {
            String normalized = AtsBoardProbe.normalizeDomain(domain);
            if (!normalized.isEmpty()) {
                unique.add(normalized);
            }
        }

        List<DiscoveredSource> registered = new ArrayList<>();
        List<String> noBoard = new ArrayList<>();
        List<String> alreadyKnown = new ArrayList<>();

        int index = 0;
        for (String domain : unique) {
            String displayName = nameFor(domains, companyNames, domain, index++);
            Optional<DiscoveredBoard> found = probe.probe(domain);
            if (found.isEmpty()) {
                noBoard.add(domain);
                continue;
            }
            DiscoveredBoard board = found.get();
            try {
                JobSource source = registryService.register(toRegistration(board, displayName), actor);
                registered.add(new DiscoveredSource(
                        source.getId().toString(), displayName, domain,
                        board.provider().name(), board.boardToken(),
                        board.howFound().name(), board.detail(), board.ingestible()));
            } catch (ConflictException duplicate) {
                // Already in the registry, which is a normal outcome for a
                // rediscovery run rather than something to report as a failure.
                alreadyKnown.add(domain);
            }
        }

        log.info("Discovery over {} domains: {} registered, {} already known, {} with no readable board",
                unique.size(), registered.size(), alreadyKnown.size(), noBoard.size());
        return new DiscoveryRun(unique.size(), registered, alreadyKnown, noBoard);
    }

    /**
     * A board with an adapter is registered at the API that adapter reads. One
     * without is registered at its public board, with no adapter and no source
     * type claimed: it is a record of where the company's jobs are, and it stays
     * at DISCOVERED because classification refuses a source no adapter reads.
     */
    private RegistrationRequest toRegistration(DiscoveredBoard board, String displayName) {
        return new RegistrationRequest(
                displayName,
                board.sourceUrl(),
                board.ingestible() ? SourceType.ATS_PUBLIC_API : SourceType.UNKNOWN,
                board.provider(),
                board.adapterKey(),
                board.boardToken(),
                board.howFound(),
                board.detail(),
                // Discovered sources start conservatively. An operator can raise
                // this once the source has proven healthy.
                20,
                displayName,
                "https://" + board.domain(),
                null);
    }

    private String nameFor(List<String> domains, List<String> names, String domain, int index) {
        if (names != null && index < names.size() && names.get(index) != null
                && !names.get(index).isBlank()) {
            return names.get(index).strip();
        }
        // Fall back to the domain's first label, capitalised: acme.com -> Acme.
        String label = domain.split("\\.")[0];
        return label.isEmpty() ? domain : Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    /** One source discovery created. */
    public record DiscoveredSource(
            String sourceId,
            String name,
            String domain,
            String provider,
            String boardToken,
            String howFound,
            String detail,
            // False for a board recorded without an adapter: nothing will be read from it.
            boolean ingestible) {
    }

    /**
     * The outcome of a discovery run.
     *
     * <p>{@code withoutBoard} is reported rather than swallowed: a company that
     * has no readable board is a real answer about the market, and repeatedly
     * probing the same dead domains is worth noticing.
     */
    public record DiscoveryRun(
            int domainsExamined,
            List<DiscoveredSource> registered,
            List<String> alreadyKnown,
            List<String> withoutBoard) {
    }
}
