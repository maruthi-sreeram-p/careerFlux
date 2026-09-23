package com.careerflux.source.adapter.jsonld;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.careerflux.common.ApplyUrl;
import com.careerflux.common.TextUtils;
import com.careerflux.source.adapter.RawJobPosting;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Turns one JobPosting the page declared into the posting shape every adapter returns.
 *
 * <p>Deterministic and side-effect free: the same node and context always produce
 * the same posting. No HTTP, no database, no Spring, no model. A {@code url},
 * {@code sameAs} or {@code mainEntityOfPage} is read as text and never fetched,
 * and a description is data whatever it says.
 *
 * <p><b>What the page is not trusted for.</b> The company is the source's own,
 * never {@code hiringOrganization}, so a page cannot post jobs under another
 * employer's name. The application destination is only the posting's own
 * {@code url}, and only when it is on the host the page came from, so a planted
 * link cannot send a student elsewhere; {@code sameAs} is never a destination.
 *
 * <p><b>What it refuses to guess.</b> A value that the pipeline's vocabulary
 * cannot state exactly is left unset rather than approximated: several different
 * employment types, an experience level written as a phrase, a salary in a
 * currency or period the pipeline cannot express. Everything the page stated is
 * still kept verbatim in the raw payload, so nothing is lost — only unclaimed.
 */
public final class JobPostingMapper {

    /** Identifiers are stored at this length; a longer one is carried as a digest of itself. */
    static final int MAX_EXTERNAL_ID_CHARS = 200;

    /** Offices in one string, as {@code JobNormalizer} reads them: the first is the primary one. */
    private static final String OFFICE_SEPARATOR = " • ";

    /** The currencies the pipeline can state, and the symbol it reads them by. */
    private static final Map<String, String> CURRENCY_SYMBOLS =
            Map.of("INR", "₹", "USD", "$", "EUR", "€", "GBP", "£");

    /** The pay periods the pipeline can state, and the words it reads them by. */
    private static final Map<String, String> SALARY_PERIODS =
            Map.of("YEAR", "per year", "MONTH", "per month", "HOUR", "per hour");

    /** The country codes the JDK knows, so a code that stands for nothing is never renamed. */
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    /** The only work mode schema.org states outright. */
    private static final String TELECOMMUTE = "TELECOMMUTE";

    private JobPostingMapper() {
    }

    /**
     * What the adapter knows and the page does not.
     *
     * @param companyName the employer this source belongs to, from the source itself
     * @param pageUrl     where the page was fetched from; the host a destination link must match
     * @param externalId  the adapter's own stable id for this posting, or null to take the page's
     */
    public record PageContext(String companyName, String pageUrl, String externalId) {

        public PageContext {
            companyName = blankToNull(companyName);
            pageUrl = blankToNull(pageUrl);
            externalId = blankToNull(externalId);
        }
    }

