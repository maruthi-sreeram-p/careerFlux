package com.careerflux.source.adapter;

import com.careerflux.source.domain.SourceHealthStatus;

/** What a health probe observed. Every field is measured, never assumed. */
public record SourceHealthResult(
        SourceHealthStatus status,
        Integer httpStatus,
        Integer latencyMs,
        Integer jobsSeen,
        String message) {

    public static SourceHealthResult healthy(int httpStatus, int latencyMs, int jobsSeen) {
        return new SourceHealthResult(SourceHealthStatus.HEALTHY, httpStatus, latencyMs, jobsSeen,
                "Responded with " + jobsSeen + " postings in " + latencyMs + " ms.");
    }

    public static SourceHealthResult failing(Integer httpStatus, Integer latencyMs, String message) {
        return new SourceHealthResult(SourceHealthStatus.FAILING, httpStatus, latencyMs, null, message);
    }

    public static SourceHealthResult unreachable(String message) {
        return new SourceHealthResult(SourceHealthStatus.UNREACHABLE, null, null, null, message);
    }
}
