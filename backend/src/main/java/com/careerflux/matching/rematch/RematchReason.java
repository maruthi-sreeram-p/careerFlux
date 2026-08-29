package com.careerflux.matching.rematch;

/** Why a candidate needs rescoring. Recorded so a queue backlog can be explained. */
public enum RematchReason {

    /** The candidate changed their skills, preferences or experience. */
    PROFILE_CHANGED,

    /** The scoring rules themselves changed, so every stored score is stale. */
    SCORER_VERSION_CHANGED,

    /** Jobs were re-enriched, so the inputs to scoring changed. */
    JOBS_REENRICHED,

    /** Somebody asked for it explicitly. */
    MANUAL
}
