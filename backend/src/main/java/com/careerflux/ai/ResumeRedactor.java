package com.careerflux.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.careerflux.common.TextUtils;

import org.springframework.stereotype.Component;

/**
 * Removes what identifies a student from resume text before any of it is sent to
 * an AI provider.
 *
 * <p>Deterministic on purpose: regular expressions and the identity the account
 * already holds, no model and no network. The same input always produces the
 * same output, which is what lets a test prove that an identifier cannot reach
 * the provider rather than hope it does not.
 *
 * <p>What goes: email addresses, phone numbers, postal addresses, profile and
 * portfolio links, national-ID-like numbers, dates of birth, file and photo
 * names, and the student's own name, which becomes {@value #CANDIDATE}. What
 * stays is what reading a resume is for: employers, titles, dates of work,
 * skills, degrees and institutions.
 *
 * <p>It errs towards removing. Losing an occasional word that happens to be
 * spelled like part of a student's name costs a slightly thinner reading; letting
 * the name through costs the thing this class exists to prevent.
 */
@Component
public class ResumeRedactor {

    public static final String CANDIDATE = "[CANDIDATE]";
    static final String EMAIL_TOKEN = "[EMAIL]";
    static final String PHONE_TOKEN = "[PHONE]";
    static final String LINK_TOKEN = "[LINK]";
    static final String ID_TOKEN = "[ID]";
    static final String ADDRESS_TOKEN = "[ADDRESS]";
    static final String BIRTH_DATE_TOKEN = "[DATE OF BIRTH]";
    static final String FILE_TOKEN = "[FILE]";

    /** Every placeholder this class writes, so model output that repeats one can be recognised. */
    public static final List<String> PLACEHOLDERS = List.of(CANDIDATE, EMAIL_TOKEN, PHONE_TOKEN, LINK_TOKEN,
            ID_TOKEN, ADDRESS_TOKEN, BIRTH_DATE_TOKEN, FILE_TOKEN);

    /** The identity the platform already knows. Any part may be null or blank. */
    public record KnownIdentity(String fullName, String email, String phone) {

