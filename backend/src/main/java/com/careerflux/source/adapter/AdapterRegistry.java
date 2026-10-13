package com.careerflux.source.adapter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Holds every registered adapter, keyed by its metadata key.
 *
 * <p>Adding support for a new source is a matter of writing one
 * {@link JobSourceAdapter} bean; nothing else in the system needs to change,
 * which is the whole point of the adapter pattern here. The registry starts
 * deliberately small — a handful of documented public endpoints — rather than
 * pretending to a scraper framework that does not exist.
 */
@Component
public class AdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(AdapterRegistry.class);

    private final Map<String, JobSourceAdapter> adaptersByKey = new LinkedHashMap<>();

    /** Adapters an admin defined as data; consulted after every built-in one. */
    private final ConfiguredAdapters configured;

    public AdapterRegistry(List<JobSourceAdapter> adapters) {
        this(adapters, ConfiguredAdapters.NONE);
    }

    @Autowired
    public AdapterRegistry(List<JobSourceAdapter> adapters, ConfiguredAdapters configured) {
        this.configured = configured;
        for (JobSourceAdapter adapter : adapters) {
            String key = adapter.getMetadata().key();
            JobSourceAdapter previous = adaptersByKey.put(key, adapter);
            if (previous != null) {
                throw new IllegalStateException("Two adapters registered under the key '" + key + "'");
            }
        }
        log.info("Registered {} source adapters: {}", adaptersByKey.size(), adaptersByKey.keySet());
    }

    public Optional<JobSourceAdapter> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        JobSourceAdapter builtIn = adaptersByKey.get(key);
        return builtIn != null ? Optional.of(builtIn) : configured.find(key);
    }

    public boolean has(String key) {
        return find(key).isPresent();
    }

    /**
     * Picks the first adapter that claims it can handle this configuration.
     * Built-in adapters are asked first; a configured one only ever claims a
     * source registered under its own key.
     */
    public Optional<JobSourceAdapter> resolveFor(SourceConfiguration configuration) {
        return Stream.concat(adaptersByKey.values().stream(), configured.enabled().stream())
                .filter(adapter -> adapter.supports(configuration))
                .findFirst();
    }

    public List<SourceMetadata> describeAll() {
        return Stream.concat(adaptersByKey.values().stream(), configured.enabled().stream())
                .map(JobSourceAdapter::getMetadata)
                .toList();
    }
}
