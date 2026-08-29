package com.careerflux.ingestion.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.source.adapter.RawJobPosting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Normalization feeds deduplication, so drift here silently splits jobs that
 * used to merge. These tests pin the behaviour that matters.
 */
class JobNormalizerTest {

    private final JobNormalizer normalizer = new JobNormalizer();

    @Nested
    @DisplayName("title normalization")
    class Titles {

        @Test
        @DisplayName("strips the decorations boards bolt onto a title")
        void stripsDecorations() {
            assertThat(normalizer.normalizeTitle("Senior Java Developer (Remote)"))
                    .isEqualTo("senior java developer");
            assertThat(normalizer.normalizeTitle("Backend Engineer - Urgent"))
                    .isEqualTo("backend engineer");
            assertThat(normalizer.normalizeTitle("Java Developer [Full-Time]"))
                    .isEqualTo("java developer");
        }

        @Test
        @DisplayName("cosmetic variants collapse to the same normalized title")
        void variantsCollapse() {
            String expected = normalizer.normalizeTitle("Backend Engineer");
            assertThat(normalizer.normalizeTitle("Backend Engineer (Hybrid)")).isEqualTo(expected);
            assertThat(normalizer.normalizeTitle("backend  engineer")).isEqualTo(expected);
        }

        @Test
        @DisplayName("genuinely different roles stay different")
        void distinctRolesStayDistinct() {
            assertThat(normalizer.normalizeTitle("Backend Engineer"))
                    .isNotEqualTo(normalizer.normalizeTitle("Frontend Engineer"));
            assertThat(normalizer.normalizeTitle("Senior Backend Engineer"))
                    .isNotEqualTo(normalizer.normalizeTitle("Backend Engineer"));
        }

