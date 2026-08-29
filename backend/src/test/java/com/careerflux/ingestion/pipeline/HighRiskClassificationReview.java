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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.careerflux.ingestion.pipeline.SkillRequirementClassifier.Cue;
import com.careerflux.ingestion.pipeline.SkillRequirementClassifier.Explanation;
import com.careerflux.job.domain.SkillRequirement;
import com.careerflux.skill.Skill;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Finds the classifications most likely to be wrong, rather than listing them all.
 *
 * <p>Classifying the whole corpus produces around 7,600 rows; reading them is not
 * review, it is data entry. This ranks by where the model is structurally weakest
 * and emits a bounded set per category, so a person can look at the cases that
 * would actually change a decision.
 *
 * <p><b>Strictly read-only.</b> Plain JDBC {@code SELECT}, no Spring context, no
 * repository. Classification happens in memory and nothing is persisted.
 *
 * <pre>
 *   mvn test -Dtest=HighRiskClassificationReview -DfailIfNoTests=false \
 *       -Djunit.jupiter.conditions.deactivate=*
 * </pre>
 */
@Disabled("Reads the real corpus; run explicitly for a classification review")
class HighRiskClassificationReview {

    private static final String URL = System.getProperty("audit.url",
            "jdbc:postgresql://localhost:5432/careerflux");
    private static final String USER = System.getProperty("audit.user", "careerflux");
    private static final String PASSWORD = System.getProperty("audit.password", "careerflux_local_dev");

    private static final Path REPORT = Path.of("target", "high-risk-review.txt");

    /** How many examples to show per category. Enough to judge, few enough to read. */
    private static final int PER_CATEGORY = 6;

    /** Skill names short enough to collide with ordinary words. */
    private static final int SHORT_NAME = 3;

    /**
     * The names called out for scrutiny. Not presumed wrong — chosen because they
     * are where edge cases live: common English words, soft skills, and the
     * technologies that appear in company marketing as often as in requirements.
     */
    private static final Set<String> WATCHED = Set.of(
            "go", "r", "c", "communication", "leadership", "collaboration", "mentoring",
            "python", "java", "spring", "spring-boot", "sql", "machine-learning",
            "data-analysis", "aws", "azure", "gcp", "kubernetes", "docker");

