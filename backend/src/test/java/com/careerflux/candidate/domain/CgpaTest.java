package com.careerflux.candidate.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.careerflux.common.error.BadRequestException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What CareerFlux will accept as a CGPA.
 *
 * <p>The bounds live in one place because a grading scale scattered through the
 * codebase is a bug waiting for the first college that does not use the one
 * assumed. Ten is a default, not a law.
 *
 * <p>Nothing here converts anything. There is no percentage formula and no
 * reading of a free-text grade: a number arrives already numeric, on a stated
 * scale, or it is refused.
 */
class CgpaTest {

    private static final BigDecimal TEN = new BigDecimal("10.00");

    @Nested
    @DisplayName("accepted")
    class Accepted {

        @ParameterizedTest
        @ValueSource(strings = {"0.00", "0.01", "6.99", "7.00", "7.01", "9.99", "10.00", "6.82"})
        @DisplayName("values within the scale, including both boundaries")
        void withinScale(String raw) {
            BigDecimal value = new BigDecimal(raw);
            assertThat(Cgpa.validate(value, TEN)).isEqualByComparingTo(value);
        }

        @Test
        @DisplayName("absent stays absent, and is never turned into a zero")
        void nullIsAllowed() {
            // The distinction the whole feature rests on: no CGPA on file means
            // unknown, and unknown is not failing.
            assertThat(Cgpa.validate(null, TEN)).isNull();
        }

        @Test
        @DisplayName("a college on a different scale is not refused by a constant")
        void otherScales() {
            assertThat(Cgpa.validate(new BigDecimal("3.75"), new BigDecimal("4.00")))
                    .isEqualByComparingTo("3.75");
            assertThat(Cgpa.validate(new BigDecimal("82.50"), new BigDecimal("100")))
                    .isEqualByComparingTo("82.50");
        }
    }

    @Nested
    @DisplayName("refused")
    class Refused {

        @ParameterizedTest
        @ValueSource(strings = {"-0.01", "-1", "10.01", "11", "99"})
        @DisplayName("outside the scale in either direction")
        void outsideScale(String raw) {
            assertThatThrownBy(() -> Cgpa.validate(new BigDecimal(raw), TEN))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("more precision than a grade actually carries")
        void tooPrecise() {
            assertThatThrownBy(() -> Cgpa.validate(new BigDecimal("7.4237"), TEN))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("two decimal places");
        }

        @Test
        @DisplayName("a value valid on one scale can be invalid on another")
        void scaleIsRespected() {
            assertThat(Cgpa.validate(new BigDecimal("8.50"), TEN)).isNotNull();
            assertThatThrownBy(() -> Cgpa.validate(new BigDecimal("8.50"), new BigDecimal("4.00")))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("a nonsense scale is refused before any value is judged against it")
        void badScale() {
            assertThatThrownBy(() -> Cgpa.validate(new BigDecimal("5"), BigDecimal.ZERO))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> Cgpa.validate(new BigDecimal("5"), new BigDecimal("-1")))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> Cgpa.validate(new BigDecimal("5"), new BigDecimal("500")))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("the error says what the bound actually is")
        void messageIsUseful() {
            assertThatThrownBy(() -> Cgpa.validate(new BigDecimal("11.00"), TEN))
                    .hasMessageContaining("10.00")
                    .hasMessageContaining("11.00");
        }
    }

    @Nested
    @DisplayName("verification")
    class Verification {

        @Test
        @DisplayName("only an institution's record counts for eligibility")
        void onlyInstitutionIsVerified() {
            // A student writing their own figure must not be able to answer a
            // question about their own eligibility for a drive.
            assertThat(CgpaSource.INSTITUTION.isVerified()).isTrue();
            assertThat(CgpaSource.STUDENT.isVerified()).isFalse();
        }
    }
}
