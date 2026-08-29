package com.careerflux.ingestion.pipeline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.careerflux.common.TextUtils;
import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.source.adapter.RawJobPosting;

import org.springframework.stereotype.Component;

/**
 * Turns a source's posting into CareerFlux's own vocabulary.
 *
 * <p>Everything here is rule-based and deterministic. The same input always
 * produces the same output, which matters because normalization feeds
 * deduplication: if normalization drifted, previously-merged jobs would silently
 * split apart. AI enrichment runs <em>after</em> this stage and is only allowed
 * to fill fields normalization left null.
 */
@Component
public class JobNormalizer {

    private static final Pattern EXPERIENCE_RANGE = Pattern.compile(
            "(?i)(\\d{1,2})\\s*(?:-|to|–)\\s*(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs?)");
    private static final Pattern EXPERIENCE_MINIMUM = Pattern.compile(
            "(?i)(\\d{1,2})\\s*\\+\\s*(?:years?|yrs?)");
    private static final Pattern EXPERIENCE_AT_LEAST = Pattern.compile(
            "(?i)(?:at least|minimum(?: of)?|min\\.?)\\s*(\\d{1,2})\\s*(?:years?|yrs?)");
    private static final Pattern SALARY_RANGE = Pattern.compile(
            "(?i)([$₹€£])\\s?([\\d,]+(?:\\.\\d+)?)\\s?(k)?\\s*(?:-|to|–)\\s*([$₹€£])?\\s?([\\d,]+(?:\\.\\d+)?)\\s?(k)?");

    private static final List<String> REMOTE_MARKERS = List.of(
            "remote", "work from home", "wfh", "anywhere", "distributed");
    private static final List<String> HYBRID_MARKERS = List.of("hybrid", "flexible location", "partially remote");
    private static final List<String> ONSITE_MARKERS = List.of("on-site", "onsite", "in office", "in-office");

    /** Title decorations that carry no meaning for matching and hurt deduplication. */
    private static final Pattern TITLE_NOISE = Pattern.compile(
            "(?i)\\s*[\\(\\[\\-–|]\\s*(remote|hybrid|onsite|on-site|contract|full[ -]?time|part[ -]?time|"
                    + "urgent|hiring|immediate joiner|w2|c2c|[a-z]{2,3}\\s*only|"
                    + "\\d+\\s*(?:openings?|positions?))\\s*[\\)\\]]?\\s*");

    public NormalizedJob normalize(RawJobPosting raw, String fallbackCompanyName) {
        String title = clean(raw.title());
        String description = TextUtils.stripHtml(raw.descriptionHtml());
        String haystack = ((title == null ? "" : title) + "\n"
                + (raw.locationText() == null ? "" : raw.locationText()) + "\n"
                + (description == null ? "" : description)).toLowerCase(Locale.ROOT);

        Location location = parseLocation(raw.locationText());
        WorkMode workMode = resolveWorkMode(raw.workModeText(), haystack, location, title);
        EmploymentType employmentType = resolveEmploymentType(raw.employmentTypeText(), haystack);
        Seniority seniority = inferSeniority(title, haystack);
        ExperienceRange experience = parseExperience(description);
        Salary salary = parseSalary(raw.salaryText() != null ? raw.salaryText() : description);
        Sections sections = splitSections(description);

        String normalizedTitle = normalizeTitle(title);
        String contentHash = TextUtils.sha256(String.join("|",
                nullSafe(normalizedTitle),
                nullSafe(location.raw()),
                nullSafe(description),
                workMode.name(),
                employmentType.name()));

        return new NormalizedJob(
                raw.externalId(),
                TextUtils.truncate(raw.requisitionId(), 120),
                TextUtils.truncate(title, 300),
                TextUtils.truncate(normalizedTitle, 200),
                TextUtils.truncate(firstNonBlank(raw.companyName(), fallbackCompanyName), 200),
                description,
                sections.responsibilities(),
                sections.requirements(),
                TextUtils.truncate(location.raw(), 300),
                TextUtils.truncate(location.city(), 120),
                TextUtils.truncate(location.region(), 120),
                TextUtils.truncate(location.country(), 120),
                workMode,
                employmentType,
                seniority,
                experience.min(),
                experience.max(),
                salary.min(),
                salary.max(),
                salary.currency(),
                salary.period(),
                TextUtils.truncate(raw.applyUrl(), 1000),
                TextUtils.truncate(raw.sourceUrl(), 1000),
                raw.postedAt(),
                contentHash,
                raw.rawPayload());
    }