    /**
     * Maps one posting, or nothing when the page did not say enough to ingest it:
     * a posting needs a title and something stable to be known by.
     */
    public static Optional<RawJobPosting> map(JobPostingNode node, PageContext context) {
        String title = blankToNull(node.title());
        if (title == null) {
            return Optional.empty();
        }
        title = title.strip();
        String externalId = externalId(node, context);
        if (externalId == null) {
            return Optional.empty();
        }

        String destination = destination(node, context);
        return Optional.of(RawJobPosting.builder(externalId)
                .requisitionId(TextUtils.truncate(JobPostingJsonLd.identifierValue(node.identifier()), 120))
                .title(title)
                // The employer this source belongs to. The page's hiringOrganization is
                // kept in the raw payload and never taken for the company's identity.
                .companyName(context.companyName())
                .locationText(location(node))
                // HTML as published; JobNormalizer strips it, as it does for every adapter.
                .descriptionHtml(node.description())
                .employmentTypeText(employmentType(node))
                .workModeText(workMode(node))
                .salaryText(salary(node))
                .applyUrl(destination)
                .sourceUrl(destination)
                .postedAt(instant(node.datePosted()))
                // Only the posting itself: no other block from the page it came from.
                .rawPayload(node.compactJson())
                .build());
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    /**
     * What this posting is known by: the adapter's own id first, since only the
     * adapter knows which part of a board's URLs is stable; then the identifier the
     * page states; then its canonical URL, and failing that the page it says it is.
     * An id too long to store is carried as a digest of itself rather than cut,
     * which would make two postings look like one.
     */
    private static String externalId(JobPostingNode node, PageContext context) {
        String chosen = context.externalId();
        if (chosen == null) {
            chosen = JobPostingJsonLd.identifierValue(node.identifier());
        }
        if (chosen == null) {
            chosen = JobPostingJsonLd.canonicalUrl(textNode(node.url()));
        }
        if (chosen == null) {
            chosen = JobPostingJsonLd.canonicalUrl(mainEntityOfPage(node));
        }
        if (chosen == null) {
            return null;
        }
        String id = chosen.strip();
        if (id.isEmpty()) {
            return null;
        }
        return id.length() <= MAX_EXTERNAL_ID_CHARS ? id : "sha256:" + TextUtils.sha256(id);
    }

    /** The page's own address, as text: a URL, or a WebPage stating its {@code @id} or {@code url}. */
    private static JsonNode mainEntityOfPage(JobPostingNode node) {
        JsonNode value = node.mainEntityOfPage();
        if (value.isTextual()) {
            return value;
        }
        JsonNode id = value.path("@id");
        return id.isTextual() ? id : value.path("url");
    }

    // ------------------------------------------------------------------
    // Where a student is sent
    // ------------------------------------------------------------------

    /**
     * The posting's own page, and only when it is on the host the page came from.
     * A URL pointing anywhere else is data the page happens to carry, not somewhere
     * to send a student, and {@code sameAs} is never a destination at all. Nothing
     * here is fetched.
     */
    private static String destination(JobPostingNode node, PageContext context) {
        String url = blankToNull(node.url());
        if (url == null || context.pageUrl() == null) {
            return null;
        }
        String candidate = url.strip();
        return sameHost(context.pageUrl(), candidate) && ApplyUrl.isValid(candidate) ? candidate : null;
    }

    private static boolean sameHost(String pageUrl, String candidate) {
        String pageHost = host(pageUrl);
        String candidateHost = host(candidate);
        return pageHost != null && pageHost.equals(candidateHost);
    }

    /** Parsed as text only. */
    private static String host(String url) {
        try {
            URI uri = new URI(url.strip());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException notAUrl) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // The posting's own words
    // ------------------------------------------------------------------

    /**
     * One employment type, stated once. Several different ones cannot be said in a
     * single field, and picking one of them would be a guess, so the field is left
     * for the pipeline to read as unspecified.
     */
    private static String employmentType(JobPostingNode node) {
        Set<String> stated = texts(node.employmentType());
        return stated.size() == 1 ? stated.iterator().next() : null;
    }

    /**
     * Remote when the page says {@code TELECOMMUTE}, which is the only work mode
     * schema.org states. Hybrid and on-site are never inferred: a page that does not
     * say leaves the work mode for the normalizer to read from what it did say.
     */
    private static String workMode(JobPostingNode node) {
        for (String value : texts(node.jobLocationType())) {
            if (value.equalsIgnoreCase(TELECOMMUTE)) {
                return "remote";
            }
        }
        return null;
    }

    /**
     * Every office the posting names, in the order it named them, written the way
     * {@code JobNormalizer} reads offices. Nothing is looked up, inferred from the
     * title or description, or filled in when the page is silent.
     */
    private static String location(JobPostingNode node) {
        List<String> offices = new ArrayList<>();
        for (JsonNode place : each(node.jobLocation())) {
            String office = office(place);
            if (office != null && !offices.contains(office)) {
                offices.add(office);
            }
        }
        return offices.isEmpty() ? null : String.join(OFFICE_SEPARATOR, offices);
    }

    private static String office(JsonNode place) {
        JsonNode address = place.path("address");
        if (address.isTextual()) {
            return blankToNull(address.asText());
        }
        if (!address.isObject()) {
            return blankToNull(place.path("name").asText(null));
        }
        String locality = text(address.path("addressLocality"));
        String region = text(address.path("addressRegion"));
        String country = country(address.path("addressCountry"));
        if (locality != null && country == null && !readAsARegion(region)) {
            // Last of "city, region" is read as the country, so a region spelled out
            // with no country to follow it would become one. It stays in the raw payload.
            region = null;
        }
        List<String> parts = new ArrayList<>();
        for (String part : new String[] {locality, region, country}) {
            if (part != null) {
                parts.add(part);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /**
     * The country as a name. A page may write it either way, and an ISO code left
     * as it stands would be read as a state: "IN" is India, not a region of one.
     */
    private static String country(JsonNode addressCountry) {
        String stated = addressCountry.isTextual()
                ? addressCountry.asText()
                : addressCountry.path("name").asText(null);
        String value = blankToNull(stated);
        if (value == null) {
            return null;
        }
        if (!value.matches("[A-Za-z]{2}")) {
            return value;
        }
        String code = value.toUpperCase(Locale.ROOT);
        if (!ISO_COUNTRIES.contains(code)) {
            // A code that stands for no country is left exactly as the page wrote it:
            // naming it something is worse than leaving it unread.
            return value;
        }
        String name = Locale.of("", code).getDisplayCountry(Locale.ENGLISH);
        return blankToNull(name) == null ? value : name;
    }

    /**
     * A salary only when the pipeline can state it exactly: a currency it reads, a
     * period it knows, and an amount. Anything else — another currency, a daily or
     * weekly rate, an amount with no period — is left unclaimed rather than
     * converted into a number a student might act on. The page's own figures stay
     * in the raw payload either way.
     */
    private static String salary(JobPostingNode node) {
        JsonNode salary = node.baseSalary();
        if (!salary.isObject()) {
            return null;
        }
        String currency = upper(salary.path("currency"));
        JsonNode amount = salary.path("value");
        if (currency == null || !amount.isObject()) {
            return null;
        }
        String symbol = CURRENCY_SYMBOLS.get(currency);
        String unit = upper(amount.path("unitText"));
        if (symbol == null || unit == null) {
            return null;
        }
        String period = SALARY_PERIODS.get(unit);
        if (period == null) {
            return null;
        }
        BigDecimal low = decimal(amount.path("minValue"));
        BigDecimal high = decimal(amount.path("maxValue"));
        BigDecimal single = decimal(amount.path("value"));
        if (low == null) {
            low = single;
        }
        if (high == null) {
            high = low;
        }
        if (low == null || high.compareTo(low) < 0) {
            return null;
        }
        return symbol + plain(low) + " - " + symbol + plain(high) + " " + period;
    }

    // ------------------------------------------------------------------
    // Values
    // ------------------------------------------------------------------

    /**
     * The shapes a date is published in, as every other adapter accepts them: an
     * offset date-time, an instant, or a plain date, which is the day it names at
     * midnight UTC. Anything else is no date.
     *
     * @see com.careerflux.source.adapter.AbstractHttpJobAdapter for the same contract
     */
    static Instant instant(String value) {
        if (blankToNull(value) == null) {
            return null;
        }
        String text = value.strip();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException notAnOffsetDateTime) {
            // Fall through to the other accepted shapes.
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notAnInstant) {
            // Fall through.
        }
        try {
            return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException notADate) {
            return null;
        }
    }

    /** The distinct non-blank strings a property states, whether it states one or several. */
    private static Set<String> texts(JsonNode value) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode element : each(value)) {
            if (element.isTextual()) {
                String text = blankToNull(element.asText());
                if (text != null) {
                    values.add(text.strip());
                }
            }
        }
        return values;
    }

    /** A property's values, whether it holds one or an array of them. */
    private static List<JsonNode> each(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            return List.of(value);
        }
        List<JsonNode> elements = new ArrayList<>();
        value.forEach(elements::add);
        return elements;
    }

    /** A two-letter code in capitals, which is what {@code JobNormalizer} reads as a state rather than a country. */
    private static boolean readAsARegion(String region) {
        return region != null && region.length() == 2 && region.equals(region.toUpperCase(Locale.ROOT))
                && region.chars().allMatch(Character::isLetter);
    }

    private static String text(JsonNode value) {
        String text = value.isTextual() ? blankToNull(value.asText()) : null;
        return text == null ? null : text.strip();
    }

    private static TextNode textNode(String value) {
        return value == null ? null : TextNode.valueOf(value);
    }

    private static String upper(JsonNode value) {
        String text = value.isTextual() ? blankToNull(value.asText()) : null;
        return text == null ? null : text.strip().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (!value.isTextual()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText().strip());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String blankToNull(String value) {
        return TextUtils.hasText(value) ? value : null;
    }
}
