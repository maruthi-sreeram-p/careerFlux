package com.careerflux.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.careerflux.ai.dto.ExtractedResume;
import com.careerflux.common.TextUtils;
import com.careerflux.ai.quota.AiQuotaService;
import com.careerflux.ai.quota.QuotaDecision;
import com.careerflux.common.error.AiUnavailableException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns resume text into a structured profile.
 *
 * <p>The model does the reading when it is available. When it is not — no API
 * key, provider outage, unparseable response — a deterministic extractor takes
 * over: it finds contact details, section headings and known skill terms with
 * regular expressions. The fallback is genuinely weaker, and the result says so,
 * so the onboarding screen can tell the candidate that more manual correction is
 * expected instead of quietly presenting a thin profile as a good one.
 */
@Service
public class ResumeExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ResumeExtractionService.class);

    private static final String SYSTEM_PROMPT = """
            You read resumes and return structured data about the candidate.

            Rules:
            - Only report what the resume actually says. Never invent an employer, a date, a school or a skill.
            - Leave a field null when the resume does not state it. A null is always better than a guess.
            - skills: individual technologies, tools and practices. One per entry, 1-3 words, no sentences.
            - seniority: one of INTERN, ENTRY, JUNIOR, MID, SENIOR, LEAD, PRINCIPAL. Use null if unclear.
            - yearsExperience: total professional experience in years, as a number. Exclude internships
              unless they are the only experience. Use null if it cannot be determined.
            - startDate and endDate: ISO format YYYY-MM-DD, or YYYY-MM when only month precision is stated,
              or null. Set current=true for the present role rather than inventing an end date.
            - summary: 2-3 sentences in the candidate's own terms. Do not editorialise or flatter.
            """;

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]{2,}");
    private static final Pattern PHONE = Pattern.compile("(\\+?\\d{1,3}[\\s-]?)?\\(?\\d{3,5}\\)?[\\s-]?\\d{3}[\\s-]?\\d{3,4}");
    private static final Pattern LINKEDIN = Pattern.compile("(?i)(https?://)?(www\\.)?linkedin\\.com/in/[\\w-]+");
    private static final Pattern GITHUB = Pattern.compile("(?i)(https?://)?(www\\.)?github\\.com/[\\w-]+");
    private static final Pattern YEARS = Pattern.compile("(?i)(\\d{1,2}(?:\\.\\d)?)\\s*\\+?\\s*(?:years?|yrs?)\\s+(?:of\\s+)?experience");

    /** Terms the fallback extractor recognises. Not a substitute for the model, just a floor. */
    private static final List<String> KNOWN_SKILLS = List.of(
            "Java", "Python", "JavaScript", "TypeScript", "Go", "Rust", "Kotlin", "Scala", "Ruby", "PHP",
            "Swift", "C++", "C#", "SQL", "HTML", "CSS",
            "Spring", "Spring Boot", "Spring Security", "Hibernate", "React", "Angular", "Vue", "Next.js",
            "Node.js", "Express", "Django", "Flask", "FastAPI", "Rails", ".NET",
            "PostgreSQL", "MySQL", "MongoDB", "Redis", "Cassandra", "Elasticsearch", "Oracle", "DynamoDB",
            "AWS", "Azure", "GCP", "Kubernetes", "Docker", "Terraform", "Jenkins", "Kafka", "RabbitMQ",
            "Git", "Maven", "Gradle", "Jira", "Linux",
            "REST APIs", "GraphQL", "Microservices", "CI/CD", "Agile", "Scrum", "TDD", "System Design",
            "Machine Learning", "Data Analysis", "Pandas", "NumPy", "TensorFlow", "PyTorch");

    private final AiClient aiClient;
    private final AiQuotaService quotaService;

    public ResumeExtractionService(AiClient aiClient, AiQuotaService quotaService) {
        this.aiClient = aiClient;
        this.quotaService = quotaService;
    }

    public boolean isAiAvailable() {
        return aiClient.isAvailable();
    }

    public String engineName() {
        return aiClient.isAvailable() ? aiClient.modelName() : "heuristic";
    }

    /**
     * Extracts a profile from resume text.
     *
     * <p>Never throws for AI reasons. An unconfigured model, a failed call or a
     * spent allowance all degrade to the deterministic parser, and each says
     * which happened through {@link Extraction#aiAssisted()} and the notice. The
     * upload is not failed over any of them: a student who has used their AI
     * allowance still gets their resume read.
     *
     * @param userId whose AI allowance a model call is charged to
     */
    public Extraction extract(java.util.UUID userId, String resumeText) {
        if (!TextUtils.hasText(resumeText)) {
            return new Extraction(emptyResume(), false, "heuristic",
                    "No readable text was found in that file.");
        }
        String text = trimForModel(resumeText);

        if (aiClient.isAvailable()) {
            // Charged before the call and refunded if the call itself fails.
            // The other order would let a student spend the model and only then
            // discover they had nothing left to spend.
            QuotaDecision decision = quotaService.tryConsume(userId);
            if (!decision.allowed()) {
                return new Extraction(heuristic(resumeText), false, "heuristic",
                        decision.message() + " This profile was read with a simpler parser, "
                                + "so please check it carefully.");
            }
            try {
                ExtractedResume extracted = aiClient.structured(SYSTEM_PROMPT,
                        "Resume text:\n\n" + text, ExtractedResume.class);
                return new Extraction(merge(extracted, resumeText), true, aiClient.modelName(), null);
            } catch (AiUnavailableException ex) {
                quotaService.refund(userId);
                log.warn("Resume extraction fell back to heuristics: {}", ex.getMessage());
                return new Extraction(heuristic(resumeText), false, "heuristic",
                        "AI extraction was unavailable, so this profile was read with a simpler parser. "
                                + "Please check it carefully.");
            }
        }
        return new Extraction(heuristic(resumeText), false, "heuristic",
                "AI is not configured, so this profile was read with a simpler parser. "
                        + "Please check it carefully.");
    }

    /** Fills anything the model left null using the deterministic extractor. */
    private ExtractedResume merge(ExtractedResume model, String rawText) {
        ExtractedResume fallback = heuristic(rawText);
        return new ExtractedResume(
                firstNonBlank(model.fullName(), fallback.fullName()),
                firstNonBlank(model.email(), fallback.email()),
                firstNonBlank(model.phone(), fallback.phone()),
                firstNonBlank(model.location(), fallback.location()),
                firstNonBlank(model.headline(), fallback.headline()),
                firstNonBlank(model.summary(), fallback.summary()),
                firstNonBlank(model.primaryRole(), fallback.primaryRole()),
                firstNonBlank(model.seniority(), fallback.seniority()),
                model.yearsExperience() != null ? model.yearsExperience() : fallback.yearsExperience(),
                firstNonBlank(model.linkedinUrl(), fallback.linkedinUrl()),
                firstNonBlank(model.githubUrl(), fallback.githubUrl()),
                firstNonBlank(model.portfolioUrl(), fallback.portfolioUrl()),
                isEmpty(model.skills()) ? fallback.skills() : model.skills(),
                isEmpty(model.experiences()) ? fallback.experiences() : model.experiences(),
                isEmpty(model.education()) ? fallback.education() : model.education());
    }

    /** Regex-based extraction. Finds contact details and known skill terms; nothing more. */
    ExtractedResume heuristic(String text) {
        String normalized = text.replace("\r\n", "\n");
        Set<String> skills = new LinkedHashSet<>();
        String lowered = normalized.toLowerCase(Locale.ROOT);
        for (String skill : KNOWN_SKILLS) {
            String needle = skill.toLowerCase(Locale.ROOT);
            if (containsTerm(lowered, needle)) {
                skills.add(skill);
            }
        }

        Double years = null;
        Matcher yearsMatcher = YEARS.matcher(normalized);
        if (yearsMatcher.find()) {
            try {
                years = Double.parseDouble(yearsMatcher.group(1));
            } catch (NumberFormatException ignored) {
                // Leave null rather than record a value we could not read.
            }
        }

        return new ExtractedResume(
                guessName(normalized),
                findFirst(EMAIL, normalized),
                findFirst(PHONE, normalized),
                guessLocation(normalized),
                null,
                null,
                null,
                null,
                years,
                findFirst(LINKEDIN, normalized),
                findFirst(GITHUB, normalized),
                null,
                new ArrayList<>(skills),
                List.of(),
                List.of());
    }

    /**
     * A skill term must appear on a word boundary. Without this, "R" matches every
     * word containing the letter and "Go" matches "Google".
     */
    private boolean containsTerm(String haystack, String needle) {
        int from = 0;
        while (true) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) {
                return false;
            }
            boolean leftOk = index == 0 || !Character.isLetterOrDigit(haystack.charAt(index - 1));
            int end = index + needle.length();
            boolean rightOk = end >= haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = index + 1;
        }
    }

    private String guessName(String text) {
        // Resumes almost always open with the candidate's name on its own short line.
        for (String line : text.split("\n")) {
            String candidate = line.strip();
            if (candidate.isEmpty() || candidate.length() > 60) {
                continue;
            }
            if (EMAIL.matcher(candidate).find() || candidate.matches(".*\\d.*")) {
                continue;
            }
            String[] words = candidate.split("\\s+");
            if (words.length >= 2 && words.length <= 4 && Character.isUpperCase(candidate.charAt(0))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Looks for a "City, Region" fragment in the contact block at the top of the
     * resume, which is where it almost always sits. Anything containing digits, an
     * email or a URL is skipped, since those are contact details rather than places.
     */
    private String guessLocation(String text) {
        String[] lines = text.split("\n");
        int limit = Math.min(lines.length, 8);
        for (int i = 0; i < limit; i++) {
            for (String segment : lines[i].split("\\s*[|·•]\\s*")) {
                String candidate = segment.strip();
                if (candidate.length() < 4 || candidate.length() > 60 || !candidate.contains(",")) {
                    continue;
                }
                if (candidate.matches(".*[\\d@/].*")) {
                    continue;
                }
                String[] parts = candidate.split("\\s*,\\s*");
                if (parts.length < 2 || parts.length > 3) {
                    continue;
                }
                boolean allPlaceLike = java.util.Arrays.stream(parts)
                        .allMatch(part -> !part.isBlank()
                                && part.split("\\s+").length <= 3
                                && Character.isUpperCase(part.charAt(0)));
                if (allPlaceLike) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private String findFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group().strip() : null;
    }

    private String trimForModel(String text) {
        // Two-page resumes fit comfortably; anything beyond this is boilerplate.
        return TextUtils.truncate(text, 24_000);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return TextUtils.hasText(preferred) ? preferred.strip() : fallback;
    }

    private static boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    private ExtractedResume emptyResume() {
        return new ExtractedResume(null, null, null, null, null, null, null, null, null,
                null, null, null, List.of(), List.of(), List.of());
    }

    /**
     * Result of an extraction attempt, including how it was produced. {@code notice}
     * is non-null only when the candidate should be told that the extraction was
     * weaker than usual.
     */
    public record Extraction(ExtractedResume resume, boolean aiAssisted, String engine, String notice) {
    }
}