    /**
     * Strips decorations so that "Senior Java Developer (Remote) - Urgent" and
     * "Senior Java Developer" resolve to the same normalized title.
     */
    String normalizeTitle(String title) {
        if (!TextUtils.hasText(title)) {
            return null;
        }
        String working = TITLE_NOISE.matcher(title).replaceAll(" ");
        working = working.replaceAll("(?i)\\b(m/f/d|f/m/d|m/w/d|h/f)\\b", " ");
        working = TextUtils.canonicalize(working);
        // Roman numerals and trailing level markers are noise for matching purposes.
        working = working.replaceAll("\\b(i{1,3}|iv|v)\\b$", "").strip();
        return working.isEmpty() ? TextUtils.canonicalize(title) : working;
    }

    Location parseLocation(String raw) {
        if (!TextUtils.hasText(raw)) {
            return new Location(null, null, null, null);
        }
        String trimmed = raw.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        // A pure remote marker is a work mode, not a place.
        if (REMOTE_MARKERS.stream().anyMatch(marker -> lower.equals(marker) || lower.equals(marker + " "))) {
            return new Location(trimmed, null, null, null);
        }

        // A posting open in several offices lists them all in one string,
        // separated by a bullet or a pipe rather than a comma. Splitting the
        // whole thing on commas produced nonsense: "San Francisco, CA • New
        // York, NY • United States" yielded a country of "NY • United States".
        // The first office is treated as the primary location, which is what a
        // student filtering by city is asking about.
        String[] offices = trimmed.split("\\s*[•|;]\\s*");
        String primary = offices[0].strip();

        String[] parts = primary.split("\\s*,\\s*");
        String city = parts.length > 0 ? blankToNull(parts[0]) : null;
        String region = parts.length > 2 ? blankToNull(parts[1]) : null;
        String country = parts.length > 1 ? blankToNull(parts[parts.length - 1]) : null;

        // "New York, NY" names a state, not a country. A bare two-letter code in
        // the last slot is a region wherever it appears.
        if (country != null && isRegionCode(country)) {
            if (region == null) {
                region = country;
            }
            country = null;
        }

        // When the offices are listed with a shared country at the end — the
        // pattern in the example above — that trailing segment is the country
        // for all of them, and it is the only place the country appears.
        if (country == null && offices.length > 1) {
            String last = offices[offices.length - 1].strip();
            if (!last.contains(",") && !last.isEmpty() && !isRegionCode(last)) {
                country = last;
            }
        }

        // "Remote, India" leaves the city slot holding a work mode; drop it.
        if (city != null && REMOTE_MARKERS.contains(city.toLowerCase(Locale.ROOT))) {
            city = null;
        }
        return new Location(trimmed, city, region, country);
    }

    /** A bare two-letter uppercase code is a state or province, never a country name. */
    private static boolean isRegionCode(String value) {
        return value.length() == 2 && value.equals(value.toUpperCase(Locale.ROOT))
                && value.chars().allMatch(Character::isLetter);
    }

