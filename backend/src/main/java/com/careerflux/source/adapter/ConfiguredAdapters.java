package com.careerflux.source.adapter;

import java.util.List;
import java.util.Optional;

/**
 * Adapters a portal admin defined as data, looked up when a source needs one.
 *
 * <p>Asked on every lookup rather than cached at startup, so a definition saved
 * or disabled in the console takes effect on the next sync without a restart.
 */
public interface ConfiguredAdapters {

    /** For tests and builds with no definitions: nothing is configured. */
    ConfiguredAdapters NONE = new ConfiguredAdapters() {
        @Override
        public Optional<JobSourceAdapter> find(String key) {
            return Optional.empty();
        }

        @Override
        public List<JobSourceAdapter> enabled() {
            return List.of();
        }
    };

    /** The enabled adapter under this key, if there is one. */
    Optional<JobSourceAdapter> find(String key);

    /** Every enabled adapter. */
    List<JobSourceAdapter> enabled();
}