    /** Marketing register: language about the company rather than the candidate. */
    private static final java.util.regex.Pattern MARKETING = java.util.regex.Pattern.compile(
            "(?:we are|we're|about (?:us|the company)|our (?:mission|platform|product|customers)"
                    + "|is the (?:leading|only)|trusted by|founded in|headquartered"
                    + "|shaping the future|world.?class|join us)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private final SkillRequirementClassifier classifier = new SkillRequirementClassifier();

    @Test
    @DisplayName("rank the highest-risk rules-2 classifications for human review")
    void review() throws Exception {
        Map<String, List<Finding>> byCategory = new LinkedHashMap<>();
        Map<String, Integer> riskCounts = new LinkedHashMap<>();
        int jobs = 0;
        int classifications = 0;

        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            List<Skill> dictionary = loadDictionary(connection);
            JobEnricher enricher = new JobEnricher(null, null, null, classifier);

            String sql = """
                    SELECT j.id, j.title, j.description,
                           (SELECT count(*) FROM job_skills js WHERE js.job_id = j.id) AS stored_required
                      FROM jobs j WHERE j.description IS NOT NULL ORDER BY j.id
                    """;
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    jobs++;
                    String id = rows.getString("id");
                    String title = rows.getString("title");
                    String text = rows.getString("description");
                    int storedRequired = rows.getInt("stored_required");

                    List<String> mentioned = enricher.matchDictionarySkills(text, dictionary);
                    List<Explanation> explanations = classifier.explain(mentioned, text);
                    classifications += explanations.size();

                    for (Finding finding : examine(id, title, text, explanations, storedRequired)) {
                        byCategory.computeIfAbsent(finding.category(), key -> new ArrayList<>())
                                .add(finding);
                        riskCounts.merge(finding.risk(), 1, Integer::sum);
                    }
                }
            }
        }

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(REPORT, StandardCharsets.UTF_8))) {
            out.printf("Jobs classified in memory: %d%n", jobs);
            out.printf("Classifications produced:  %d%n", classifications);
            out.println("Corpus read, never written.");
            out.println();

            for (Map.Entry<String, List<Finding>> entry : byCategory.entrySet()) {
                List<Finding> findings = entry.getValue();
                findings.sort(Comparator.comparingInt((Finding f) -> riskOrder(f.risk()))
                        .thenComparing(Finding::skill));
                out.println("=".repeat(104));
                out.printf("%s   (%d found, showing %d)%n",
                        entry.getKey(), findings.size(), Math.min(PER_CATEGORY, findings.size()));
                out.println("=".repeat(104));
                for (Finding finding : findings.stream().limit(PER_CATEGORY).toList()) {
                    out.printf("  [%s] %s%n", finding.risk(), finding.title());
                    out.printf("       id=%s%n", finding.jobId());
                    out.printf("       %-20s %-10s cue=%s%n",
                            finding.skill(), finding.tier(), finding.cue());
                    out.printf("       why:      %s%n", finding.why());
                    out.printf("       context:  %s%n", finding.context());
                    out.println();
                }
            }

            out.println("=".repeat(104));
            out.println("CATEGORY TOTALS");
            for (Map.Entry<String, List<Finding>> entry : byCategory.entrySet()) {
                out.printf("  %-64s %d%n", entry.getKey(), entry.getValue().size());
            }
            out.println("RISK DISTRIBUTION");
            for (String risk : List.of("HIGH", "MEDIUM", "LOW")) {
                out.printf("  %-8s %d%n", risk, riskCounts.getOrDefault(risk, 0));
            }
        }
        System.out.println("Review written to " + REPORT.toAbsolutePath());
    }

    /**
     * Dumps the postings that produce no required skill at all, with the lines a
     * requirement cue actually appears on, so each can be judged rather than
     * counted.
     */
    @Test
    @DisplayName("characterise the jobs that produce zero REQUIRED skills")
    void zeroRequiredJobs() throws Exception {
        Path report = Path.of("target", "zero-required.txt");
        java.util.regex.Pattern cueLine = java.util.regex.Pattern.compile(
                "(must have|required|mandatory|minimum|\\d+\\+? years?|proficien|experience (with|in|using|working)"
                        + "|expertise|working knowledge|deep understanding|ability to|comfortable with|well.versed)",
                java.util.regex.Pattern.CASE_INSENSITIVE);

        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             PrintWriter out = new PrintWriter(Files.newBufferedWriter(report, StandardCharsets.UTF_8))) {
            List<Skill> dictionary = loadDictionary(connection);
            JobEnricher enricher = new JobEnricher(null, null, null, classifier);
            int zeroRequired = 0;
            int shown = 0;

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT id, title, description FROM jobs WHERE description IS NOT NULL ORDER BY id")) {
                while (rows.next()) {
                    String text = rows.getString("description");
                    List<String> mentioned = enricher.matchDictionarySkills(text, dictionary);
                    List<Explanation> explanations = classifier.explain(mentioned, text);
                    boolean anyRequired = explanations.stream()
                            .anyMatch(e -> e.tier() == SkillRequirement.REQUIRED);
                    if (anyRequired || explanations.isEmpty()) {
                        continue;
                    }
                    zeroRequired++;
                    if (shown++ >= 25) {
                        continue;
                    }
                    out.printf("---- %s%n     id=%s%n", rows.getString("title"), rows.getString("id"));
                    out.printf("     detected: %s%n", explanations.stream()
                            .map(e -> e.skill() + "=" + e.tier())
                            .toList());
                    out.println("     lines carrying a requirement cue:");
                    int printed = 0;
                    for (String line : text.split("\\R")) {
                        String trimmed = line.strip();
                        if (trimmed.length() > 8 && cueLine.matcher(trimmed).find() && printed++ < 4) {
                            out.printf("       | %s%n",
                                    trimmed.substring(0, Math.min(140, trimmed.length())));
                        }
                    }
                    if (printed == 0) {
                        out.println("       | (no requirement cue anywhere in this posting)");
                    }
                    out.println();
                }
            }
            out.printf("%nJobs producing zero REQUIRED: %d%n", zeroRequired);
        }
        System.out.println("Zero-required analysis written to " + report.toAbsolutePath());
    }

    /** Applies every risk rule to one posting's classifications. */
    private List<Finding> examine(String id, String title, String text,
                                  List<Explanation> explanations, int storedRequired) {
        List<Finding> findings = new ArrayList<>();
        String lowerTitle = title == null ? "" : title.toLowerCase(Locale.ROOT);

        long required = explanations.stream()
                .filter(e -> e.tier() == SkillRequirement.REQUIRED).count();
        long optional = explanations.stream()
                .filter(e -> e.tier() == SkillRequirement.OPTIONAL).count();

        for (Explanation e : explanations) {
            String slug = e.skill().toLowerCase(Locale.ROOT).replace(' ', '-');
            String context = contextFor(text, e);
            boolean watched = WATCHED.contains(slug);

            // A — required on proximity alone, with no heading to back it up.
            if (e.tier() == SkillRequirement.REQUIRED && e.cue() == Cue.PHRASE_REQUIRED) {
                findings.add(new Finding("A. REQUIRED from proximity language, not a requirements heading",
                        watched ? "MEDIUM" : "LOW", id, title, e.skill(), e.tier(), e.cue(),
                        "The cue \"" + e.evidence() + "\" appeared near the mention. No heading confirmed it.",
                        context));
            }

            // B — required with no cue at all, which should not happen.
            if (e.tier() == SkillRequirement.REQUIRED
                    && (e.cue() == Cue.NO_CUE || e.cue() == Cue.NOT_MENTIONED)) {
                findings.add(new Finding("B. REQUIRED with no identifiable cue",
                        "HIGH", id, title, e.skill(), e.tier(), e.cue(),
                        "Classified required without a heading or a phrase to justify it.", context));
            }

            // C — the role is named after the skill, yet it is only optional.
            if (e.tier() == SkillRequirement.OPTIONAL && !slug.isBlank()
                    && lowerTitle.contains(e.skill().toLowerCase(Locale.ROOT))) {
                findings.add(new Finding("C. OPTIONAL although the skill is in the job title",
                        "HIGH", id, title, e.skill(), e.tier(), e.cue(),
                        "The posting is titled after this skill but no requirement language was found.",
                        context));
            }

            // D — preferred on a weak or distant phrase.
            if (e.tier() == SkillRequirement.PREFERRED && e.cue() == Cue.PHRASE_PREFERRED) {
                findings.add(new Finding("D. PREFERRED from ambiguous wording",
                        watched ? "MEDIUM" : "LOW", id, title, e.skill(), e.tier(), e.cue(),
                        "Preference read from \"" + e.evidence() + "\" rather than a heading.", context));
            }

            // E — very short names, which collide with ordinary words.
            if (e.skill().length() <= SHORT_NAME) {
                findings.add(new Finding("E. Skill detected from a very short name",
                        e.tier() == SkillRequirement.REQUIRED ? "HIGH" : "MEDIUM",
                        id, title, e.skill(), e.tier(), e.cue(),
                        "A name this short can match an ordinary word.", context));
            }

            // F — the mention sits in company marketing rather than requirements.
            if (MARKETING.matcher(context).find()) {
                findings.add(new Finding("F. Skill named in company or marketing language",
                        e.tier() == SkillRequirement.REQUIRED ? "HIGH" : "LOW",
                        id, title, e.skill(), e.tier(), e.cue(),
                        "The surrounding text describes the company, not what the candidate needs.",
                        context));
            }
        }

        // G — an implausible number of hard requirements.
        if (required >= 10) {
            findings.add(new Finding("G. Job with an unusually high number of REQUIRED skills",
                    "MEDIUM", id, title, required + " required skills",
                    SkillRequirement.REQUIRED, Cue.NO_CUE,
                    "Few postings genuinely demand this many; a broad heading may be capturing a list.",
                    firstLine(text)));
        }

        // H — nothing required, several mentioned.
        if (required == 0 && optional >= 3) {
            findings.add(new Finding("H. Job with zero REQUIRED but several OPTIONAL skills",
                    "MEDIUM", id, title, optional + " optional, 0 required",
                    SkillRequirement.OPTIONAL, Cue.NO_CUE,
                    "Either the posting genuinely demands nothing, or its requirement language was missed.",
                    firstLine(text)));
        }

        // I — a large swing away from what is stored today.
        if (storedRequired > 0 && required <= storedRequired / 4 && storedRequired >= 8) {
            findings.add(new Finding("I. Large tier change against the stored rules-1 data",
                    "MEDIUM", id, title, storedRequired + " stored -> " + required + " required",
                    SkillRequirement.REQUIRED, Cue.NO_CUE,
                    "rules-1 marked everything required, so a fall is expected; this one is steep.",
                    firstLine(text)));
        }
        return findings;
    }

    /**
     * The text that actually decided the classification.
     *
     * <p>Shows the window around the cue when there was one. An earlier audit
     * displayed the skill's first occurrence instead, which for a skill mentioned
     * several times was often not the deciding one.
     */
    private String contextFor(String text, Explanation explanation) {
        String anchor = explanation.evidence() != null && !explanation.evidence().isBlank()
                ? explanation.evidence()
                : explanation.skill();
        int at = text.toLowerCase(Locale.ROOT).indexOf(anchor.toLowerCase(Locale.ROOT));
        if (at < 0) {
            at = text.toLowerCase(Locale.ROOT).indexOf(explanation.skill().toLowerCase(Locale.ROOT));
        }
        if (at < 0) {
            return "(not locatable in the raw text)";
        }
        int start = Math.max(0, at - 95);
        int end = Math.min(text.length(), at + anchor.length() + 95);
        return text.substring(start, end).replaceAll("\\s+", " ").strip();
    }

    private String firstLine(String text) {
        return text.replaceAll("\\s+", " ").strip().substring(0, Math.min(150, text.length()));
    }

    private int riskOrder(String risk) {
        return switch (risk) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
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

    private record Finding(String category, String risk, String jobId, String title, String skill,
                           SkillRequirement tier, Cue cue, String why, String context) {
    }
}