    WorkMode resolveWorkMode(String stated, String haystack, Location location, String title) {
        // A value the source stated explicitly always beats anything inferred from prose.
        if (TextUtils.hasText(stated)) {
            String value = stated.toLowerCase(Locale.ROOT);
            if (REMOTE_MARKERS.stream().anyMatch(value::contains)) {
                return WorkMode.REMOTE;
            }
            if (HYBRID_MARKERS.stream().anyMatch(value::contains)) {
                return WorkMode.HYBRID;
            }
            if (ONSITE_MARKERS.stream().anyMatch(value::contains) || value.contains("office")) {
                return WorkMode.ONSITE;
            }
        }

        // "Backend Engineer (Remote)" is the employer stating the work mode, not
        // prose that happens to mention the word, so the title ranks just below an
        // explicit field and above the description.
        String titleText = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (HYBRID_MARKERS.stream().anyMatch(titleText::contains)) {
            return WorkMode.HYBRID;
        }
        if (REMOTE_MARKERS.stream().anyMatch(titleText::contains)) {
            return WorkMode.REMOTE;
        }
        if (ONSITE_MARKERS.stream().anyMatch(titleText::contains)) {
            return WorkMode.ONSITE;
        }

        // The location field is something the employer filled in, so it outranks
        // the description entirely. Reading it before the prose matters: a posting
        // located "Remote - United States" whose boilerplate happens to mention
        // hybrid working is a remote job, not a hybrid one.
        String locationText = location.raw() == null ? "" : location.raw().toLowerCase(Locale.ROOT);
        if (!locationText.isEmpty()) {
            if (HYBRID_MARKERS.stream().anyMatch(locationText::contains)) {
                return WorkMode.HYBRID;
            }
            if (REMOTE_MARKERS.stream().anyMatch(locationText::contains)) {
                return WorkMode.REMOTE;
            }
            if (ONSITE_MARKERS.stream().anyMatch(locationText::contains)) {
                return WorkMode.ONSITE;
            }
        }

        // Only unambiguous phrases are trusted from the description. A passing
        // mention of a word is not a statement about this role.
        if (haystack.contains("fully remote") || haystack.contains("100% remote")
                || haystack.contains("remote-first") || haystack.contains("remote first")) {
            return WorkMode.REMOTE;
        }
        if (haystack.contains("hybrid role") || haystack.contains("hybrid position")
                || haystack.contains("hybrid working") || haystack.contains("hybrid work model")) {
            return WorkMode.HYBRID;
        }
        return WorkMode.UNSPECIFIED;
    }

    EmploymentType resolveEmploymentType(String stated, String haystack) {
        String value = TextUtils.hasText(stated) ? stated.toLowerCase(Locale.ROOT) : "";
        if (value.contains("intern")) {
            return EmploymentType.INTERNSHIP;
        }
        if (value.contains("contract") || value.contains("freelance")) {
            return EmploymentType.CONTRACT;
        }
        if (value.contains("part")) {
            return EmploymentType.PART_TIME;
        }
        if (value.contains("temp")) {
            return EmploymentType.TEMPORARY;
        }
        if (value.contains("full") || value.equals("fulltime")) {
            return EmploymentType.FULL_TIME;
        }
        if (haystack.contains("internship")) {
            return EmploymentType.INTERNSHIP;
        }
        if (haystack.contains("full-time") || haystack.contains("full time")) {
            return EmploymentType.FULL_TIME;
        }
        return EmploymentType.UNSPECIFIED;
    }

    Seniority inferSeniority(String title, String haystack) {
        String subject = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (subject.contains("intern") && !subject.contains("internal")) {
            return Seniority.INTERN;
        }
        if (subject.contains("principal") || subject.contains("distinguished")) {
            return Seniority.PRINCIPAL;
        }
        if (subject.contains("staff") || subject.contains("lead") || subject.contains("head of")
                || subject.contains("manager")) {
            return Seniority.LEAD;
        }
        if (subject.contains("senior") || subject.contains("sr.") || subject.contains("sr ")) {
            return Seniority.SENIOR;
        }
        if (subject.contains("junior") || subject.contains("jr.") || subject.contains("associate")) {
            return Seniority.JUNIOR;
        }
        if (subject.contains("entry level") || subject.contains("graduate") || subject.contains("trainee")
                || subject.contains("fresher")) {
            return Seniority.ENTRY;
        }
        if (subject.contains("mid-level") || subject.contains("mid level")) {
            return Seniority.MID;
        }
        if (haystack.contains("fresher") || haystack.contains("entry-level")) {
            return Seniority.ENTRY;
        }
        return Seniority.UNSPECIFIED;
    }

