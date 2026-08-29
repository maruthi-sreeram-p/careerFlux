package com.careerflux.ingestion.pipeline;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.careerflux.ingestion.pipeline.SkillRequirementClassifier.Explanation;
import com.careerflux.job.domain.SkillRequirement;
import com.careerflux.skill.Skill;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the skill classifier over a stratified sample of the real corpus and
 * writes an audit report.
 *
 * <p><b>Strictly read-only.</b> Plain JDBC with {@code SELECT} only, and no
 * Spring context, so there is no repository, no entity manager and no path by
 * which this could write to the corpus it is measuring. It classifies in memory
 * and reports; nothing is persisted.
 *
 * <p>Disabled by default because it needs the real database. Run it with:
 *
 * <pre>
 *   mvn test -Dtest=CorpusClassificationAudit -DfailIfNoTests=false -Djunit.jupiter.conditions.deactivate=*
 * </pre>
 *
 * <p>The sample is stratified rather than random. A random draw from this corpus
 * would be dominated by long bullet-heavy postings with requirements headings,
 * which are the cases the classifier already handles; the interesting failures
 * live in the shapes that are rare.
 */
@Disabled("Reads the real corpus; run explicitly for a classification audit")
class CorpusClassificationAudit {

    private static final String URL = System.getProperty("audit.url",
            "jdbc:postgresql://localhost:5432/careerflux");
    private static final String USER = System.getProperty("audit.user", "careerflux");
    private static final String PASSWORD = System.getProperty("audit.password", "careerflux_local_dev");

    private static final Path REPORT = Path.of("target", "classification-audit.txt");

    /** How many postings to draw from each stratum. */
    private static final int PER_STRATUM = 8;

    private final SkillRequirementClassifier classifier = new SkillRequirementClassifier();

    /**
     * The shapes worth sampling separately, each with the SQL that selects it.
     *
     * <p>Ordered so the most structurally reliable comes first and the least
     * comes last, which makes the report readable as a descent from the easy
     * cases into the risky ones.
     */
    private static final Map<String, String> STRATA = new LinkedHashMap<>();

    static {
        STRATA.put("explicit Requirements heading",
                "description ~* '(^|\n)[^\n]{0,50}requirements?[^\n]{0,15}(\n|:)'");
        STRATA.put("explicit Qualifications heading",
                "description ~* '(^|\n)[^\n]{0,50}qualifications?[^\n]{0,15}(\n|:)'");
        STRATA.put("explicit Preferred / Nice-to-have heading",
                "description ~* '(^|\n)[^\n]{0,50}(nice[ -]to[ -]have|preferred qualification|bonus)[^\n]{0,15}(\n|:)'");
        STRATA.put("no requirements heading at all",
                "description !~* '(^|\n)[^\n]{0,60}(requirements?|qualifications?|what you.{0,15}(need|bring))[^\n]{0,20}(\n|:)'");
        STRATA.put("prose-heavy (few bullets)",
                "(length(description) - length(replace(description, chr(8226), ''))) < 5");
        STRATA.put("bullet-heavy (20+ bullets)",
                "(length(description) - length(replace(description, chr(8226), ''))) >= 20");
        STRATA.put("short description",
                "length(description) < 2500");
        STRATA.put("long description",
                "length(description) > 9000");
        STRATA.put("many skills detected",
                "(SELECT count(*) FROM job_skills js WHERE js.job_id = j.id) >= 8");
        STRATA.put("few skills detected",
                "(SELECT count(*) FROM job_skills js WHERE js.job_id = j.id) <= 2");
        STRATA.put("contextual / collaborative language",
                "description ~* '(collaborate with|work with|partner with|alongside) [^\n]{0,40}(team|engineers|developers)'");
        STRATA.put("preference language in prose, no heading",
                "description ~* '(is a plus|nice to have|bonus points|would be a bonus)' "
                        + "AND description !~* '(^|\n)[^\n]{0,50}(nice[ -]to[ -]have|preferred qualification)[^\n]{0,15}(\n|:)'");
    }

