package com.careerflux.matching;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.matching.role.ScorableRoles;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.job.domain.Job;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.matching.service.CandidateSnapshot;
import com.careerflux.matching.service.MatchScorer;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Representative match cases scored against the real post-backfill corpus.
 *
 * <p>Read-only. Jobs are chosen from the live database by the traits each case
 * needs, so the scorecards describe what a student would actually be shown
 * rather than what a fixture says. Candidate profiles are constructed here
 * because the corpus has only three onboarded students, which cannot cover
 * freshers, incomplete profiles and hard blockers at once.
 *
 * <p>Disabled by default: it needs the production database and is a validation
 * instrument, not a regression test.
 */
@Disabled("Reads the real corpus; run explicitly for match validation")
@SpringBootTest
@ActiveProfiles("postgres")
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/careerflux",
        "spring.datasource.username=careerflux",
        "spring.datasource.password=careerflux_local_dev"
})
@Transactional(readOnly = true)
class Rules2MatchValidation {

    @Autowired
    private MatchScorer scorer;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private JdbcTemplate jdbc;

    private static final Set<String> FULL_KIT =
            Set.of("java", "spring-boot", "sql", "git", "rest-api", "python", "communication");

    /** A stronger but still believable graduate kit, for the strong-match case. */
    private static final Set<String> STRONG_KIT =
            Set.of("java", "spring-boot", "sql", "git", "rest-api", "python", "communication",
                    "docker", "kubernetes", "postgresql", "collaboration", "mysql", "system-design");

    /** A Bengaluru backend student, the common shape on this platform. */
    private CandidateSnapshot backendStudent(Set<String> skills, BigDecimal years,
                                             Set<String> locations, boolean relocate) {
        return new CandidateSnapshot(UUID.randomUUID(), UUID.randomUUID(), "Ananya Rao",
                "Backend Developer", Seniority.JUNIOR, years, "Bengaluru",
                skills, List.of("Backend Developer", "Software Engineer"),
                locations, Set.of(WorkMode.ONSITE, WorkMode.HYBRID, WorkMode.REMOTE),
                Set.of(EmploymentType.FULL_TIME), relocate, false, false);
    }

    private Job pick(String sql) {
        List<UUID> found = jdbc.query(sql, (rs, n) -> (UUID) rs.getObject(1));
        return found.isEmpty() ? null : jobs.findById(found.get(0)).orElse(null);
    }

    @Test
    @DisplayName("nine representative cases")
    void nineCases() {
        report("1. STRONG MATCH - every required skill held",
                backendStudent(STRONG_KIT, new BigDecimal("3.0"), Set.of("Bengaluru"), true),
                pick("SELECT j.id FROM jobs j WHERE j.status='OPEN'"
                        + " AND j.location_raw ~* '(India|Bengaluru|Bangalore|Hyderabad)'"
                        + " AND (j.title ILIKE '%Backend%' OR j.title ILIKE '%Software Engineer%')"
                        + " AND (SELECT count(*) FROM job_skills js WHERE js.job_id=j.id"
                        + "      AND js.requirement='REQUIRED') >= 2"
                        + " AND NOT EXISTS (SELECT 1 FROM job_skills js JOIN skills s ON s.id=js.skill_id"
                        + "   WHERE js.job_id=j.id AND js.requirement='REQUIRED'"
                        + "   AND s.slug NOT IN ('java','spring-boot','sql','git','rest-api','python',"
                        + "                      'communication','docker','kubernetes','postgresql',"
                        + "                      'collaboration','mysql','system-design'))"
                        + " ORDER BY j.id LIMIT 1"));

        report("2. WRONG ROLE - backend student against a non-engineering posting",
                backendStudent(FULL_KIT, new BigDecimal("3.0"), Set.of("Bengaluru"), true),
                pick("SELECT id FROM jobs WHERE status='OPEN'"
                        + " AND (title ILIKE '%Sales%' OR title ILIKE '%Account Executive%'"
                        + "      OR title ILIKE '%Office Manager%' OR title ILIKE '%Recruiter%')"
                        + " ORDER BY id LIMIT 1"));

        report("3. MISSING REQUIRED SKILL - the one thing the job demands",
                backendStudent(Set.of("java", "sql", "git"), new BigDecimal("3.0"),
                        Set.of("Bengaluru"), true),
                pick("SELECT j.id FROM jobs j WHERE j.status='OPEN'"
                        + " AND EXISTS (SELECT 1 FROM job_skills js JOIN skills s ON s.id=js.skill_id"
                        + "   WHERE js.job_id=j.id AND js.requirement='REQUIRED' AND s.slug='kubernetes')"
                        + " ORDER BY j.id LIMIT 1"));

        report("4. PREFERRED GAP - all required held, preferred ones missing",
                backendStudent(Set.of("java", "communication"), new BigDecimal("3.0"),
                        Set.of("Bengaluru"), true),
                pick("SELECT j.id FROM jobs j WHERE j.status='OPEN'"
                        + " AND NOT EXISTS (SELECT 1 FROM job_skills js JOIN skills s ON s.id=js.skill_id"
                        + "   WHERE js.job_id=j.id AND js.requirement='REQUIRED'"
                        + "   AND s.slug NOT IN ('java','communication'))"
                        + " AND EXISTS (SELECT 1 FROM job_skills js WHERE js.job_id=j.id AND js.requirement='REQUIRED')"
                        + " AND (SELECT count(*) FROM job_skills js WHERE js.job_id=j.id"
                        + "      AND js.requirement='PREFERRED') >= 2"
                        + " ORDER BY j.id LIMIT 1"));

        report("5. EXPERIENCE BLOCKER - posting demands far more than the student has",
                backendStudent(FULL_KIT, new BigDecimal("1.0"), Set.of("Bengaluru"), true),
                pick("SELECT id FROM jobs WHERE status='OPEN' AND min_experience_years >= 8"
                        + " ORDER BY id LIMIT 1"));

        report("6. LOCATION BLOCKER - onsite elsewhere, student will not relocate",
                backendStudent(FULL_KIT, new BigDecimal("3.0"), Set.of("Hyderabad"), false),
                pick("SELECT id FROM jobs WHERE status='OPEN' AND work_mode='ONSITE'"
                        + " AND location_raw IS NOT NULL AND location_raw !~* 'Hyderabad'"
                        + " AND location_raw ~* '(Bengaluru|Bangalore|Pune|Chennai)'"
                        + " ORDER BY id LIMIT 1"));

        report("7. FRESHER - campus candidate, no professional experience",
                new CandidateSnapshot(UUID.randomUUID(), UUID.randomUUID(), "Rohit Verma",
                        "Software Engineer", Seniority.ENTRY, BigDecimal.ZERO, "Hyderabad",
                        Set.of("java", "sql", "git", "communication"),
                        List.of("Software Engineer"), Set.of("Hyderabad", "Bengaluru"),
                        Set.of(WorkMode.ONSITE, WorkMode.HYBRID), Set.of(EmploymentType.FULL_TIME),
                        true, false, false),
                pick("SELECT j.id FROM jobs j WHERE j.status='OPEN'"
                        + " AND j.location_raw ~* '(India|Bengaluru|Bangalore|Hyderabad)'"
                        + " AND EXISTS (SELECT 1 FROM job_skills js WHERE js.job_id=j.id"
                        + "      AND js.requirement='REQUIRED')"
                        + " ORDER BY j.id LIMIT 1"));

        report("8. INCOMPLETE PROFILE - onboarding barely started",
                new CandidateSnapshot(UUID.randomUUID(), UUID.randomUUID(), "Unnamed Student",
                        null, null, null, null, Set.of(), List.of(), Set.of(),
                        Set.of(), Set.of(), false, false, false),
                pick("SELECT j.id FROM jobs j WHERE j.status='OPEN'"
                        + " AND EXISTS (SELECT 1 FROM job_skills js WHERE js.job_id=j.id"
                        + "      AND js.requirement='REQUIRED')"
                        + " ORDER BY j.id LIMIT 1"));

        report("9. SKILL-LESS JOB - nothing the classifier could extract",
                backendStudent(FULL_KIT, new BigDecimal("3.0"), Set.of("Bengaluru"), true),
                pick("SELECT j.id FROM jobs j WHERE j.status='OPEN'"
                        + " AND NOT EXISTS (SELECT 1 FROM job_skills js WHERE js.job_id=j.id)"
                        + " ORDER BY j.id LIMIT 1"));
    }