    ExperienceRange parseExperience(String description) {
        if (!TextUtils.hasText(description)) {
            return new ExperienceRange(null, null);
        }
        Matcher range = EXPERIENCE_RANGE.matcher(description);
        if (range.find()) {
            BigDecimal low = decimal(range.group(1));
            BigDecimal high = decimal(range.group(2));
            if (low != null && high != null && low.compareTo(high) <= 0 && high.intValue() <= 40) {
                return new ExperienceRange(low, high);
            }
        }
        Matcher minimum = EXPERIENCE_MINIMUM.matcher(description);
        if (minimum.find()) {
            BigDecimal low = decimal(minimum.group(1));
            if (low != null && low.intValue() <= 40) {
                return new ExperienceRange(low, null);
            }
        }
        Matcher atLeast = EXPERIENCE_AT_LEAST.matcher(description);
        if (atLeast.find()) {
            BigDecimal low = decimal(atLeast.group(1));
            if (low != null && low.intValue() <= 40) {
                return new ExperienceRange(low, null);
            }
        }
        return new ExperienceRange(null, null);
    }

    /**
     * Reads a salary range only when it is stated unambiguously with a currency
     * symbol. Guessing here would put a wrong number in front of a candidate
     * making a real decision, so an unreadable salary stays null.
     */
    Salary parseSalary(String text) {
        if (!TextUtils.hasText(text)) {
            return Salary.empty();
        }
        Matcher matcher = SALARY_RANGE.matcher(text);
        if (!matcher.find()) {
            return Salary.empty();
        }
        BigDecimal low = money(matcher.group(2), matcher.group(3) != null);
        BigDecimal high = money(matcher.group(5), matcher.group(6) != null);
        if (low == null || high == null || low.compareTo(high) > 0) {
            return Salary.empty();
        }
        String currency = switch (matcher.group(1)) {
            case "$" -> "USD";
            case "₹" -> "INR";
            case "€" -> "EUR";
            case "£" -> "GBP";
            default -> null;
        };
        String lower = text.toLowerCase(Locale.ROOT);
        String period = lower.contains("per hour") || lower.contains("/hr") || lower.contains("hourly")
                ? "HOURLY"
                : lower.contains("per month") || lower.contains("/month") ? "MONTHLY" : "ANNUAL";
        return new Salary(low, high, currency, period);
    }

    /**
     * Splits a description into responsibilities and requirements when the posting
     * uses recognisable headings. Postings that do not are left whole rather than
     * chopped at an arbitrary point.
     */
    Sections splitSections(String description) {
        if (!TextUtils.hasText(description)) {
            return new Sections(null, null);
        }
        String responsibilities = extractSection(description,
                "(?im)^\\s*(what you.{0,20}do|responsibilities|the role|your impact|about the role|"
                        + "key responsibilities|duties)\\s*:?\\s*$");
        String requirements = extractSection(description,
                "(?im)^\\s*(requirements|qualifications|what we.{0,20}looking for|who you are|"
                        + "skills|must have|your profile|what you bring)\\s*:?\\s*$");
        return new Sections(responsibilities, requirements);
    }

    private String extractSection(String description, String headingPattern) {
        Matcher matcher = Pattern.compile(headingPattern).matcher(description);
        if (!matcher.find()) {
            return null;
        }
        int start = matcher.end();
        // A section runs until the next heading-looking line or the end of the text.
        Matcher next = Pattern.compile("(?m)^\\s*[A-Z][A-Za-z '/&]{3,40}\\s*:?\\s*$").matcher(description);
        int end = description.length();
        if (next.find(start + 1)) {
            end = next.start();
        }
        String section = description.substring(start, end).strip();
        return section.isEmpty() ? null : section;
    }

    private BigDecimal money(String digits, boolean thousands) {
        BigDecimal value = decimal(digits.replace(",", ""));
        if (value == null) {
            return null;
        }
        return thousands ? value.multiply(BigDecimal.valueOf(1000)) : value;
    }

    private BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value).setScale(1, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String clean(String value) {
        return TextUtils.hasText(value) ? value.strip().replaceAll("\\s+", " ") : null;
    }

    private String blankToNull(String value) {
        return TextUtils.hasText(value) ? value.strip() : null;
    }

    private String firstNonBlank(String first, String second) {
        return TextUtils.hasText(first) ? first.strip() : second;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    record Location(String raw, String city, String region, String country) {
    }

    record ExperienceRange(BigDecimal min, BigDecimal max) {
    }

    record Sections(String responsibilities, String requirements) {
    }

    record Salary(BigDecimal min, BigDecimal max, String currency, String period) {

        static Salary empty() {
            return new Salary(null, null, null, null);
        }
    }
}
