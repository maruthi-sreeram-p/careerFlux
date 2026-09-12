package com.careerflux.security.ratelimit;

/**
 * The endpoint groups that carry a request ceiling.
 *
 * <p>Deliberately a closed set. Every limit CareerFlux enforces is one of these
 * six, so the whole policy can be read in one place rather than discovered by
 * grepping for annotations.
 *
 * <p><b>AI is not on this list, on purpose.</b> AI cost is already governed by
 * {@link com.careerflux.ai.quota.AiQuotaService}: a per-student and
 * per-institution daily allowance, enforced with a conditional {@code UPDATE}
 * against a database row. Adding a second ceiling over the same resource would
 * give two answers to one question. {@link #RESUME_UPLOAD} is the one endpoint
 * that appears in both, and it is not a contradiction — see its note.
 */
public enum RateLimitedAction {

    /**
     * Signing in, per client address and login identity.
     *
     * <p>Keyed on something other than an account, because there is no account
     * yet. Password verification is BCrypt at cost 12 — roughly a quarter-second
     * of CPU per attempt — so an unthrottled sign-in endpoint is both a guessing
     * surface and a cheap way to saturate the machine.
     */
    LOGIN,

    /**
     * Signing in, per login identity alone, whatever address the attempt comes
     * from.
     *
     * <p>The address in {@link #LOGIN} can be varied by the caller wherever a
     * forwarded header is believed, so on its own it could not stop guesses
     * against one account spread across many addresses. This can.
     */
    LOGIN_ACCOUNT,

    /**
     * Uploading a resume.
     *
     * <p>Overlaps the AI quota without competing with it, because the two bound
     * different things. The AI quota bounds spend on extraction and is a daily
     * allowance; exhausting it does not fail the upload, it falls back to the
     * heuristic parser. This bounds 8 MB files arriving on disk, which happens
     * whether or not any AI runs. A student replacing their resume ten times in
     * an hour has already replaced it nine times too often.
     */
    RESUME_UPLOAD,

    /**
     * Running candidate discovery for a requirement.
     *
     * <p>The heaviest read in the product: it scores every student in scope on
     * every request. Measured at roughly 600 ms warm against 2,000 students.
     */
    CANDIDATE_DISCOVERY,

    /** Creating a company requirement. */
    REQUIREMENT_CREATE,

    /** Adding a candidate to a shortlist, or taking one off. */
    SHORTLIST_MUTATION
}