    private void report(String caseName, CandidateSnapshot candidate, Job job) {
        System.out.println();
        System.out.println("=========================================================");
        System.out.println(caseName);
        if (job == null) {
            System.out.println("  NO JOB IN THE CORPUS MATCHES THIS CASE");
            return;
        }
        System.out.printf("  job       : %s%n", job.getTitle());
        System.out.printf("  company   : %s | location: %s | mode: %s | seniority: %s%n",
                job.getCompany() == null ? "-" : job.getCompany().getName(),
                job.getLocationRaw(), job.getWorkMode(), job.getSeniority());
        System.out.printf("  min exp   : %s%n", job.getMinExperienceYears());
        System.out.printf("  job skills: %s%n", job.getSkills().isEmpty() ? "(none)"
                : job.getSkills().stream()
                        .map(js -> js.getSkill().getCanonicalName() + "/" + js.getRequirement())
                        .sorted().toList());
        System.out.printf("  candidate : %s, %s yrs, skills=%s, locations=%s, relocate=%s%n",
                candidate.primaryRole(), candidate.yearsExperience(),
                candidate.skillSlugs().stream().sorted().toList(),
                candidate.preferredLocations(), candidate.openToRelocation());

        MatchScorer.Scorecard card = scorer.score(candidate, ScorableRoles.of(job));
        System.out.println("  ---------------------------------------------------");
        System.out.printf("  SCORE       : %s%n",
                card.compatibility() == null ? "UNAVAILABLE (not scorable)" : card.compatibility());
        System.out.printf("  ELIGIBILITY : %s%n", card.eligibility());
        System.out.printf("  CONFIDENCE  : %s (%d%% coverage)%s%n",
                card.confidence().level(), card.confidence().coveragePercent(),
                card.confidence().unknown().isEmpty() ? ""
                        : "  unknown=" + card.confidence().unknown());
        System.out.printf("  BLOCKERS    : %s%n", card.blockers().isEmpty() ? "none"
                : card.blockers().stream().map(b -> b.type() + " (" + b.label() + ")").toList());
        System.out.printf("  matched req : %s%n", card.matchedRequiredSkills());
        System.out.printf("  missing req : %s%n", card.missingRequiredSkills());
        System.out.printf("  missing pref: %s%n", card.missingPreferredSkills());
        System.out.print("  dimensions  : ");
        for (Map.Entry<?, ?> entry : card.dimensions().entrySet()) {
            System.out.print(entry.getKey() + "=" + entry.getValue() + "  ");
        }
        System.out.println();
    }
}