        public static KnownIdentity none() {
            return new KnownIdentity(null, null, null);
        }
    }

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");

    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>()\"'|]+");

    /** Profile and portfolio hosts, with or without a scheme or a path. */
    private static final Pattern PROFILE_HOST = Pattern.compile("(?i)\\b(?:[a-z0-9-]+\\.)*(?:"
            + "linkedin\\.com|github\\.com|gitlab\\.com|bitbucket\\.org|behance\\.net|dribbble\\.com|"
            + "medium\\.com|twitter\\.com|x\\.com|instagram\\.com|facebook\\.com|leetcode\\.com|"
            + "hackerrank\\.com|codechef\\.com|codeforces\\.com|kaggle\\.com|stackoverflow\\.com|"
            + "github\\.io|vercel\\.app|netlify\\.app|pages\\.dev|herokuapp\\.com|wixsite\\.com|"
            + "about\\.me|dev\\.to)(?:/[^\\s<>()\"'|]*)?");

    /**
     * A bare domain such as a personal site. The first label needs two characters
     * so that degrees written "B.Tech" or "B.Com" are not mistaken for one.
     */
    private static final Pattern BARE_DOMAIN = Pattern.compile("(?i)(?<![@\\w.-])"
            + "[a-z0-9][a-z0-9-]*[a-z0-9](?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*"
            + "\\.(?:com|co\\.in|in|org|net|io|dev|me|site|xyz|tech|app|ai|co)"
            + "(?:/[^\\s<>()\"'|]*)?(?![\\w-])");

    /** Technologies that are spelled like domains and are what a resume is read for. */
    private static final Set<String> TECHNOLOGY_NAMES =
            Set.of("asp.net", "ado.net", "vb.net", "dot.net", "socket.io", "draw.io", "fast.ai");

    /** Aadhaar-shaped: three groups of four digits. */
    private static final Pattern AADHAAR = Pattern.compile("(?<!\\d)(\\d{4})[\\s-](\\d{4})[\\s-](\\d{4})(?!\\d)");

    private static final Pattern PAN = Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b");

    private static final Pattern PASSPORT = Pattern.compile("\\b[A-PR-WY][1-9]\\d\\s?\\d{4}[1-9]\\b");

    /** Indian mobile numbers, with or without the country code. */
    private static final Pattern MOBILE = Pattern.compile("(?<![\\w+])(?:\\+?91[\\s.-]?)?(?:\\(0\\)[\\s.-]?)?"
            + "[6-9]\\d{4}[\\s.-]?\\d{5}(?!\\d)");

    /** International numbers written with a leading plus. */
    private static final Pattern INTERNATIONAL =
            Pattern.compile("(?<![\\w])\\+\\d{1,3}(?:[\\s.-]?\\(?\\d{1,5}\\)?){2,5}");

    /** Landlines with an STD code, such as 040-23456789. */
    private static final Pattern LANDLINE = Pattern.compile("(?<!\\d)\\(?0\\d{2,4}\\)?[\\s.-]?\\d{6,8}(?!\\d)");

    /** Any remaining run of nine or more digits: account, registration and ID numbers. */
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d{9,}(?!\\d)");

    private static final Pattern BIRTH_DATE = Pattern.compile("(?i)\\b(?:d\\.?\\s?o\\.?\\s?b\\b\\.?|"
            + "date\\s+of\\s+birth|birth\\s*date|born\\s+on)\\s*[:\\-–]?\\s*[^\\n|;]{1,40}");

    private static final Pattern LABELLED_ADDRESS = Pattern.compile("(?i)\\b(?:(?:permanent|present|current|"
            + "residential|postal|home|correspondence)\\s+)?address\\s*[:\\-–][^\\n|]*");

    private static final Pattern PIN_CODE = Pattern.compile("(?i)\\b(?:pin(?:\\s*code)?|pincode|postal\\s+code|"
            + "zip(?:\\s*code)?)\\s*[:\\-]?\\s*\\d{3}\\s?\\d{3}\\b");

    /** ", 500081" at the end of an Indian address. */
    private static final Pattern TRAILING_PIN = Pattern.compile(",\\s*\\d{6}(?!\\d)");

    /** Words that mark a line near the top of a resume as a street address, when it also holds a number. */
    private static final Pattern ADDRESS_WORD = Pattern.compile("(?i)\\b(?:flat|house|h\\.?\\s?no|door\\s?no|"
            + "d\\.?\\s?no|plot|street|road|rd|lane|nagar|colony|layout|sector|apartments?|apts?|block|"
            + "village|mandal|district|dist|opp|opposite|near|cross|main)\\b");

    /** How far down a resume the contact block can reasonably reach. */
    private static final int CONTACT_BLOCK_LINES = 12;

    private static final Pattern FILE_NAME = Pattern.compile("(?i)\\b[\\w.-]+\\.(?:jpe?g|png|gif|bmp|webp|heic|"
            + "pdf|docx?|odt|rtf)\\b");

    /**
     * Name parts that are also technologies or ordinary words. Replacing them on
     * their own would remove what the resume says about the student's work, so
     * they are only removed as part of the full name.
     */
    private static final Set<String> NOT_A_NAME_ON_ITS_OWN = Set.of(
            "ram", "rom", "java", "ruby", "rust", "swift", "dart", "perl", "julia", "apex", "flask", "spring",
            "rails", "node", "react", "angular", "kotlin", "scala", "elixir", "crystal", "azure", "redis",
            "kafka", "spark", "hive", "maven", "jenkins", "docker", "linux", "unity", "blender", "figma",
            "excel", "word", "power", "oracle", "tableau", "looker", "express", "django", "pandas", "numpy",
            "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
            "november", "december", "summer", "winter", "hope", "grace", "joy", "faith", "king", "prince",
            "rich", "will", "mark", "art", "sky", "star", "sun", "moon", "rose", "lily", "sunny");

    private static final Pattern REPEATED_CANDIDATE =
            Pattern.compile(Pattern.quote(CANDIDATE) + "(?:[\\s.,-]+" + Pattern.quote(CANDIDATE) + ")+");

    /** Removes identifiers from resume text. Null or blank text comes back unchanged. */
    public String redact(String text, KnownIdentity identity) {
        if (text == null || text.isBlank()) {
            return text;
        }
        KnownIdentity known = identity == null ? KnownIdentity.none() : identity;
        String result = text.replace("\r\n", "\n");

        // Order matters. Addresses and links first, so the digits and dots inside
        // them are never read as phone numbers or as domains of their own.
        result = replaceLiteral(result, known.email(), EMAIL_TOKEN);
        result = EMAIL.matcher(result).replaceAll(EMAIL_TOKEN);
        result = URL.matcher(result).replaceAll(LINK_TOKEN);
        result = PROFILE_HOST.matcher(result).replaceAll(LINK_TOKEN);
        result = replaceBareDomains(result);
        result = FILE_NAME.matcher(result).replaceAll(FILE_TOKEN);

        result = BIRTH_DATE.matcher(result).replaceAll(Matcher.quoteReplacement("Date of birth: " + BIRTH_DATE_TOKEN));
        result = LABELLED_ADDRESS.matcher(result).replaceAll(Matcher.quoteReplacement(ADDRESS_TOKEN));
        result = PIN_CODE.matcher(result).replaceAll(Matcher.quoteReplacement(ADDRESS_TOKEN));
        result = TRAILING_PIN.matcher(result).replaceAll(Matcher.quoteReplacement(", " + ADDRESS_TOKEN));
        result = redactContactBlockAddresses(result);

        result = replaceKnownPhone(result, known.phone());
        result = replaceAadhaar(result);
        result = PAN.matcher(result).replaceAll(Matcher.quoteReplacement(ID_TOKEN));
        result = PASSPORT.matcher(result).replaceAll(Matcher.quoteReplacement(ID_TOKEN));
        result = INTERNATIONAL.matcher(result).replaceAll(Matcher.quoteReplacement(PHONE_TOKEN));
        result = MOBILE.matcher(result).replaceAll(Matcher.quoteReplacement(PHONE_TOKEN));
        result = LANDLINE.matcher(result).replaceAll(Matcher.quoteReplacement(PHONE_TOKEN));
        result = LONG_NUMBER.matcher(result).replaceAll(Matcher.quoteReplacement(ID_TOKEN));

        result = replaceName(result, known.fullName());
        return REPEATED_CANDIDATE.matcher(result).replaceAll(Matcher.quoteReplacement(CANDIDATE));
    }

    private static String replaceLiteral(String text, String literal, String token) {
        if (!TextUtils.hasText(literal)) {
            return text;
        }
        return Pattern.compile(Pattern.quote(literal.strip()), Pattern.CASE_INSENSITIVE)
                .matcher(text).replaceAll(Matcher.quoteReplacement(token));
    }

    private static String replaceBareDomains(String text) {
        Matcher matcher = BARE_DOMAIN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String found = matcher.group();
            String replacement = TECHNOLOGY_NAMES.contains(found.toLowerCase(Locale.ROOT)) ? found : LINK_TOKEN;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Twelve digits in groups of four, unless all three groups are years. */
    private static String replaceAadhaar(String text) {
        Matcher matcher = AADHAAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            boolean years = isYear(matcher.group(1)) && isYear(matcher.group(2)) && isYear(matcher.group(3));
            matcher.appendReplacement(out, Matcher.quoteReplacement(years ? matcher.group() : ID_TOKEN));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean isYear(String digits) {
        return digits.startsWith("19") || digits.startsWith("20");
    }

    /** The phone number on the account, however it is spaced in the document. */
    private static String replaceKnownPhone(String text, String phone) {
        if (!TextUtils.hasText(phone)) {
            return text;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 8) {
            return text;
        }
        // The last ten digits are the number itself; a country code may or may
        // not be written in front of it.
        String local = digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
        StringBuilder spaced = new StringBuilder("(?:\\+?\\d{1,3}[\\s.-]?)?");
        for (int i = 0; i < local.length(); i++) {
            if (i > 0) {
                spaced.append("[\\s.()-]?");
            }
            spaced.append(local.charAt(i));
        }
        return Pattern.compile("(?<!\\d)" + spaced + "(?!\\d)").matcher(text)
                .replaceAll(Matcher.quoteReplacement(PHONE_TOKEN));
    }

    /** A line near the top that names a street and carries a number is an address. */
    private static String redactContactBlockAddresses(String text) {
        String[] lines = text.split("\n", -1);
        int seen = 0;
        for (int i = 0; i < lines.length && seen < CONTACT_BLOCK_LINES; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            seen++;
            if (lines[i].matches(".*\\d.*") && ADDRESS_WORD.matcher(lines[i]).find()) {
                lines[i] = ADDRESS_TOKEN;
            }
        }
        return String.join("\n", lines);
    }

    private static String replaceName(String text, String fullName) {
        if (!TextUtils.hasText(fullName)) {
            return text;
        }
        List<String> parts = new ArrayList<>();
        for (String part : fullName.strip().split("[\\s.,-]+")) {
            if (part.length() >= 2) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            return text;
        }
        String result = text;
        if (parts.size() > 1) {
            result = replaceSequence(result, parts);
            List<String> reversed = new ArrayList<>(parts);
            Collections.reverse(reversed);
            result = replaceSequence(result, reversed);
        }
        for (String part : parts) {
            if (part.length() >= 3 && !NOT_A_NAME_ON_ITS_OWN.contains(part.toLowerCase(Locale.ROOT))) {
                result = Pattern.compile("(?i)(?<![\\w])" + Pattern.quote(part) + "(?![\\w])")
                        .matcher(result).replaceAll(Matcher.quoteReplacement(CANDIDATE));
            }
        }
        return result;
    }

    private static String replaceSequence(String text, List<String> parts) {
        StringBuilder pattern = new StringBuilder("(?i)(?<![\\w])");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                pattern.append("[\\s.,-]+");
            }
            pattern.append(Pattern.quote(parts.get(i)));
        }
        pattern.append("(?![\\w])");
        return Pattern.compile(pattern.toString()).matcher(text).replaceAll(Matcher.quoteReplacement(CANDIDATE));
    }
}
