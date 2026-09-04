package com.careerflux.candidate.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.careerflux.common.error.BadRequestException;

/**
 * What counts as a usable CGPA.
 *
 * <p>One place, because a grading scale scattered through the codebase is a bug
 * waiting for the first college that does not use the one assumed. The maximum
 * travels with the student's record rather than being a constant here; ten is
 * only the default, and it is the default because the colleges CareerFlux
 * serves use it, not because the code cannot imagine another.
 *
 * <p>Nothing here parses, infers or converts. A percentage is not a CGPA, marks
 * are not a CGPA, and a free-text grade is not a CGPA. A value arrives already
 * numeric or it is refused.
 */
public final class Cgpa {

    /** The Indian ten-point scale, which the colleges on CareerFlux use today. */
    public static final BigDecimal DEFAULT_SCALE = new BigDecimal("10.00");

    /** Guards against a scale that is itself nonsense. */
    private static final BigDecimal MAX_SCALE = new BigDecimal("100");

    private Cgpa() {
    }

    /**
     * Checks a value against the scale it is expressed on.
     *
     * @param value null is allowed and means "not provided" — an absent CGPA is
     *              a real state and is not zero
     * @throws BadRequestException with a message naming the actual bound
     */
    public static BigDecimal validate(BigDecimal value, BigDecimal scale) {
        BigDecimal max = validateScale(scale);
        if (value == null) {
            return null;
        }
        if (value.scale() > 2) {
            throw new BadRequestException(
                    "A CGPA is recorded to two decimal places. Received " + value.toPlainString() + ".");
        }
        if (value.signum() < 0) {
            throw new BadRequestException("A CGPA cannot be negative. Received "
                    + value.toPlainString() + ".");
        }
        if (value.compareTo(max) > 0) {
            throw new BadRequestException("A CGPA cannot exceed the " + max.toPlainString()
                    + " scale this institution uses. Received " + value.toPlainString() + ".");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal validateScale(BigDecimal scale) {
        BigDecimal max = scale == null ? DEFAULT_SCALE : scale;
        if (max.signum() <= 0 || max.compareTo(MAX_SCALE) > 0) {
            throw new BadRequestException("A grading scale must be greater than 0 and at most "
                    + MAX_SCALE.toPlainString() + ". Received " + max.toPlainString() + ".");
        }
        return max;
    }
}