    @Test
    @DisplayName("classify a stratified sample of the real corpus and write an audit report")
    void auditCorpus() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             PrintWriter out = new PrintWriter(Files.newBufferedWriter(REPORT, StandardCharsets.UTF_8))) {

            List<Skill> dictionary = loadDictionary(connection);
            out.printf("Skill dictionary: %d canonical skills%n", dictionary.size());
            out.println("Corpus is read but never written.");
            out.println();

            Map<SkillRequirement, Integer> totals = new EnumMap<>(SkillRequirement.class);
            Map<SkillRequirementClassifier.Cue, Integer> cueCounts = new EnumMap<>(SkillRequirementClassifier.Cue.class);
            int sampledJobs = 0;

            for (Map.Entry<String, String> stratum : STRATA.entrySet()) {
                out.println("=".repeat(100));
                out.printf("STRATUM: %s%n", stratum.getKey());
                out.println("=".repeat(100));

                List<Posting> postings = sample(connection, stratum.getValue(), PER_STRATUM);
                out.printf("(%d sampled)%n%n", postings.size());

                for (Posting posting : postings) {
                    sampledJobs++;
                    report(out, posting, dictionary, totals, cueCounts);
                }
            }

            out.println("=".repeat(100));
            out.printf("SAMPLED JOBS: %d%n", sampledJobs);
            out.println("TIER TOTALS");
            for (SkillRequirement tier : SkillRequirement.values()) {
                out.printf("  %-10s %d%n", tier, totals.getOrDefault(tier, 0));
            }
            out.println("CUE TOTALS");
            for (SkillRequirementClassifier.Cue cue : SkillRequirementClassifier.Cue.values()) {
                out.printf("  %-18s %d%n", cue, cueCounts.getOrDefault(cue, 0));
            }
        }
        System.out.println("Audit written to " + REPORT.toAbsolutePath());
    }

    /**
     * Classifies every posting in the corpus in memory and compares the result
     * with what is stored, without writing anything.
     *
     * <p>This is the backfill's dry run: it answers what would change without
     * changing it.
     */
    @Test
    @DisplayName("estimate what re-enrichment would change across the whole corpus")
    void estimateCorpusImpact() throws Exception {
        Path report = Path.of("target", "corpus-impact.txt");
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             PrintWriter out = new PrintWriter(Files.newBufferedWriter(report, StandardCharsets.UTF_8))) {

            List<Skill> dictionary = loadDictionary(connection);
            JobEnricher enricher = new JobEnricher(null, null, null, classifier);

            Map<SkillRequirement, Integer> proposed = new EnumMap<>(SkillRequirement.class);
            int jobs = 0;
            int jobsWithFewerRequired = 0;
            int jobsGainingPreferred = 0;
            int jobsGainingOptional = 0;
            int jobsUnchanged = 0;
            int jobsWithNoSkills = 0;
            int storedRequiredTotal = 0;

            String sql = """
                    SELECT j.id, j.description,
                           (SELECT count(*) FROM job_skills js WHERE js.job_id = j.id) AS stored_skills
                      FROM jobs j WHERE j.description IS NOT NULL ORDER BY j.id
                    """;
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    jobs++;
                    int storedRequired = rows.getInt("stored_skills");
                    storedRequiredTotal += storedRequired;

                    List<String> mentioned = enricher.matchDictionarySkills(
                            rows.getString("description"), dictionary);
                    List<Explanation> explanations =
                            classifier.explain(mentioned, rows.getString("description"));
                    if (explanations.isEmpty()) {
                        jobsWithNoSkills++;
                        continue;
                    }
                    int required = 0;
                    int preferred = 0;
                    int optional = 0;
                    for (Explanation explanation : explanations) {
                        proposed.merge(explanation.tier(), 1, Integer::sum);
                        switch (explanation.tier()) {
                            case REQUIRED -> required++;
                            case PREFERRED -> preferred++;
                            case OPTIONAL -> optional++;
                        }
                    }
                    if (required < storedRequired) {
                        jobsWithFewerRequired++;
                    }
                    if (preferred > 0) {
                        jobsGainingPreferred++;
                    }
                    if (optional > 0) {
                        jobsGainingOptional++;
                    }
                    if (preferred == 0 && optional == 0 && required == storedRequired) {
                        jobsUnchanged++;
                    }
                }
            }

            out.printf("Jobs examined                     %d%n", jobs);
            out.printf("Stored today (all REQUIRED)       %d%n", storedRequiredTotal);
            out.println();
            out.println("rules-2 would produce:");
            for (SkillRequirement tier : SkillRequirement.values()) {
                out.printf("  %-10s %d%n", tier, proposed.getOrDefault(tier, 0));
            }
            out.println();
            out.printf("Jobs with fewer required skills   %d%n", jobsWithFewerRequired);
            out.printf("Jobs gaining preferred skills     %d%n", jobsGainingPreferred);
            out.printf("Jobs gaining optional skills      %d%n", jobsGainingOptional);
            out.printf("Jobs with no meaningful change    %d%n", jobsUnchanged);
            out.printf("Jobs with no skills detected      %d%n", jobsWithNoSkills);
        }
        System.out.println("Impact estimate written to " + report.toAbsolutePath());
    }

    private void report(PrintWriter out, Posting posting, List<Skill> dictionary,
                        Map<SkillRequirement, Integer> totals,
                        Map<SkillRequirementClassifier.Cue, Integer> cueCounts) {
        JobEnricher enricher = new JobEnricher(null, null, null, classifier);
        List<String> mentioned = enricher.matchDictionarySkills(posting.description(), dictionary);
        List<Explanation> explanations = classifier.explain(mentioned, posting.description());

        out.printf("---- %s%n", posting.title());
        out.printf("     id=%s  chars=%d  bullets=%d%n",
                posting.id(), posting.description().length(), posting.bullets());
        if (explanations.isEmpty()) {
            out.println("     (no dictionary skills detected)");
            out.println();
            return;
        }
        for (Explanation explanation : explanations) {
            totals.merge(explanation.tier(), 1, Integer::sum);
            cueCounts.merge(explanation.cue(), 1, Integer::sum);
            out.printf("     %-22s %-10s cue=%-18s %s%n",
                    explanation.skill(), explanation.tier(), explanation.cue(),
                    evidence(explanation, posting));
        }
        out.println();
    }

    /**
     * The words that produced the decision, plus the sentence the skill sits in.
     *
     * <p>The surrounding sentence is what makes a judgement possible: a cue on
     * its own says what the classifier matched, not whether it was right.
     */
    private String evidence(Explanation explanation, Posting posting) {
        String cue = explanation.evidence() == null ? "" : "\"" + explanation.evidence().strip() + "\" ";
        return cue + "| " + sentenceAround(posting.description(), explanation.skill());
    }

    private String sentenceAround(String text, String skill) {
        int at = text.toLowerCase().indexOf(skill.toLowerCase());
        if (at < 0) {
            return "(not located in raw text)";
        }
        int start = Math.max(0, at - 90);
        int end = Math.min(text.length(), at + skill.length() + 90);
        return text.substring(start, end).replaceAll("\\s+", " ").strip();
    }

    private List<Posting> sample(Connection connection, String predicate, int limit) throws Exception {
        // Deterministic ordering so a re-run audits the same postings and two
        // reports can be compared.
        String sql = """
                SELECT j.id, j.title, j.description,
                       (length(j.description) - length(replace(j.description, chr(8226), ''))) AS bullets
                  FROM jobs j
                 WHERE j.description IS NOT NULL AND (%s)
                 ORDER BY j.id
                 LIMIT %d
                """.formatted(predicate, limit);
        List<Posting> postings = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                postings.add(new Posting(rows.getString("id"), rows.getString("title"),
                        rows.getString("description"), rows.getInt("bullets")));
            }
        }
        return postings;
    }

    private List<Skill> loadDictionary(Connection connection) throws Exception {
        List<Skill> dictionary = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT canonical_name, slug FROM skills")) {
            while (rows.next()) {
                Skill skill = new Skill();
                skill.setCanonicalName(rows.getString("canonical_name"));
                skill.setSlug(rows.getString("slug"));
                dictionary.add(skill);
            }
        }
        return dictionary;
    }

    private record Posting(String id, String title, String description, int bullets) {
    }
}
