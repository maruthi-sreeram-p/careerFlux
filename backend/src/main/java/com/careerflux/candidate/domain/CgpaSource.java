package com.careerflux.candidate.domain;

/**
 * Who put a CGPA on a student's record, which is what decides whether it counts.
 *
 * <p>"A CGPA exists" and "a CGPA is verified" are different claims, and only the
 * second may be compared against a company's stated minimum. A student writing
 * their own figure onto their profile is useful to them and is not an
 * institutional record; treating it as one would let a student decide their own
 * eligibility for a drive.
 */
public enum CgpaSource {

    /** The student entered it. Shown back to them, never used for eligibility. */
    STUDENT,

    /** Placement staff entered it as an institutional record. This is "verified". */
    INSTITUTION;

    /** Whether a value from this source may be evaluated against a requirement. */
    public boolean isVerified() {
        return this == INSTITUTION;
    }
}
