package com.careerflux.institution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careerflux.common.error.BadRequestException;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.service.EmailDomains;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What an operator may type, and what gets stored.
 *
 * <p>The stakes are quiet rather than loud. A malformed domain does not throw
 * anything at runtime — it simply never matches, so every student from that
 * college is refused at registration and told to ask their placement office,
 * while the row looks perfectly reasonable to anybody reading the table.
 */
class EmailDomainsTest {

    @Nested
    @DisplayName("accepted")
    class Accepted {

        @ParameterizedTest
        @ValueSource(strings = {
                "northgate.edu",
                "NORTHGATE.EDU",
                "  northgate.edu  ",
                "@northgate.edu",
                "students.osmania.ac.in",
                "iiit-h.ac.in"
        })
        @DisplayName("a bare domain, however it was typed")
        void normalisesToABareLowercaseDomain(String input) {
            assertThat(EmailDomains.normalize(input))
                    .isEqualTo(input.strip().toLowerCase(java.util.Locale.ROOT).replace("@", ""));
        }

        @Test
        @DisplayName("several domains, sorted and de-duplicated so the order typed does not matter")
        void canonicalisesAList() {
            // Sorted because V12 indexes this string. Two operators claiming the
            // same pair in different orders must produce one value, or the index
            // sees two different claims and lets both through.
            String one = EmailDomains.normalize("students.northgate.edu, NORTHGATE.edu");
            String other = EmailDomains.normalize("northgate.edu,students.northgate.edu");

            assertThat(one).isEqualTo("northgate.edu,students.northgate.edu");
            assertThat(other).isEqualTo(one);
        }

        @Test
        @DisplayName("a repeated domain is stored once")
        void deduplicates() {
            assertThat(EmailDomains.normalize("northgate.edu, northgate.edu , NORTHGATE.EDU"))
                    .isEqualTo("northgate.edu");
        }

        @Test
        @DisplayName("nothing claimed is null, not an empty string")
        void emptyClaimIsNull() {
            // A college onboarding with a registration code claims no domain.
            // Null keeps it out of the unique index, so several such colleges
            // can coexist; an empty string would collide on the second one.
            assertThat(EmailDomains.normalize(null)).isNull();
            assertThat(EmailDomains.normalize("   ")).isNull();
            assertThat(EmailDomains.normalize(" , , ")).isNull();
        }
    }

    @Nested
    @DisplayName("refused")
    class Refused {

        @ParameterizedTest
        @ValueSource(strings = {
                "https://northgate.edu",
                "http://northgate.edu",
                "northgate.edu/students",
                "northgate.edu/",
                "north gate.edu",
                "someone@northgate.edu",
                "northgate",
                ".northgate.edu",
                "northgate.edu.",
                "-northgate.edu",
                "northgate.e"
        })
        @DisplayName("anything that is not a bare domain")
        void rejectsEverythingElse(String input) {
            assertThatThrownBy(() -> EmailDomains.normalize(input))
                    .describedAs("input %s", input)
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("with a message that shows the shape expected")
        void messagesAreActionable() {
            assertThatThrownBy(() -> EmailDomains.normalize("https://northgate.edu"))
                    .hasMessageContaining("northgate.edu");
            assertThatThrownBy(() -> EmailDomains.normalize("someone@northgate.edu"))
                    .hasMessageContaining("not a full address");
        }

        @Test
        @DisplayName("one bad entry refuses the whole list rather than silently dropping it")
        void oneBadEntryFailsTheList() {
            // Storing the good half would leave a college half-claimed, and the
            // operator believing both domains worked.
            assertThatThrownBy(() -> EmailDomains.normalize("northgate.edu,https://other.edu"))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    @DisplayName("what is stored is what matching expects")
    class RoundTrip {

        @Test
        @DisplayName("a normalised claim actually matches an address at that domain")
        void normalisedClaimMatches() {
            // The point of normalising at all. Institution.acceptsEmail compares
            // against a bare lowercase host, so this is the assertion that ties
            // the two together.
            Institution institution = new Institution();
            institution.setEmailDomains(EmailDomains.normalize("@Northgate.EDU"));

            assertThat(institution.acceptsEmail("student@northgate.edu")).isTrue();
            assertThat(institution.acceptsEmail("STUDENT@NORTHGATE.EDU")).isTrue();
            assertThat(institution.acceptsEmail("student@cse.northgate.edu")).isTrue();
            assertThat(institution.acceptsEmail("student@evilnorthgate.edu")).isFalse();
            assertThat(institution.acceptsEmail("student@other.edu")).isFalse();
        }
    }
}