        @Test
        @DisplayName("stripping everything falls back to the original rather than returning empty")
        void neverReturnsEmpty() {
            assertThat(normalizer.normalizeTitle("(Remote)")).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("location parsing")
    class Locations {

        @Test
        @DisplayName("splits city, region and country")
        void splitsParts() {
            var location = normalizer.parseLocation("Hyderabad, Telangana, India");
            assertThat(location.city()).isEqualTo("Hyderabad");
            assertThat(location.region()).isEqualTo("Telangana");
            assertThat(location.country()).isEqualTo("India");
        }

        @Test
        @DisplayName("a posting open in several offices keeps the first as the location")
        void multiOfficePostingsUseTheFirstOffice() {
            // This mangled the corpus: splitting the whole string on commas made
            // the country "NY - United States" and broke every location filter.
            var location = normalizer.parseLocation(
                    "San Francisco, CA • New York, NY • United States");
            assertThat(location.city()).isEqualTo("San Francisco");
            assertThat(location.region()).isEqualTo("CA");
            assertThat(location.country()).isEqualTo("United States");
        }

        @Test
        @DisplayName("a two-letter state code is a region, never a country")
        void stateCodesAreNotCountries() {
            var location = normalizer.parseLocation("New York, NY");
            assertThat(location.city()).isEqualTo("New York");
            assertThat(location.region()).isEqualTo("NY");
            assertThat(location.country()).isNull();
        }

        @Test
        @DisplayName("Indian multi-city postings keep the country")
        void indianMultiCityPostings() {
            var location = normalizer.parseLocation("Bengaluru, India • Hyderabad, India");
            assertThat(location.city()).isEqualTo("Bengaluru");
            assertThat(location.country()).isEqualTo("India");
        }

        @Test
        @DisplayName("a pipe separates offices just as a bullet does")
        void pipeSeparatedOffices() {
            var location = normalizer.parseLocation("Pune, Maharashtra, India | Chennai, Tamil Nadu, India");
            assertThat(location.city()).isEqualTo("Pune");
            assertThat(location.country()).isEqualTo("India");
        }

        @Test
        @DisplayName("a bare remote marker is a work mode, not a city")
        void remoteIsNotACity() {
            assertThat(normalizer.parseLocation("Remote").city()).isNull();
            assertThat(normalizer.parseLocation("Remote, India").city()).isNull();
            assertThat(normalizer.parseLocation("Remote, India").country()).isEqualTo("India");
        }

        @Test
        @DisplayName("an absent location produces nulls, not empty strings")
        void handlesMissing() {
            var location = normalizer.parseLocation(null);
            assertThat(location.raw()).isNull();
            assertThat(location.city()).isNull();
        }
    }

    @Nested
    @DisplayName("work mode")
    class WorkModes {

        @Test
        @DisplayName("a value the source stated beats anything inferred from prose")
        void statedWins() {
            assertThat(normalizer.resolveWorkMode("Remote", "we love our office", location(null), null))
                    .isEqualTo(WorkMode.REMOTE);
            assertThat(normalizer.resolveWorkMode("Onsite", "fully remote team", location(null), null))
                    .isEqualTo(WorkMode.ONSITE);
        }

        @Test
        @DisplayName("a work mode in the title is an explicit statement, not prose")
        void titleMarkerCounts() {
            assertThat(normalizer.resolveWorkMode(null, "", location("Bengaluru"), "Java Developer (Remote)"))
                    .isEqualTo(WorkMode.REMOTE);
            assertThat(normalizer.resolveWorkMode(null, "", location("Bengaluru"), "Java Developer - Hybrid"))
                    .isEqualTo(WorkMode.HYBRID);
        }

        @Test
        @DisplayName("an explicit field still outranks the title")
        void statedFieldBeatsTitle() {
            assertThat(normalizer.resolveWorkMode("Onsite", "", location(null), "Developer (Remote)"))
                    .isEqualTo(WorkMode.ONSITE);
        }

        @Test
        @DisplayName("hybrid is detected from the location text")
        void hybridFromLocation() {
            assertThat(normalizer.resolveWorkMode(null, "", location("Hyderabad (Hybrid)"), null))
                    .isEqualTo(WorkMode.HYBRID);
        }

        @Test
        @DisplayName("the location field outranks a passing mention in the description")
        void locationBeatsDescriptionProse() {
            // A real posting located "Remote - United States" whose company
            // boilerplate mentions hybrid working is a remote job.
            String prose = "we support hybrid and flexible arrangements across the company";
            assertThat(normalizer.resolveWorkMode(null, prose, location("Remote - United States"), "Visual Designer"))
                    .isEqualTo(WorkMode.REMOTE);
        }

        @Test
        @DisplayName("only unambiguous phrasing is trusted from the description")
        void descriptionNeedsToBeExplicit() {
            assertThat(normalizer.resolveWorkMode(null, "we occasionally mention remote in passing", location("Pune"), "Engineer"))
                    .isEqualTo(WorkMode.UNSPECIFIED);
            assertThat(normalizer.resolveWorkMode(null, "this is a fully remote role", location(null), "Engineer"))
                    .isEqualTo(WorkMode.REMOTE);
            assertThat(normalizer.resolveWorkMode(null, "this is a hybrid role", location(null), "Engineer"))
                    .isEqualTo(WorkMode.HYBRID);
        }

        @Test
        @DisplayName("nothing stated stays unspecified rather than guessing onsite")
        void unknownStaysUnknown() {
            assertThat(normalizer.resolveWorkMode(null, "a normal job description", location("Pune"), "Backend Engineer"))
                    .isEqualTo(WorkMode.UNSPECIFIED);
        }

        private JobNormalizer.Location location(String raw) {
            return new JobNormalizer.Location(raw, null, null, null);
        }
    }

    @Nested
    @DisplayName("seniority inference")
    class Seniorities {

        @Test
        @DisplayName("reads the level out of the title")
        void fromTitle() {
            assertThat(normalizer.inferSeniority("Senior Backend Engineer", "")).isEqualTo(Seniority.SENIOR);
            assertThat(normalizer.inferSeniority("Junior Developer", "")).isEqualTo(Seniority.JUNIOR);
            assertThat(normalizer.inferSeniority("Principal Engineer", "")).isEqualTo(Seniority.PRINCIPAL);
            assertThat(normalizer.inferSeniority("Software Engineer Intern", "")).isEqualTo(Seniority.INTERN);
            assertThat(normalizer.inferSeniority("Engineering Manager", "")).isEqualTo(Seniority.LEAD);
        }

        @Test
        @DisplayName("does not mistake 'internal' for 'intern'")
        void internalIsNotAnIntern() {
            assertThat(normalizer.inferSeniority("Internal Tools Engineer", ""))
                    .isNotEqualTo(Seniority.INTERN);
        }

        @Test
        @DisplayName("an unlabelled title stays unspecified")
        void unspecifiedWhenSilent() {
            assertThat(normalizer.inferSeniority("Backend Engineer", "build things"))
                    .isEqualTo(Seniority.UNSPECIFIED);
        }
    }

    @Nested
    @DisplayName("experience parsing")
    class Experience {

        @Test
        @DisplayName("reads an explicit range")
        void readsRange() {
            var range = normalizer.parseExperience("We need 3-5 years of experience with Java.");
            assertThat(range.min()).isEqualByComparingTo(BigDecimal.valueOf(3.0));
            assertThat(range.max()).isEqualByComparingTo(BigDecimal.valueOf(5.0));
        }

        @Test
        @DisplayName("reads an open-ended minimum")
        void readsMinimum() {
            assertThat(normalizer.parseExperience("5+ years experience required").min())
                    .isEqualByComparingTo(BigDecimal.valueOf(5.0));
            assertThat(normalizer.parseExperience("At least 2 years of backend work").min())
                    .isEqualByComparingTo(BigDecimal.valueOf(2.0));
        }

        @Test
        @DisplayName("silence produces nulls rather than a default")
        void nothingStated() {
            var range = normalizer.parseExperience("A great opportunity for a developer.");
            assertThat(range.min()).isNull();
            assertThat(range.max()).isNull();
        }

        @Test
        @DisplayName("an implausible figure is discarded")
        void rejectsNonsense() {
            assertThat(normalizer.parseExperience("99 years of experience").min()).isNull();
        }
    }

    @Nested
    @DisplayName("salary parsing")
    class Salary {

        @Test
        @DisplayName("reads a currency-marked range")
        void readsRange() {
            var salary = normalizer.parseSalary("Compensation: $120,000 - $150,000 per year");
            assertThat(salary.min()).isEqualByComparingTo(BigDecimal.valueOf(120000));
            assertThat(salary.max()).isEqualByComparingTo(BigDecimal.valueOf(150000));
            assertThat(salary.currency()).isEqualTo("USD");
            assertThat(salary.period()).isEqualTo("ANNUAL");
        }

        @Test
        @DisplayName("expands the k shorthand")
        void expandsThousands() {
            var salary = normalizer.parseSalary("$90k - $120k");
            assertThat(salary.min()).isEqualByComparingTo(BigDecimal.valueOf(90000));
            assertThat(salary.max()).isEqualByComparingTo(BigDecimal.valueOf(120000));
        }

        @Test
        @DisplayName("an hourly rate is labelled as such")
        void hourly() {
            assertThat(normalizer.parseSalary("$50 - $70 per hour").period()).isEqualTo("HOURLY");
        }

        @Test
        @DisplayName("a salary with no currency marker is left null rather than guessed")
        void refusesToGuess() {
            assertThat(normalizer.parseSalary("competitive salary, 10 - 20 lakhs").min()).isNull();
            assertThat(normalizer.parseSalary("Great pay!").min()).isNull();
        }
    }

    @Test
    @DisplayName("a full posting normalizes end to end")
    void normalizesWholePosting() {
        RawJobPosting raw = RawJobPosting.builder("EXT-1")
                .title("Senior Java Developer (Remote)")
                .companyName("Acme")
                .locationText("Bengaluru, Karnataka, India")
                .employmentTypeText("Full-time")
                .descriptionHtml("<p>We need <b>5-8 years</b> of Java experience.</p><ul><li>Spring Boot</li></ul>")
                .applyUrl("https://acme.invalid/apply/1")
                .build();

        NormalizedJob job = normalizer.normalize(raw, "Fallback Co");

        assertThat(job.title()).isEqualTo("Senior Java Developer (Remote)");
        assertThat(job.normalizedTitle()).isEqualTo("senior java developer");
        assertThat(job.companyName()).isEqualTo("Acme");
        assertThat(job.city()).isEqualTo("Bengaluru");
        assertThat(job.country()).isEqualTo("India");
        assertThat(job.employmentType()).isEqualTo(EmploymentType.FULL_TIME);
        assertThat(job.seniority()).isEqualTo(Seniority.SENIOR);
        assertThat(job.workMode()).isEqualTo(WorkMode.REMOTE);
        assertThat(job.minExperienceYears()).isEqualByComparingTo(BigDecimal.valueOf(5.0));
        // The HTML is stripped so downstream stages see readable text.
        assertThat(job.description()).doesNotContain("<p>").contains("Spring Boot");
        assertThat(job.contentHash()).isNotBlank();
    }

    @Test
    @DisplayName("the content hash changes only when the content does")
    void contentHashIsStable() {
        RawJobPosting first = RawJobPosting.builder("EXT-1")
                .title("Backend Engineer").locationText("Pune").descriptionHtml("<p>Java</p>").build();
        RawJobPosting same = RawJobPosting.builder("EXT-1")
                .title("Backend Engineer").locationText("Pune").descriptionHtml("<p>Java</p>").build();
        RawJobPosting edited = RawJobPosting.builder("EXT-1")
                .title("Backend Engineer").locationText("Pune").descriptionHtml("<p>Java and Kafka</p>").build();

        assertThat(normalizer.normalize(first, "Acme").contentHash())
                .isEqualTo(normalizer.normalize(same, "Acme").contentHash());
        assertThat(normalizer.normalize(first, "Acme").contentHash())
                .isNotEqualTo(normalizer.normalize(edited, "Acme").contentHash());
    }
}
