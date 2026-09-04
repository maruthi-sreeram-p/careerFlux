package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.careerflux.candidate.domain.CandidatePreferenceValue;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.CandidateSkill;
import com.careerflux.candidate.domain.OnboardingStage;
import com.careerflux.candidate.domain.PreferenceType;
import com.careerflux.candidate.domain.SkillOrigin;
import com.careerflux.candidate.repository.CandidatePreferenceValueRepository;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.ingestion.domain.IngestionTrigger;
import com.careerflux.ingestion.service.IngestionService;
import com.careerflux.matching.domain.JobMatch;
import com.careerflux.matching.domain.MatchTier;
import com.careerflux.matching.repository.JobMatchRepository;
import com.careerflux.matching.service.MatchingService;
import com.careerflux.notification.domain.NotificationPriority;
import com.careerflux.notification.repository.NotificationRepository;
import com.careerflux.notification.service.NotificationService;
import com.careerflux.skill.SkillResolver;
import com.careerflux.source.adapter.LocalFixtureAdapter;
import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.DiscoveryMethod;
import com.careerflux.source.domain.JobSource;
import com.careerflux.source.domain.RobotsStatus;
import com.careerflux.source.domain.SourceAccessPolicy;
import com.careerflux.source.domain.SourceState;
import com.careerflux.source.domain.SourceType;
import com.careerflux.source.domain.TosStatus;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.support.IngestionTestData;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Matching and notification routing against real ingested jobs.
 *
 * <p>The notification rules from the product spec are the part most likely to
 * be got wrong in a way nobody notices, because the failure mode is either
 * silence or spam. Both are checked here.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MatchingAndNotificationIntegrationTest {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private JobSourceRepository sourceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandidateProfileRepository profileRepository;

    @Autowired
    private CandidateProfileService profileService;

    @Autowired
    private CandidatePreferenceValueRepository preferenceValueRepository;

    @Autowired
    private MatchingService matchingService;

    @Autowired
    private JobMatchRepository matchRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private SkillResolver skillResolver;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions testInstitutions;

    private CandidateProfile candidate;


    /** Kept so the cleanup below can find what this test ingested. */
    private JobSource ingestedSource;

    @Autowired
    private IngestionTestData testData;

    /**
     * Effective only once ingestion commits independently.
     *
     * <p>This class isolates itself by rolling back, which covers everything
     * ingestion writes today because it all joins the test's transaction. When
     * the fetch moves out of that transaction the ingested rows will commit on
     * their own and the rollback will stop reaching them. The cleanup runs in its
     * own transaction, so it removes exactly those and cannot see — or disturb —
     * the rows the rollback still owns.
     */
    @AfterEach
    void removeAnythingIngestionCommittedOnItsOwn() {
        if (ingestedSource != null) {
            testData.deleteSource(ingestedSource.getId());
        }
    }

    @BeforeEach
    void setUp() {
        JobSource source = new JobSource();
        source.setName("Fixture feed");
        source.setBaseUrl("local://test/matching-" + System.nanoTime());
        source.setSourceType(SourceType.LOCAL_FIXTURE);
        source.setAtsProvider(AtsProvider.NONE);
        source.setAdapterKey(LocalFixtureAdapter.KEY);
        source.setExternalIdentifier("sample-employers");
        source.setDiscoveryMethod(DiscoveryMethod.SEED);
        source.setRateLimitPerMinute(600);
        source.setDiscoveredAt(Instant.now());
        source.setState(SourceState.ACTIVE);
        source.setStateChangedAt(Instant.now());

        SourceAccessPolicy policy = new SourceAccessPolicy();
        policy.setRobotsStatus(RobotsStatus.NOT_PUBLISHED);
        policy.setTosStatus(TosStatus.PERMITTED);
        policy.setTosReviewedBy("test");
        policy.setTosReviewedAt(Instant.now());
        policy.setAccessPolicy(AccessPolicyType.PUBLIC_FEED);
        source.setAccessPolicy(policy);
        sourceRepository.saveAndFlush(source);
        this.ingestedSource = source;

        ingestionService.ingest(source, IngestionTrigger.MANUAL);

        User user = new User();
        user.setEmail("matcher-" + System.nanoTime() + "@example.com");
        user.setFullName("Match Tester");
        user.setPasswordHash(passwordEncoder.encode("IntegrationTest123!"));
        user.setInstitution(testInstitutions.example());
        userRepository.saveAndFlush(user);

        candidate = profileService.createForUser(user);
        candidate.setPrimaryRole("Backend Developer");
        candidate.setSeniority(Seniority.JUNIOR);
        candidate.setYearsExperience(BigDecimal.valueOf(2));
        candidate.setLocation("Hyderabad, India");
        candidate.setOnboardingStage(OnboardingStage.COMPLETE);

        for (String name : List.of("Java", "Spring Boot", "PostgreSQL", "REST APIs", "Git", "SQL")) {
            CandidateSkill skill = new CandidateSkill();
            skill.setCandidate(candidate);
            skill.setSkill(skillResolver.resolve(name).orElseThrow());
            skill.setOrigin(SkillOrigin.RESUME);
            candidate.getSkills().add(skill);
        }
        profileRepository.saveAndFlush(candidate);

        preferenceValueRepository.saveAll(List.of(
                CandidatePreferenceValue.of(candidate, PreferenceType.TARGET_ROLE, "Backend Developer", 0),
                CandidatePreferenceValue.of(candidate, PreferenceType.TARGET_ROLE, "Java Developer", 1),
                CandidatePreferenceValue.of(candidate, PreferenceType.LOCATION, "Hyderabad", 0)));
    }

    @Test
    @DisplayName("a rematch scores every open job and returns only what clears the floor")
    void scoresEverythingAndFiltersByThreshold() {
        var run = matchingService.recomputeForCandidate(candidate.getId());

        assertThat(run.jobsScored()).isGreaterThan(0);
        assertThat(matchRepository.findAll()).hasSize(run.jobsScored());
        assertThat(run.matches()).allMatch(match -> match.getTier() != MatchTier.HIDDEN);
        assertThat(run.matches()).allMatch(match -> match.getOverallScore() >= 70);
    }

    @Test
    @DisplayName("results come back best first")
    void ordersByScore() {
        var run = matchingService.recomputeForCandidate(candidate.getId());
        List<Integer> scores = run.matches().stream().map(JobMatch::getOverallScore).toList();
        assertThat(scores).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("a Java backend role beats an unrelated one for this candidate")
    void relevantJobsScoreHigher() {
        matchingService.recomputeForCandidate(candidate.getId());

        int backend = matchRepository.findAll().stream()
                .filter(match -> match.getJob().getTitle().contains("Java"))
                .mapToInt(JobMatch::getOverallScore).max().orElseThrow();
        int unrelated = matchRepository.findAll().stream()
                .filter(match -> match.getJob().getTitle().contains("Frontend"))
                .mapToInt(JobMatch::getOverallScore).max().orElse(0);

        assertThat(backend).isGreaterThan(unrelated);
    }

    @Test
    @DisplayName("every visible match carries its explanation")
    void visibleMatchesAlwaysExplainThemselves() {
        var run = matchingService.recomputeForCandidate(candidate.getId());

        assertThat(run.matches()).isNotEmpty();
        for (JobMatch match : run.matches()) {
            assertThat(match.getComponents()).isNotEmpty();
            assertThat(match.getNarrative()).isNotBlank();
            assertThat(match.getScorerVersion()).isNotBlank();
            // Sub-scores must be present so the number can be taken apart.
            assertThat(match.getSkillScore()).isBetween(0, 100);
            assertThat(match.getRoleScore()).isBetween(0, 100);
        }
    }

    @Test
    @DisplayName("rematching updates rows in place instead of accumulating duplicates")
    void rematchIsIdempotent() {
        matchingService.recomputeForCandidate(candidate.getId());
        long afterFirst = matchRepository.count();

        matchingService.recomputeForCandidate(candidate.getId());

        assertThat(matchRepository.count()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("a candidate with nothing on their profile is not scored at all")
    void unscoreableCandidateIsToldWhy() {
        candidate.getSkills().clear();
        candidate.setPrimaryRole(null);
        profileRepository.saveAndFlush(candidate);
        preferenceValueRepository.deleteByCandidateIdAndValueType(candidate.getId(), PreferenceType.TARGET_ROLE);

        var run = matchingService.recomputeForCandidate(candidate.getId());

        assertThat(run.jobsScored()).isZero();
        assertThat(run.notice()).contains("Add skills or a target role");
    }

    @Test
    @DisplayName("notification priority follows the published thresholds")
    void notificationPriorityBands() {
        assertThat(notificationService.priorityFor(97)).isEqualTo(NotificationPriority.IMMEDIATE);
        assertThat(notificationService.priorityFor(95)).isEqualTo(NotificationPriority.IMMEDIATE);
        assertThat(notificationService.priorityFor(90)).isEqualTo(NotificationPriority.HIGH);
        assertThat(notificationService.priorityFor(85)).isEqualTo(NotificationPriority.HIGH);
        assertThat(notificationService.priorityFor(75)).isEqualTo(NotificationPriority.DIGEST);
        assertThat(notificationService.priorityFor(70)).isEqualTo(NotificationPriority.DIGEST);
        assertThat(notificationService.priorityFor(69)).isEqualTo(NotificationPriority.LOW);
    }

    @Test
    @DisplayName("a candidate is never notified about the same job twice")
    void neverNotifiesTwiceAboutOneJob() {
        var run = matchingService.recomputeForCandidate(candidate.getId());
        JobMatch match = run.matches().get(0);
        User user = candidate.getUser();

        assertThat(notificationService.notifyMatch(user, match, true)).isPresent();
        assertThat(notificationService.notifyMatch(user, match, true)).isEmpty();
        assertThat(notificationRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("a below-threshold match produces no notification at all")
    void hiddenMatchesAreNeverSent() {
        matchingService.recomputeForCandidate(candidate.getId());
        JobMatch hidden = matchRepository.findAll().stream()
                .filter(match -> match.getTier() == MatchTier.HIDDEN)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected at least one hidden match in the fixture"));

        assertThat(notificationService.notifyMatch(candidate.getUser(), hidden, true)).isEmpty();
        assertThat(notificationRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("switching off immediate alerts downgrades them to the digest rather than dropping them")
    void immediateAlertsOffDowngradesRatherThanSilences() {
        var run = matchingService.recomputeForCandidate(candidate.getId());
        JobMatch match = run.matches().get(0);
        match.setOverallScore(97);
        match.setTier(MatchTier.EXCELLENT);

        var notification = notificationService.notifyMatch(candidate.getUser(), match, false);

        assertThat(notification).isPresent();
        assertThat(notification.get().getPriority()).isEqualTo(NotificationPriority.DIGEST);
    }
}
