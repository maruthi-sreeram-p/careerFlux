package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.engagement.domain.InteractionType;
import com.careerflux.engagement.domain.JobInteraction;
import com.careerflux.engagement.repository.JobInteractionRepository;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.dto.JobDtos.JobPage;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.job.service.JobQueryService;
import com.careerflux.job.service.JobQueryService.JobFilter;
import com.careerflux.matching.domain.JobMatch;
import com.careerflux.matching.domain.MatchTier;
import com.careerflux.matching.repository.JobMatchRepository;
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
 * Filters that narrow a page must narrow the query, not the page.
 *
 * <p>Two discovery filters were applied to the rows a page had already returned
 * rather than to the query that selected them. The match-score floor and the
 * hide-dismissed default both worked that way, and the effect is worse than it
 * sounds. Filtering afterwards cannot reach a row the page never contained, so
 * the first page of "70% and above" came back empty while the header said 1,914
 * roles across 96 pages — the matching jobs were real, committed, and sitting on
 * page 39. A student looking for their best matches was shown nothing at all.
 *
 * <p>The counts were wrong in the same way: {@code totalElements} and
 * {@code totalPages} were computed before either filter ran, so they described a
 * result set the caller could never see.
 *
 * <p>The fixture is built so the qualifying jobs sit deliberately late in the
 * default ordering. Putting them on the first page would let a post-pagination
 * filter pass by accident, which is exactly how this survived until now.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JobDiscoveryFilterPaginationTest {

    private static final int TOTAL_JOBS = 30;
    private static final int PAGE_SIZE = 10;

    @Autowired
    private JobQueryService jobQueryService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandidateProfileRepository profileRepository;

    @Autowired
    private JobMatchRepository matchRepository;

    @Autowired
    private JobInteractionRepository interactionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    private UUID userId;
    private CandidateProfile candidate;
    private final List<Job> jobs = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("discovery-" + System.nanoTime() + "@example.com");
        user.setFullName("Discovery Tester");
        user.setPasswordHash(passwordEncoder.encode("IntegrationTest123!"));
        user.setInstitution(institutions.example());
        userId = userRepository.saveAndFlush(user).getId();

        candidate = new CandidateProfile();
        candidate.setUser(user);
        profileRepository.saveAndFlush(candidate);

        Company company = new Company();
        company.setName("Discovery Employer");
        company.setSlug("discovery-employer-" + System.nanoTime());
        companyRepository.saveAndFlush(company);

        // lastObservedAt descending is the default ordering, so job 0 is newest
        // and lands on page 0. The high scores go on the oldest jobs on purpose.
        Instant now = Instant.now();
        for (int index = 0; index < TOTAL_JOBS; index++) {
            Job job = new Job();
            job.setCompany(company);
            job.setCanonicalKey("discovery-" + System.nanoTime() + "-" + index);
            job.setTitle("Discovery Role " + index);
            job.setNormalizedTitle("discovery role " + index);
            job.setDescription("A role for the discovery filter test.");
            job.setSearchText("discovery role " + index);
            job.setStatus(JobStatus.OPEN);
            job.setWorkMode(WorkMode.REMOTE);
            job.setEmploymentType(EmploymentType.FULL_TIME);
            job.setSeniority(Seniority.ENTRY);
            job.setCity("Bengaluru");
            job.setLocationRaw("Bengaluru, India");
            job.setFirstObservedAt(now.minusSeconds(index * 60L));
            job.setLastObservedAt(now.minusSeconds(index * 60L));
            jobs.add(jobRepository.saveAndFlush(job));
        }
    }

    /** The last three jobs — the ones furthest from page 0 — score above the floor. */
    private void scoreLastThreeJobsHighly() {
        for (int index = 0; index < TOTAL_JOBS; index++) {
            score(jobs.get(index), index >= TOTAL_JOBS - 3 ? 88 : 20);
        }
    }

    private void score(Job job, int overall) {
        JobMatch match = new JobMatch();
        match.setCandidate(candidate);
        match.setJob(job);
        match.setOverallScore(overall);
        match.setTier(overall >= 70 ? MatchTier.STRONG : MatchTier.HIDDEN);
        match.setScorerVersion("test");
        matchRepository.saveAndFlush(match);
    }

    private void dismiss(Job job) {
        JobInteraction interaction = new JobInteraction();
        interaction.setCandidate(candidate);
        interaction.setJob(job);
        interaction.setInteractionType(InteractionType.DISMISSED);
        interactionRepository.saveAndFlush(interaction);
    }

    private JobPage search(JobFilter filter, int page, int size) {
        return jobQueryService.search(userId, filter, page, size, null);
    }

    private JobFilter filter(Integer minMatch, boolean hideDismissed) {
        return new JobFilter(null, null, null, null, null, null, null, null, null, null,
                minMatch, false, hideDismissed);
    }

    // ------------------------------------------------------ the match floor

    @Test
    @DisplayName("the match floor returns the jobs that clear it, wherever they sit in the corpus")
    void matchFloorFindsQualifyingJobsOnTheFirstPage() {
        scoreLastThreeJobsHighly();

        JobPage page = search(filter(70, false), 0, PAGE_SIZE);

        assertThat(page.content())
                .describedAs("three jobs score 88; a post-pagination filter finds none of them "
                        + "because they sit on the last page of the unfiltered query")
                .hasSize(3);
        assertThat(page.content()).allSatisfy(summary ->
                assertThat(summary.match().overall()).isGreaterThanOrEqualTo(70));
    }

    @Test
    @DisplayName("the match floor reports how many jobs actually cleared it")
    void matchFloorReportsHonestCounts() {
        scoreLastThreeJobsHighly();

        JobPage page = search(filter(70, false), 0, PAGE_SIZE);

        assertThat(page.totalElements())
                .describedAs("the header must not advertise the unfiltered corpus")
                .isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("a floor nothing clears is an honest empty result, not an empty page of many")
    void anUnreachableFloorReturnsNothingAndSaysSo() {
        scoreLastThreeJobsHighly();

        JobPage page = search(filter(95, false), 0, PAGE_SIZE);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
    }

    @Test
    @DisplayName("a floor of zero still excludes jobs that were never scored")
    void aZeroFloorStillRequiresAMatch() {
        // Only half the corpus is scored. minMatch=0 has always meant "has a
        // match at or above zero", not "no filter", because an unscored job has
        // no match to compare. Pinned so the fix cannot quietly widen it.
        for (int index = 0; index < TOTAL_JOBS / 2; index++) {
            score(jobs.get(index), 10);
        }

        JobPage page = search(filter(0, false), 0, TOTAL_JOBS);

        assertThat(page.content()).hasSize(TOTAL_JOBS / 2);
        assertThat(page.totalElements()).isEqualTo(TOTAL_JOBS / 2);
    }

    @Test
    @DisplayName("a score exactly at the floor qualifies")
    void theFloorIsInclusive() {
        // "70% and above" is the label on the control, so 70 is in.
        score(jobs.get(TOTAL_JOBS - 1), 70);

        JobPage page = search(filter(70, false), 0, PAGE_SIZE);

        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("the floor reads this candidate's matches, not somebody else's")
    void anotherCandidatesScoresDoNotLeak() {
        // The subquery has to be scoped by candidate. Without that clause every
        // student would see every other student's strong matches — the same
        // rows, so the count would look plausible while being someone else's.
        User other = new User();
        other.setEmail("other-" + System.nanoTime() + "@example.com");
        other.setFullName("Other Candidate");
        other.setPasswordHash(passwordEncoder.encode("IntegrationTest123!"));
        other.setInstitution(institutions.example());
        userRepository.saveAndFlush(other);
        CandidateProfile otherCandidate = new CandidateProfile();
        otherCandidate.setUser(other);
        profileRepository.saveAndFlush(otherCandidate);

        JobMatch theirs = new JobMatch();
        theirs.setCandidate(otherCandidate);
        theirs.setJob(jobs.get(TOTAL_JOBS - 1));
        theirs.setOverallScore(99);
        theirs.setTier(MatchTier.EXCELLENT);
        theirs.setScorerVersion("test");
        matchRepository.saveAndFlush(theirs);

        JobPage page = search(filter(70, false), 0, PAGE_SIZE);

        assertThat(page.content())
                .describedAs("this candidate has no matches of their own")
                .isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    @DisplayName("a user with no candidate profile gets nothing from the match floor")
    void aMatchFloorWithoutAProfileMatchesNothing() {
        // Staff accounts reach this endpoint too and have no profile to score
        // against. Asking for a floor must return nothing rather than the whole
        // corpus, which is what an unguarded subquery would do.
        User staff = new User();
        staff.setEmail("staff-" + System.nanoTime() + "@example.com");
        staff.setFullName("Staff Viewer");
        staff.setPasswordHash(passwordEncoder.encode("IntegrationTest123!"));
        staff.setInstitution(institutions.example());
        UUID staffId = userRepository.saveAndFlush(staff).getId();

        JobPage page = jobQueryService.search(staffId, filter(70, false), 0, PAGE_SIZE, null);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    // -------------------------------------------------------- hide dismissed

    @Test
    @DisplayName("hiding dismissed jobs still fills the page")
    void dismissedJobsDoNotLeaveHolesInThePage() {
        dismiss(jobs.get(0));
        dismiss(jobs.get(1));

        JobPage page = search(filter(null, true), 0, PAGE_SIZE);

        assertThat(page.content())
                .describedAs("two dismissed jobs were removed from the page after it was "
                        + "selected, so the page came back short instead of refilled")
                .hasSize(PAGE_SIZE);
        assertThat(page.totalElements()).isEqualTo(TOTAL_JOBS - 2);
    }

    @Test
    @DisplayName("a dismissed job is absent from every page, not just the one it sat on")
    void dismissedJobsAreAbsentFromEveryPage() {
        Job dismissed = jobs.get(0);
        dismiss(dismissed);

        List<UUID> seen = new java.util.ArrayList<>();
        for (int page = 0; page < 4; page++) {
            search(filter(null, true), page, PAGE_SIZE).content()
                    .forEach(summary -> seen.add(summary.id()));
        }

        assertThat(seen).doesNotContain(dismissed.getId());
        assertThat(seen).hasSize(TOTAL_JOBS - 1);
    }

    @Test
    @DisplayName("dismissed jobs come back when the caller asks for them")
    void dismissedJobsReturnWhenNotHidden() {
        dismiss(jobs.get(0));

        JobPage page = search(filter(null, false), 0, TOTAL_JOBS);

        assertThat(page.content()).hasSize(TOTAL_JOBS);
        assertThat(page.totalElements()).isEqualTo(TOTAL_JOBS);
    }

    // ------------------------------------------------------------ pagination

    @Test
    @DisplayName("paging through a filtered result set returns each job exactly once")
    void filteredPaginationIsStable() {
        scoreLastThreeJobsHighly();

        List<UUID> seen = new java.util.ArrayList<>();
        for (int page = 0; page < 3; page++) {
            search(filter(70, false), page, 2).content().forEach(summary -> seen.add(summary.id()));
        }

        assertThat(seen).hasSize(3);
        assertThat(seen).doesNotHaveDuplicates();
    }

    // ------------------------------------------------------- filter input

    @Test
    @DisplayName("a location with stray spaces around it still matches")
    void locationIgnoresSurroundingWhitespace() {
        // The search box trims what the caller typed, because canonicalize does
        // it on the way in. The location box did not, and the value goes
        // straight into a LIKE pattern — so "Bengaluru " asked for the literal
        // string "bengaluru " with the space, matched nothing, and reported an
        // empty corpus for a city with jobs in it. Pasting a value is enough to
        // hit this.
        JobFilter padded = new JobFilter(null, "  Bengaluru  ", null, null, null, null, null,
                null, null, null, null, false, false);
        JobFilter plain = new JobFilter(null, "Bengaluru", null, null, null, null, null,
                null, null, null, null, false, false);

        assertThat(search(padded, 0, PAGE_SIZE).totalElements())
                .describedAs("stray whitespace must not empty the result set")
                .isEqualTo(search(plain, 0, PAGE_SIZE).totalElements())
                .isPositive();
    }

    @Test
    @DisplayName("a location of only whitespace is treated as no location filter")
    void blankLocationIsNotAFilter() {
        JobFilter blank = new JobFilter(null, "   ", null, null, null, null, null,
                null, null, null, null, false, false);

        assertThat(search(blank, 0, PAGE_SIZE).totalElements()).isEqualTo(TOTAL_JOBS);
    }

    @Test
    @DisplayName("a page size of zero is refused rather than failing the request")
    void aZeroPageSizeDoesNotBlowUp() {
        // PageRequest rejects a size of zero, which surfaced as a 500 from the
        // search endpoint. Negative pages were already clamped; size was not.
        JobPage page = search(filter(null, false), 0, 0);

        assertThat(page.content()).isNotEmpty();
        assertThat(page.size()).isPositive();
    }
}
