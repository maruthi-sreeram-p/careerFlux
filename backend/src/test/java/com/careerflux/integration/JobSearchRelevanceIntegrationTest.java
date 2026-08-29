package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.ingestion.pipeline.JobEnricher;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.dto.JobDtos.JobSummary;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.job.service.JobQueryService;
import com.careerflux.job.service.JobQueryService.JobFilter;
import com.careerflux.source.domain.Company;
import com.careerflux.source.repository.CompanyRepository;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Search relevance, against the corpus shapes that made it look broken.
 *
 * <p>Two real complaints are pinned here. Searching a two-word role returned
 * almost nothing, because the words had to be adjacent in the document. And
 * searching one word returned everything containing it in date order, so a
 * communications job called "Senior Director, Analyst Relations" outranked every
 * actual analyst role.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JobSearchRelevanceIntegrationTest {

    @Autowired
    private JobQueryService jobQueryService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    @Autowired
    private JobEnricher enricher;

    private UUID userId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("searcher-" + System.nanoTime() + "@example.com");
        user.setFullName("Searcher");
        user.setPasswordHash(passwordEncoder.encode("IntegrationTest123!"));
        user.setInstitution(institutions.example());
        userId = userRepository.saveAndFlush(user).getId();

        Company company = new Company();
        company.setName("Acme Software");
        company.setSlug("acme-software-" + System.nanoTime());
        companyRepository.saveAndFlush(company);

        // The exact shapes that ranked wrongly in the real corpus.
        job(company, "Data Analyst", "Work with product teams on reporting.");
        job(company, "Data Analyst, Payments", "Payments reporting and dashboards.");
        job(company, "Senior Analyst, Security Risk", "Assess security risk across the estate.");
        job(company, "Senior Director, Analyst Relations",
                "Own relationships with industry analyst firms and press.");
        job(company, "Product Manager, Growth",
                "You will partner closely with a data analyst on experiments.");

        job(company, "Backend Developer", "Java and Spring Boot services.");
        job(company, "Senior Backend Developer", "Own backend services end to end.");
        job(company, "Software Engineer, Backend",
                "Backend systems work. We are looking for a developer who enjoys distributed systems.");
        job(company, "Technical Writer",
                "Document our APIs for the backend developer audience.");
        jobRepository.flush();
    }

    @Test
    @DisplayName("a two-word role finds jobs whose words are not adjacent")
    void multiWordQueriesAreNotPhraseLookups() {
        List<JobSummary> results = search("backend developer");

        // "Software Engineer, Backend" contains both words but never together.
        // The old phrase match excluded it entirely.
        assertThat(titles(results)).contains("Software Engineer, Backend");
        assertThat(results).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("an exact title match comes first")
    void exactTitleWins() {
        assertThat(titles(search("backend developer")).get(0)).isEqualTo("Backend Developer");
    }

    @Test
    @DisplayName("a title match outranks a passing mention in the description")
    void titlesBeatDescriptions() {
        List<String> titles = titles(search("backend developer"));

        // The technical writer job mentions the phrase but is not the role.
        assertThat(titles.indexOf("Technical Writer"))
                .as("a job that only mentions the role in prose must rank below the real ones")
                .isGreaterThan(titles.indexOf("Senior Backend Developer"));
    }

    @Test
    @DisplayName("the role itself outranks a job that merely qualifies the word")
    void analystRelationsDoesNotWin() {
        List<String> titles = titles(search("analyst"));

        assertThat(titles.get(0)).isEqualTo("Data Analyst");
        assertThat(titles.indexOf("Senior Director, Analyst Relations"))
                .as("Analyst Relations is a communications role, not an analyst role")
                .isGreaterThan(titles.indexOf("Data Analyst, Payments"));
    }

    @Test
    @DisplayName("a job that only mentions the word in prose ranks last")
    void proseMentionsSinkToTheBottom() {
        List<String> titles = titles(search("analyst"));

        assertThat(titles.get(titles.size() - 1)).isEqualTo("Product Manager, Growth");
    }

    @Test
    @DisplayName("an explicit sort still wins over relevance")
    void explicitSortIsHonoured() {
        List<String> titles = titles(search("analyst", "title"));

        assertThat(titles).isSorted();
    }

    @Test
    @DisplayName("a wildcard typed into the box cannot widen the search")
    void wildcardsCannotWiden() {
        // Canonicalisation strips anything outside [a-z0-9+#.] before a term is
        // built, so a wildcard never reaches the LIKE pattern. The escaping in
        // the ranking model is a second line of defence rather than the only one.
        assertThat(titles(search("analyst%"))).isEqualTo(titles(search("analyst")));
        assertThat(titles(search("back_end developer")))
                .isEqualTo(titles(search("back end developer")));
    }

    @Test
    @DisplayName("a query of only punctuation is treated as no query, not as a match-everything")
    void punctuationOnlyQueryBrowses() {
        // "%" canonicalises to nothing. Browsing the whole corpus is the honest
        // reading of an empty query; the important part is that it happens
        // because there is no query, not because a wildcard matched every row.
        List<String> everything = titles(search(null));
        assertThat(titles(search("%"))).isEqualTo(everything);
    }

    // ------------------------------------------------------------------

    private List<JobSummary> search(String query) {
        return search(query, null);
    }

    private List<JobSummary> search(String query, String sort) {
        return jobQueryService.search(userId, filter(query), 0, 50, sort).content();
    }

    private static List<String> titles(List<JobSummary> results) {
        return results.stream().map(JobSummary::title).toList();
    }

    private static JobFilter filter(String query) {
        return new JobFilter(query, null, null, null, null, null, null, null, null, null, null, false, false);
    }

    private void job(Company company, String title, String description) {
        Job job = new Job();
        job.setCompany(company);
        job.setTitle(title);
        job.setNormalizedTitle(title.toLowerCase(java.util.Locale.ROOT));
        job.setDescription(description);
        job.setStatus(JobStatus.OPEN);
        job.setWorkMode(WorkMode.UNSPECIFIED);
        job.setEmploymentType(EmploymentType.FULL_TIME);
        job.setSeniority(Seniority.UNSPECIFIED);
        job.setCanonicalKey("acme-" + title.toLowerCase(java.util.Locale.ROOT).replace(' ', '-')
                + "-" + System.nanoTime());
        job.setFirstObservedAt(Instant.now());
        job.setLastObservedAt(Instant.now());
        jobRepository.save(job);
        // The search column is built by the enricher, so the test data goes
        // through the same construction the pipeline uses.
        enricher.refreshSearchText(job);
    }
}
