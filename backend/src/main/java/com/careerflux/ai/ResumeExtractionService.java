package com.careerflux.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.careerflux.ai.dto.AiResumeReading;
import com.careerflux.ai.dto.ExtractedResume;
import com.careerflux.ai.policy.AiProcessingPolicy;
import com.careerflux.ai.policy.AiPurpose;
import com.careerflux.candidate.repository.CandidateProfileRepository;
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
 *
 * <p><b>Nothing reaches the model without consent, and nothing identifying
 * reaches it at all.</b> {@link AiProcessingPolicy} is asked first, before any AI
 * allowance is spent; a student who has not agreed to AI processing is read by
 * the local parser, and the upload succeeds exactly as it would with AI switched
 * off. When the model is used, it receives the text only after
 * {@link ResumeRedactor} has removed contact details, links, identity numbers
 * and the student's name, and only then is it shortened to fit. Contact details
 * the profile does use are read locally from the original text, never by the
 * model.
 */
@Service
public class ResumeExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ResumeExtractionService.class);

    /** The most text a model is sent, applied to the redacted text. */
    static final int MODEL_TEXT_LIMIT = 24_000;

    static final String AI_NOT_PERMITTED_NOTICE = "AI processing is off for your account, so your resume was "
            + "read on CareerFlux with a simpler parser and was not sent to an AI provider. Please check the "
            + "suggestions carefully. You can turn AI processing on in your privacy settings.";

    static final String SYSTEM_PROMPT = """
            You read resumes and return structured data about a candidate's work, education and skills.

            Personal details were removed from the text before you received it. Placeholders such as
            [CANDIDATE], [EMAIL], [PHONE], [LINK], [ADDRESS], [ID], [DATE OF BIRTH] and [FILE] stand where
            they were. Never try to work out what a placeholder replaced, and never copy a placeholder into
            any field.

            Rules:
            - Only report what the resume actually says. Never invent an employer, a date, a school or a skill.
            - Leave a field null when the resume does not state it. A null is always better than a guess.
            - skills: individual technologies, tools and practices. One per entry, 1-3 words, no sentences.
            - seniority: one of INTERN, ENTRY, JUNIOR, MID, SENIOR, LEAD, PRINCIPAL. Use null if unclear.
            - yearsExperience: total professional experience in years, as a number. Exclude internships
              unless they are the only experience. Use null if it cannot be determined.
            - startDate and endDate: ISO format YYYY-MM-DD, or YYYY-MM when only month precision is stated,
              or null. Set current=true for the present role rather than inventing an end date.
            - summary: 2-3 sentences about the candidate's work, without naming anyone. Do not editorialise
              or flatter.
            """;

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]{2,}");
    /**
     * A run that could be a phone number: digits with the spaces, hyphens and
     * brackets people write between them, on one line. Whether it is one is
     * decided by {@link #isPhoneNumber}, on the digits alone, because the
     * grouping varies too much to pattern-match — "98765 43210", the usual way
     * an Indian mobile is written, defeated the fixed 3-3-4 shape used before.
     */
    private static final Pattern PHONE = Pattern.compile("(?<![\\w+])\\(?\\+?\\d[\\d \\t()-]{8,20}\\d(?!\\w)");
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
    private final AiProcessingPolicy policy;
    private final ResumeRedactor redactor;
    private final CandidateProfileRepository profiles;

    public ResumeExtractionService(AiClient aiClient, AiQuotaService quotaService, AiProcessingPolicy policy,
                                   ResumeRedactor redactor, CandidateProfileRepository profiles) {
        this.aiClient = aiClient;
        this.quotaService = quotaService;
        this.policy = policy;
        this.redactor = redactor;
        this.profiles = profiles;
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
        if (!aiClient.isAvailable()) {
            return new Extraction(heuristic(resumeText), false, "heuristic",
                    "AI is not configured, so this profile was read with a simpler parser. "
                            + "Please check it carefully.");
        }
        // Consent first: before any allowance is spent and before anything is
        // prepared for the provider. Asked now, not when the upload started.
        if (!policy.mayProcess(userId, AiPurpose.RESUME_EXTRACTION)) {
            return new Extraction(heuristic(resumeText), false, "heuristic", AI_NOT_PERMITTED_NOTICE);
        }
        // Charged before the call and refunded if the call itself fails.
        // The other order would let a student spend the model and only then
        // discover they had nothing left to spend.
        QuotaDecision decision = quotaService.tryConsume(userId);
        if (!decision.allowed()) {
            return new Extraction(heuristic(resumeText), false, "heuristic",
                    decision.message() + " This profile was read with a simpler parser, "
                            + "so please check it carefully.");
        }
        // Redacted first and shortened second. Shortening first could cut an
        // address or a number in half and leave a fragment no pattern recognises.
        String sanitized = trimForModel(redactor.redact(resumeText, knownIdentity(userId)));
        try {
            AiResumeReading reading = aiClient.structured(SYSTEM_PROMPT,
                    "Resume text:\n\n" + sanitized, AiResumeReading.class);
            return new Extraction(merge(reading, resumeText), true, aiClient.modelName(), null);
        } catch (AiUnavailableException ex) {
            quotaService.refund(userId);
            log.warn("Resume extraction fell back to the local parser ({})", ex.getClass().getSimpleName());
            return new Extraction(heuristic(resumeText), false, "heuristic",
                    "AI extraction was unavailable, so this profile was read with a simpler parser. "
                            + "Please check it carefully.");
        }
    }

    /** What the account already knows, so the redactor can find it in the text. */
    private ResumeRedactor.KnownIdentity knownIdentity(java.util.UUID userId) {
        if (userId == null) {
            return ResumeRedactor.KnownIdentity.none();
        }
        return profiles.findKnownIdentityByUserId(userId)
                .map(known -> new ResumeRedactor.KnownIdentity(known.getFullName(), known.getEmail(),
                        known.getPhone()))
                .orElse(ResumeRedactor.KnownIdentity.none());
    }

    /**
     * The model's reading of the work, with contact details from the local parser.
     *
     * <p>The model never saw the student's name, email, phone, location or links,
     * so those come from the deterministic extractor reading the original text on
     * this server. A value the model built around a placeholder is dropped rather
     * than proposed to the student with "[CANDIDATE]" in it.
     */
    private ExtractedResume merge(AiResumeReading model, String rawText) {
        ExtractedResume local = heuristic(rawText);
        return new ExtractedResume(
                local.fullName(),
                local.email(),
                local.phone(),
                local.location(),
                firstNonBlank(withoutPlaceholder(model.headline()), local.headline()),
                firstNonBlank(withoutPlaceholder(model.summary()), local.summary()),
                firstNonBlank(withoutPlaceholder(model.primaryRole()), local.primaryRole()),
                firstNonBlank(withoutPlaceholder(model.seniority()), local.seniority()),
                model.yearsExperience() != null ? model.yearsExperience() : local.yearsExperience(),
                local.linkedinUrl(),
                local.githubUrl(),
                local.portfolioUrl(),
                isEmpty(model.skills()) ? local.skills() : model.skills().stream()
                        .filter(skill -> withoutPlaceholder(skill) != null).toList(),
                isEmpty(model.experiences()) ? local.experiences() : model.experiences(),
                isEmpty(model.education()) ? local.education() : model.education());
    }

    private static String withoutPlaceholder(String value) {
        if (value == null) {
            return null;
        }
        for (String placeholder : ResumeRedactor.PLACEHOLDERS) {
            if (value.contains(placeholder)) {
                return null;
            }
        }
        return value;
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

        // The work and the degrees are read from the document's own layout. They
        // used to be left to the model alone, which meant that with no model
        // configured a student was shown a handful of skills and asked to type
        // their education and employment in again from the file they had just
        // uploaded. Everything here is quoted from the resume: nothing is
        // inferred, and a part the document does not state stays null.
        Map<Part, String> parts = parts(normalized);
        List<ExtractedResume.ExtractedExperience> experiences = experiences(parts.get(Part.EXPERIENCE));
        List<ExtractedResume.ExtractedEducation> education = education(parts.get(Part.EDUCATION));
        String name = guessName(normalized);
        String role = experiences.stream()
                .map(ExtractedResume.ExtractedExperience::title)
                .filter(TextUtils::hasText)
                .findFirst()
                .orElse(null);

        return new ExtractedResume(
                name,
                findFirst(EMAIL, normalized),
                findPhone(normalized),
                guessLocation(normalized),
                guessHeadline(normalized, name),
                summary(parts.get(Part.SUMMARY)),
                role,
                seniorityOf(role),
                years,
                findFirst(LINKEDIN, normalized),
                findFirst(GITHUB, normalized),
                null,
                new ArrayList<>(skills),
                experiences,
                education);
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

    /** The first run of digits that reads as a phone number rather than a year range, score or id. */
    private String findPhone(String text) {
        Matcher matcher = PHONE.matcher(text);
        while (matcher.find()) {
            // The run can carry on into unrelated digits on the same line
            // ("9876543210 2019-2023"), so shorter prefixes, cut at a separator,
            // are tried as well.
            String candidate = matcher.group().strip();
            while (!candidate.isEmpty()) {
                if (isPhoneNumber(candidate)) {
                    return candidate;
                }
                int cut = Math.max(candidate.lastIndexOf(' '), Math.max(candidate.lastIndexOf('\t'),
                        candidate.lastIndexOf('-')));
                candidate = cut < 0 ? "" : candidate.substring(0, cut).strip();
            }
        }
        return null;
    }

    /**
     * Indian numbers first, since those are the students this reads: a 10-digit
     * mobile (starting 6-9), a landline with its leading 0, or either with the
     * 91 country code. Anything else needs an explicit "+" to count, which keeps
     * "2022-2026", marks and 12-digit identity numbers out.
     */
    static boolean isPhoneNumber(String candidate) {
        String digits = candidate.replaceAll("\\D", "");
        boolean international = candidate.contains("+");
        return switch (digits.length()) {
            case 10 -> digits.charAt(0) >= '6' && digits.charAt(0) <= '9';
            case 11 -> digits.startsWith("0") || international;
            case 12 -> digits.startsWith("91") || international;
            case 13 -> international;
            default -> false;
        };
    }

    // ------------------------------------------------------- the parts of a resume

    /**
     * The parts of a resume the local parser reads.
     *
     * <p>{@code OTHER} is not read, and exists so that a heading this parser has
     * no use for still ends the section above it. Without it a PROJECTS list
     * would be swallowed by whatever section happened to precede it and read as
     * employment.
     */
    private enum Part { SUMMARY, EXPERIENCE, EDUCATION, OTHER }

    /** The most entries read from one section, and the longest text kept from one. */
    private static final int MAX_ENTRIES = 12;
    private static final int MAX_DESCRIPTION = 2_000;
    private static final int MAX_SUMMARY = 4_000;

    private static final Pattern ROLE_WORDS = Pattern.compile("(?i)\\b(engineer|developer|intern|analyst"
            + "|manager|designer|scientist|consultant|architect|lead|trainee|associate|administrator"
            + "|specialist|officer|executive|coordinator|assistant|technician|programmer|researcher"
            + "|strategist|accountant|teacher|instructor)\\b");

    private static final Pattern COMPANY_WORDS = Pattern.compile("(?i)\\b(technologies|technology|labs"
            + "|laboratories|solutions|systems|software|services|consulting|analytics|industries|group"
            + "|corporation|corp|inc|ltd|limited|pvt|private|llp|llc|plc|enterprises|ventures|studio"
            + "|studios|media|digital|networks|infotech|bank|institute|university|college|foundation"
            + "|hospital|motors|international)\\b");

    private static final Pattern INSTITUTION_WORDS = Pattern.compile("(?i)\\b(university|institute"
            + "|college|school|academy|polytechnic|vidyalaya|vidyalayam|gurukul|iit|nit|iiit|bits"
            + "|campus|faculty)\\b");

    private static final Pattern DEGREE_WORDS = Pattern.compile("(?i)(\\bbachelor|\\bmaster|\\bb\\.?\\s?tech\\b"
            + "|\\bm\\.?\\s?tech\\b|\\bb\\.?e\\.?\\b|\\bm\\.?e\\.?\\b|\\bb\\.?sc\\b|\\bm\\.?sc\\b|\\bbca\\b"
            + "|\\bmca\\b|\\bmba\\b|\\bbba\\b|\\bb\\.?com\\b|\\bm\\.?com\\b|\\bb\\.?a\\.?\\b|\\bm\\.?a\\.?\\b"
            + "|\\bph\\.?\\s?d\\b|\\bdiploma\\b|\\bintermediate\\b|\\bhigher secondary\\b|\\bsenior secondary"
            + "|\\bsecondary school|\\bclass\\s+(x|xii|10|12)\\b|\\bhsc\\b|\\bssc\\b|\\bpuc\\b)");

    /** A grade as resumes write it: a CGPA out of ten, or a percentage. */
    private static final Pattern GRADE = Pattern.compile("(?i)(cgpa|gpa|percentage|aggregate|marks|score"
            + "|grade)\\s*[:\\-]?\\s*\\d{1,3}(\\.\\d+)?\\s*(/\\s*\\d{1,3}(\\.\\d+)?)?|\\b\\d{1,3}(\\.\\d+)?\\s*%");

    private static final String DATE_TOKEN = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)"
            + "[a-z]*\\.?,?\\s+\\d{4}|\\d{1,2}/\\d{4}|\\d{4}";

    private static final Pattern DATE_RANGE = Pattern.compile("(?i)(?<start>" + DATE_TOKEN + ")"
            + "\\s*(?:-|\u2013|\u2014|to|until|till)\\s*"
            + "(?<end>" + DATE_TOKEN + "|present|current|now|ongoing|till\\s+date|to\\s+date|date)");

    private static final Pattern YEAR_RANGE = Pattern.compile("(?i)\\b(\\d{4})\\s*(?:-|\u2013|\u2014|to)\\s*"
            + "(\\d{4}|present|current|now|ongoing)\\b");

    private static final Set<String> ONGOING =
            Set.of("present", "current", "now", "ongoing", "till date", "to date", "date");

    private static final List<String> MONTHS =
            List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

    private static final Map<String, Part> HEADINGS = headings();

    private static Map<String, Part> headings() {
        Map<String, Part> headings = new HashMap<>();
        for (String heading : List.of("summary", "professional summary", "career summary", "profile",
                "professional profile", "profile summary", "objective", "career objective",
                "about", "about me", "overview")) {
            headings.put(heading, Part.SUMMARY);
        }
        for (String heading : List.of("experience", "work experience", "professional experience",
                "employment", "employment history", "work history", "internship", "internships",
                "internship experience", "industry experience", "professional background",
                "relevant experience")) {
            headings.put(heading, Part.EXPERIENCE);
        }
        for (String heading : List.of("education", "educational qualifications", "academic qualifications",
                "academics", "academic background", "qualifications", "educational background",
                "education and training", "academic details")) {
            headings.put(heading, Part.EDUCATION);
        }
        // Read by nobody, but they close the section above them.
        for (String heading : List.of("skills", "technical skills", "key skills", "core competencies",
                "competencies", "technologies", "projects", "academic projects", "personal projects",
                "certifications", "certificates", "achievements", "awards", "honours", "honors",
                "publications", "activities", "interests", "hobbies", "languages", "references",
                "extracurricular", "positions of responsibility", "workshops", "training", "courses",
                "coursework", "volunteer experience", "accomplishments", "personal details",
                "declaration", "strengths", "contact", "links", "profiles")) {
            headings.put(heading, Part.OTHER);
        }
        return Map.copyOf(headings);
    }

    /**
     * Splits the resume at its headings.
     *
     * <p>This is layout, not meaning: a line counts as a heading when it reads as
     * a label rather than a sentence and uses a word resumes put above a section.
     * Text before the first heading is the contact block, and belongs to no part.
     */
    private Map<Part, String> parts(String text) {
        Map<Part, StringBuilder> collected = new EnumMap<>(Part.class);
        Part current = null;
        for (String line : text.split("\n")) {
            Part heading = headingOf(line);
            if (heading != null) {
                current = heading;
                collected.computeIfAbsent(current, part -> new StringBuilder());
                continue;
            }
            if (current != null) {
                collected.get(current).append(line).append('\n');
            }
        }
        Map<Part, String> parts = new EnumMap<>(Part.class);
        collected.forEach((part, body) -> parts.put(part, body.toString()));
        return parts;
    }

    private static Part headingOf(String line) {
        String candidate = line.strip();
        // A heading is short, and it is a label: it does not end a sentence, and
        // it is not a line out of the contact block.
        if (candidate.isEmpty() || candidate.length() > 48 || candidate.endsWith(".")
                || candidate.contains("@")) {
            return null;
        }
        String key = candidate.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z ]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return HEADINGS.get(key);
    }

    /**
     * The entries inside a section.
     *
     * <p>A blank line separates two entries when the writer left one. Plenty of
     * resumes do not, so an entry also ends where the next plainly starts: once a
     * block carries a date range, the next line that is not a bullet begins the
     * entry after it.
     */
    private static List<List<String>> blocks(String body) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        boolean dated = false;
        for (String raw : body.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                if (!current.isEmpty()) {
                    blocks.add(current);
                    current = new ArrayList<>();
                    dated = false;
                }
                continue;
            }
            if (!isBullet(line) && dated && !current.isEmpty()) {
                blocks.add(current);
                current = new ArrayList<>();
                dated = false;
            }
            current.add(line);
            dated = dated || DATE_RANGE.matcher(line).find() || YEAR_RANGE.matcher(line).find();
        }
        if (!current.isEmpty()) {
            blocks.add(current);
        }
        return blocks;
    }

    private static boolean isBullet(String line) {
        return !line.isEmpty() && "-*\u2022\u00b7\u25aa\u2023\u25cf\u25e6\u2043".indexOf(line.charAt(0)) >= 0;
    }

    private static String withoutBullet(String line) {
        return isBullet(line) ? line.substring(1).strip() : line;
    }

    /** Trims the punctuation left behind when a date or a grade is cut out of a line. */
    private static String trimSeparators(String value) {
        return value.replaceAll("^[\\s,|\u00b7\u2022\\-\u2013\u2014]+", "")
                .replaceAll("[\\s,|\u00b7\u2022\\-\u2013\u2014]+$", "")
                .replaceAll("\\s{2,}", " ")
                .strip();
    }

    // ------------------------------------------------------------------ summary

    /** The summary section as one paragraph, or null when the heading had nothing under it. */
    private String summary(String body) {
        if (body == null) {
            return null;
        }
        String collapsed = Arrays.stream(body.split("\n"))
                .map(ResumeExtractionService::withoutBullet)
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining(" "))
                .replaceAll("\\s{2,}", " ")
                .strip();
        // A heading with a word under it is a layout artefact, not a summary.
        return collapsed.length() < 40 ? null : TextUtils.truncate(collapsed, MAX_SUMMARY);
    }

    // --------------------------------------------------------------- experience

    private List<ExtractedResume.ExtractedExperience> experiences(String body) {
        if (body == null) {
            return List.of();
        }
        List<ExtractedResume.ExtractedExperience> entries = new ArrayList<>();
        for (List<String> block : blocks(body)) {
            ExtractedResume.ExtractedExperience entry = experience(block);
            if (entry != null) {
                entries.add(entry);
            }
            if (entries.size() >= MAX_ENTRIES) {
                break;
            }
        }
        return List.copyOf(entries);
    }

    /**
     * One employment entry.
     *
     * <p>The first line that is not a bullet names the job; the dates sit on it or
     * under it, and the bullets beneath are what the person did there. A block
     * whose first line names neither a role nor an employer is not an entry, and
     * is left out rather than proposed as "Unspecified at Unspecified".
     */
    private ExtractedResume.ExtractedExperience experience(List<String> block) {
        String header = null;
        String start = null;
        String end = null;
        Boolean current = null;
        List<String> details = new ArrayList<>();

        for (String line : block) {
            String text = line;
            Matcher dates = DATE_RANGE.matcher(line);
            if (dates.find()) {
                if (start == null) {
                    start = isoDate(dates.group("start"));
                    String finish = dates.group("end").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                    current = ONGOING.contains(finish);
                    end = Boolean.TRUE.equals(current) ? null : isoDate(dates.group("end"));
                }
                text = line.substring(0, dates.start()) + " " + line.substring(dates.end());
            }
            String cleaned = trimSeparators(withoutBullet(text));
            if (cleaned.isEmpty()) {
                // The line carried nothing but the dates.
                continue;
            }
            if (header == null && !isBullet(line)) {
                header = cleaned;
                continue;
            }
            if (details.size() < MAX_ENTRIES * 4) {
                details.add(cleaned);
            }
        }

        if (header == null) {
            return null;
        }
        String[] named = splitRole(header);
        if (named == null) {
            return null;
        }
        String description = details.isEmpty()
                ? null
                : TextUtils.truncate(String.join("\n", details), MAX_DESCRIPTION);
        return new ExtractedResume.ExtractedExperience(
                named[1], named[0], named[2], start, end, current, description);
    }

    /**
     * Reads "Title, Company, City" and the other orders people write it in.
     *
     * <p>Position alone is unreliable, since plenty of resumes lead with the
     * employer. A segment naming a role and a segment naming a company are
     * recognised by their own words first, and position is only the fallback.
     *
     * @return title, company and location, any of which may be null; or null when
     *         the line names nothing usable.
     */
    private static String[] splitRole(String header) {
        String normalized = header.replaceAll("(?i)\\s+(?:at|@)\\s+", ",");
        List<String> segments = Arrays.stream(
                        normalized.split("\\s*[,|\u00b7\u2022]\\s*|\\s+[-\u2013\u2014]\\s+"))
                .map(String::strip)
                .filter(segment -> !segment.isEmpty())
                .limit(6)
                .toList();
        if (segments.isEmpty()) {
            return null;
        }

        int titleIndex = indexMatching(segments, ROLE_WORDS, -1);
        int companyIndex = indexMatching(segments, COMPANY_WORDS, titleIndex);
        if (titleIndex < 0 && companyIndex < 0) {
            // Nothing announced itself, so fall back to the usual order.
            titleIndex = 0;
            companyIndex = segments.size() > 1 ? 1 : -1;
        } else if (titleIndex < 0) {
            titleIndex = firstUnused(segments, companyIndex);
        } else if (companyIndex < 0) {
            companyIndex = firstUnused(segments, titleIndex);
        }

        int locationIndex = -1;
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (i != titleIndex && i != companyIndex && isPlaceLike(segments.get(i))) {
                locationIndex = i;
                break;
            }
        }
        String title = titleIndex < 0 ? null : segments.get(titleIndex);
        String company = companyIndex < 0 ? null : segments.get(companyIndex);
        if (title == null && company == null) {
            return null;
        }
        return new String[] {
                truncate(title, 200),
                truncate(company, 200),
                locationIndex < 0 ? null : truncate(segments.get(locationIndex), 160)};
    }

    private static int indexMatching(List<String> segments, Pattern pattern, int skip) {
        for (int i = 0; i < segments.size(); i++) {
            if (i != skip && pattern.matcher(segments.get(i)).find()) {
                return i;
            }
        }
        return -1;
    }

    private static int firstUnused(List<String> segments, int used) {
        for (int i = 0; i < segments.size(); i++) {
            if (i != used) {
                return i;
            }
        }
        return -1;
    }

    /** A few capitalised words with no digits: a city, not a job title or a date. */
    private static boolean isPlaceLike(String value) {
        return !value.isEmpty()
                && value.length() <= 60
                && !value.matches(".*[\\d@/].*")
                && value.split("\\s+").length <= 3
                && Character.isUpperCase(value.charAt(0))
                && !ROLE_WORDS.matcher(value).find();
    }

    // ---------------------------------------------------------------- education

    private List<ExtractedResume.ExtractedEducation> education(String body) {
        if (body == null) {
            return List.of();
        }
        List<ExtractedResume.ExtractedEducation> entries = new ArrayList<>();
        for (List<String> block : blocks(body)) {
            ExtractedResume.ExtractedEducation entry = education(block);
            if (entry != null) {
                entries.add(entry);
            }
            if (entries.size() >= MAX_ENTRIES) {
                break;
            }
        }
        return List.copyOf(entries);
    }

    /**
     * One education entry.
     *
     * <p>The years and the grade are cut out of whichever line carries them,
     * because they are regularly written on the same line as the degree. What is
     * left is a school if it names one and a degree if it names one, in either
     * order, since both layouts are common.
     *
     * <p>An entry without an institution is dropped: that is the one field the
     * profile requires, and a degree floating free of a school is not something to
     * ask a student to confirm.
     */
    private ExtractedResume.ExtractedEducation education(List<String> block) {
        String institution = null;
        String degree = null;
        String fieldOfStudy = null;
        String grade = null;
        Integer startYear = null;
        Integer endYear = null;
        List<String> leftovers = new ArrayList<>();

        for (String line : block) {
            String text = line;
            Matcher years = YEAR_RANGE.matcher(text);
            if (years.find()) {
                if (startYear == null) {
                    startYear = year(years.group(1));
                    endYear = ONGOING.contains(years.group(2).toLowerCase(Locale.ROOT))
                            ? null
                            : year(years.group(2));
                }
                text = text.substring(0, years.start()) + " " + text.substring(years.end());
            }
            Matcher graded = GRADE.matcher(text);
            if (graded.find()) {
                if (grade == null) {
                    grade = truncate(trimSeparators(graded.group()), 60);
                }
                text = text.substring(0, graded.start()) + " " + text.substring(graded.end());
            }
            String cleaned = trimSeparators(withoutBullet(text));
            if (cleaned.isEmpty()) {
                continue;
            }
            if (institution == null && INSTITUTION_WORDS.matcher(cleaned).find()) {
                institution = truncate(placeTrimmed(cleaned), 200);
                continue;
            }
            if (degree == null && DEGREE_WORDS.matcher(cleaned).find()) {
                String[] read = splitDegree(cleaned);
                degree = read[0];
                fieldOfStudy = read[1];
                continue;
            }
            leftovers.add(cleaned);
        }

        if (institution == null) {
            // No line named a school in so many words. The first line that is not
            // the degree is where the name sits in every layout this reads.
            institution = leftovers.stream().findFirst()
                    .map(line -> truncate(placeTrimmed(line), 200))
                    .orElse(null);
        }
        if (institution == null) {
            return null;
        }
        if (startYear != null && endYear != null && endYear < startYear) {
            // Read backwards, so trust neither.
            startYear = null;
            endYear = null;
        }
        return new ExtractedResume.ExtractedEducation(
                institution, degree, fieldOfStudy, startYear, endYear, grade);
    }

    /** "Bachelor of Technology, Computer Science" and "B.Tech in Computer Science". */
    private static String[] splitDegree(String line) {
        Matcher in = Pattern.compile("(?i)^(.{2,80}?)\\s+in\\s+(.{2,120})$").matcher(line);
        if (in.matches() && DEGREE_WORDS.matcher(in.group(1)).find()) {
            return new String[] {truncate(in.group(1), 160), truncate(in.group(2), 160)};
        }
        String[] segments = line.split("\\s*[,|\u00b7\u2022]\\s*|\\s+[-\u2013\u2014]\\s+");
        String degree = null;
        String fieldOfStudy = null;
        for (String raw : segments) {
            String segment = raw.strip();
            if (segment.isEmpty()) {
                continue;
            }
            if (degree == null && DEGREE_WORDS.matcher(segment).find()) {
                degree = truncate(segment, 160);
                continue;
            }
            if (degree != null && fieldOfStudy == null && !segment.matches(".*\\d.*")) {
                fieldOfStudy = truncate(segment, 160);
            }
        }
        return new String[] {degree == null ? truncate(line, 160) : degree, fieldOfStudy};
    }

    /** Drops a trailing city from "Northgate Institute of Technology, Hyderabad". */
    private static String placeTrimmed(String value) {
        int comma = value.lastIndexOf(',');
        if (comma <= 0) {
            return value;
        }
        String head = value.substring(0, comma).strip();
        String tail = value.substring(comma + 1).strip();
        return !head.isEmpty() && isPlaceLike(tail) ? head : value;
    }

    // ---------------------------------------------------------------- seniority

    /**
     * The level a job title states, and only that.
     *
     * <p>Years of experience are not turned into a level here. Where the resume
     * does not say, CareerFlux records unspecified: a guess would be written onto
     * the profile that matching reads, and a student who is sorted into the wrong
     * band never finds out why.
     */
    private static String seniorityOf(String title) {
        if (!TextUtils.hasText(title)) {
            return null;
        }
        String lowered = title.toLowerCase(Locale.ROOT);
        if (containsWord(lowered, "principal")) {
            return "PRINCIPAL";
        }
        if (containsWord(lowered, "lead")) {
            return "LEAD";
        }
        if (containsWord(lowered, "senior") || containsWord(lowered, "sr")) {
            return "SENIOR";
        }
        if (containsWord(lowered, "junior") || containsWord(lowered, "jr")) {
            return "JUNIOR";
        }
        if (containsWord(lowered, "intern") || containsWord(lowered, "internship")) {
            return "INTERN";
        }
        if (containsWord(lowered, "trainee") || containsWord(lowered, "fresher")
                || containsWord(lowered, "graduate")) {
            return "ENTRY";
        }
        return null;
    }

    private static boolean containsWord(String haystack, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(haystack).find();
    }

    /**
     * A title line under the candidate's name, which is where a resume states the
     * role the person presents themselves as.
     *
     * <p>Only a line that reads as a job title counts. The contact block sits in
     * the same place, and a summary sentence must not be promoted to a headline.
     */
    private String guessHeadline(String text, String name) {
        String[] lines = text.split("\n");
        int limit = Math.min(lines.length, 6);
        for (int i = 0; i < limit; i++) {
            String candidate = lines[i].strip();
            if (headingOf(candidate) != null) {
                // The contact block has ended without one.
                return null;
            }
            if (candidate.isEmpty() || candidate.length() > 80 || candidate.endsWith(".")
                    || candidate.matches(".*[\\d@|].*")) {
                continue;
            }
            if (name != null && candidate.equalsIgnoreCase(name.strip())) {
                continue;
            }
            if (ROLE_WORDS.matcher(candidate).find()) {
                return candidate;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ small helpers

    /** "June 2025" as 2025-06 and "2025" as 2025: the ISO shapes the profile parses. */
    private static String isoDate(String token) {
        String value = token.strip().toLowerCase(Locale.ROOT).replace(",", "");
        if (value.matches("\\d{4}")) {
            return value;
        }
        Matcher slash = Pattern.compile("(\\d{1,2})/(\\d{4})").matcher(value);
        if (slash.matches()) {
            int month = Integer.parseInt(slash.group(1));
            return month >= 1 && month <= 12 ? slash.group(2) + "-" + twoDigits(month) : null;
        }
        Matcher named = Pattern.compile("([a-z]{3})[a-z]*\\.?\\s+(\\d{4})").matcher(value);
        if (named.matches()) {
            int month = MONTHS.indexOf(named.group(1));
            return month < 0 ? null : named.group(2) + "-" + twoDigits(month + 1);
        }
        return null;
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    private static Integer year(String value) {
        try {
            int year = Integer.parseInt(value.strip());
            // A resume states school years, not the fourteenth century.
            return year >= 1950 && year <= 2100 ? year : null;
        } catch (NumberFormatException notAYear) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        return value == null ? null : TextUtils.truncate(value.strip(), max);
    }

    private String trimForModel(String text) {
        // Two-page resumes fit comfortably; anything beyond this is boilerplate.
        return TextUtils.truncate(text, MODEL_TEXT_LIMIT);
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
