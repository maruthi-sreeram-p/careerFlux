package com.careerflux.source.adapter.jsonld;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.careerflux.common.TextUtils;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Finds the schema.org JobPosting objects a page declares in its JSON-LD (Phase 2).
 *
 * <p><b>Data only.</b> The page is untrusted, and this only reads the text it is
 * handed. It has no HTTP client and makes no request: no {@code url},
 * {@code sameAs}, {@code image}, {@code mainEntityOfPage} or organization link is
 * followed, no remote {@code @context} is loaded, nothing is executed, and a
 * description is a string whatever it says.
 *
 * <p><b>Bounded while it reads.</b> The page is scanned once, left to right,
 * without building a DOM, and nothing is copied out of it but JSON-LD blocks —
 * each measured before it is copied, and left unread past
 * {@link #MAX_BLOCK_BYTES}. The JSON parser enforces its own limits on depth,
 * tokens and string, number and name length as it goes, so a hostile block fails
 * partway through rather than after it has been built. At most
 * {@link #MAX_BLOCKS} blocks and {@link #MAX_POSTINGS} postings are taken from a
 * page.
 *
 * <p><b>Forgiving per block, never per page.</b> A block that is malformed or goes
 * over a limit is skipped and counted, and the rest of the page is still read.
 * Nothing on a page makes this throw.
 *
 * <p>A JobPosting is recognised as a block's object, as an element of a
 * top-level array, or as a member of {@code @graph}, typed {@code JobPosting}
 * (alone or among other types) or by its schema.org IRI. One nested as the
 * value of another object's property is not looked for.
 */
public final class JobPostingJsonLd {

    /** JSON-LD blocks read from one page; any after these are ignored. */
    static final int MAX_BLOCKS = 32;

    /** The largest JSON-LD block read, in UTF-8 bytes; a larger one is skipped unread. */
    static final int MAX_BLOCK_BYTES = 256 * 1024;

    static final int MAX_DEPTH = 32;

    static final int MAX_TOKENS = 20_000;

    static final int MAX_STRING_CHARS = 256 * 1024;

    static final int MAX_NUMBER_DIGITS = 64;

    /** JSON-LD property names are short; a longer one is not a property anyone meant. */
    static final int MAX_NAME_CHARS = 1024;

    /** Distinct postings kept from one page. */
    static final int MAX_POSTINGS = 50;

    /** The page ceiling the fetch already enforces; nothing past it is scanned, whatever is handed in. */
    static final int MAX_PAGE_CHARS = 2 * 1024 * 1024;

    /** A script start tag longer than this is not a tag worth reading. */
    static final int MAX_TAG_CHARS = 4 * 1024;

    private static final String SCRIPT_START = "<script";
    private static final String SCRIPT_END = "</script";
    private static final String COMMENT_START = "<!--";
    private static final String COMMENT_END = "-->";
    private static final String JSON_LD = "application/ld+json";

    private static final Set<String> JOB_POSTING_TERMS = Set.of("JobPosting", "schema:JobPosting");
    private static final Set<String> JOB_POSTING_IRIS = Set.of(
            "http://schema.org/JobPosting", "https://schema.org/JobPosting");

    /** The properties whose presence makes one copy of a repeated posting richer than another. */
    private static final List<String> KNOWN_FIELDS = List.of(
            "identifier", "title", "description", "datePosted", "validThrough", "employmentType",
            "hiringOrganization", "jobLocation", "jobLocationType", "applicantLocationRequirements",
            "baseSalary", "experienceRequirements", "skills", "qualifications", "responsibilities",
            "url", "mainEntityOfPage", "sameAs");

    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_DEPTH)
                    .maxTokenCount(MAX_TOKENS)
                    .maxStringLength(MAX_STRING_CHARS)
                    .maxNumberLength(MAX_NUMBER_DIGITS)
                    .maxNameLength(MAX_NAME_CHARS)
                    .build())
            .build())
            // A block holding a second value, or text after its value, is malformed.
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private JobPostingJsonLd() {
    }

    /**
     * What one page yielded.
     *
     * @param postings            distinct postings, in the order they first appear
     * @param blocksRead          JSON-LD blocks examined, at most {@link #MAX_BLOCKS}
     * @param blocksRejected      of those, the ones skipped as malformed or over a limit
     * @param blockLimitReached   true when the page had more JSON-LD blocks than are read
     * @param duplicatesCollapsed postings folded into one already found on the page
     * @param postingLimitReached true when the page declared more postings than are kept
     */
    public record Result(List<JobPostingNode> postings, int blocksRead, int blocksRejected,
                         boolean blockLimitReached, int duplicatesCollapsed, boolean postingLimitReached) {

        public Result {
            postings = List.copyOf(postings);
        }
    }

    /** Reads every JobPosting the page declares. Never throws for anything on the page. */
    public static Result parse(String html) {
        Page page = new Page();
        if (html != null) {
            page.scan(html);
        }
        return page.result();
    }

    /** One page being read. */
    private static final class Page {

        private final Map<String, Found> postings = new LinkedHashMap<>();
        private int blocksRead;
        private int blocksRejected;
        private boolean blockLimitReached;
        private int duplicatesCollapsed;
        private boolean postingLimitReached;

        private boolean done() {
            return blockLimitReached || postingLimitReached;
        }

        void scan(String html) {
            int end = Math.min(html.length(), MAX_PAGE_CHARS);
            int pos = 0;
            while (pos < end && !done()) {
                int lt = html.indexOf('<', pos, end);
                if (lt < 0) {
                    return;
                }
                if (html.startsWith(COMMENT_START, lt)) {
                    // A commented-out block is not on the page.
                    int close = html.indexOf(COMMENT_END, lt + COMMENT_START.length(), end);
                    if (close < 0) {
                        return;
                    }
                    pos = close + COMMENT_END.length();
                    continue;
                }
                if (!isScriptStart(html, lt, end)) {
                    pos = lt + 1;
                    continue;
                }
                StartTag tag = readStartTag(html, lt + SCRIPT_START.length(), end);
                if (tag.closedAt() < 0) {
                    // No '>' within reach, so not a tag worth reading. What was
                    // scanned is left behind, so the page is still read only once.
                    pos = tag.scannedTo();
                    continue;
                }
                int contentStart = tag.closedAt() + 1;
                int contentEnd = scriptEnd(html, contentStart, end);
                if (contentEnd < 0) {
                    // An unterminated script runs to the end of the page.
                    return;
                }
                if (isJsonLd(tag.type())) {
                    block(html, contentStart, contentEnd);
                }
                pos = contentEnd + SCRIPT_END.length();
            }
        }

        private void block(String html, int start, int end) {
            if (blocksRead == MAX_BLOCKS) {
                blockLimitReached = true;
                return;
            }
            blocksRead++;
            if (!fitsBlockLimit(html, start, end)) {
                blocksRejected++;
                return;
            }
            Optional<JsonNode> root = readBlock(unwrap(html.substring(start, end)));
            if (root.isEmpty()) {
                blocksRejected++;
                return;
            }
            collect(root.get(), null);
        }

        /**
         * Walks the block's object, a top-level array's elements and {@code @graph}
         * members — and nothing else. A property's value is never searched.
         */
        private void collect(JsonNode node, JsonNode context) {
            if (done()) {
                return;
            }
            if (node.isArray()) {
                for (JsonNode element : node) {
                    collect(element, context);
                }
                return;
            }
            if (!node.isObject()) {
                return;
            }
            JsonNode effective = node.has("@context") ? node.get("@context") : context;
            if (isJobPosting(node, effective)) {
                add((ObjectNode) node);
            }
            JsonNode graph = node.get("@graph");
            if (graph != null) {
                collect(graph, effective);
            }
        }

        private void add(ObjectNode node) {
            String hash = TextUtils.sha256(canonicalJson(node));
            String key = identityKey(node, hash);
            Found existing = postings.get(key);
            if (existing != null) {
                duplicatesCollapsed++;
                if (richer(node, hash, existing)) {
                    // Keeps its place: the first copy's position, the better copy's content.
                    postings.put(key, new Found(node, hash, key));
                }
                return;
            }
            if (postings.size() == MAX_POSTINGS) {
                postingLimitReached = true;
                return;
            }
            postings.put(key, new Found(node, hash, key));
        }

        Result result() {
            List<JobPostingNode> nodes = new ArrayList<>(postings.size());
            for (Found found : postings.values()) {
                nodes.add(new JobPostingNode(found.node(), compact(found.node()), found.key()));
            }
            return new Result(nodes, blocksRead, blocksRejected, blockLimitReached,
                    duplicatesCollapsed, postingLimitReached);
        }
    }

    private record Found(ObjectNode node, String hash, String key) {
    }

    /** Where a start tag closes, its {@code type} attribute, and how far it was scanned. */
    private record StartTag(int closedAt, String type, int scannedTo) {
    }

    // ------------------------------------------------------------------
    // Reading the HTML
    // ------------------------------------------------------------------

    private static boolean isScriptStart(String html, int at, int end) {
        int after = at + SCRIPT_START.length();
        return after < end && matchesAscii(html, at, SCRIPT_START) && isTagNameEnd(html.charAt(after));
    }

    /**
     * Reads a script start tag's attributes up to its {@code >}, with quoted values
     * that may contain {@code >}. The first {@code type} attribute counts, as in a
     * browser. Never reads more than {@link #MAX_TAG_CHARS}.
     */
    private static StartTag readStartTag(String html, int from, int end) {
        int limit = Math.min(end, from + MAX_TAG_CHARS);
        String type = null;
        int i = from;
        while (i < limit) {
            char c = html.charAt(i);
            if (c == '>') {
                return new StartTag(i, type, i + 1);
            }
            if (isSpace(c) || c == '/') {
                i++;
                continue;
            }
            int nameStart = i;
            while (i < limit && !isSpace(html.charAt(i)) && html.charAt(i) != '='
                    && html.charAt(i) != '>' && html.charAt(i) != '/') {
                i++;
            }
            int nameEnd = i;
            while (i < limit && isSpace(html.charAt(i))) {
                i++;
            }
            int valueStart = -1;
            int valueEnd = -1;
            if (i < limit && html.charAt(i) == '=') {
                i++;
                while (i < limit && isSpace(html.charAt(i))) {
                    i++;
                }
                if (i < limit && (html.charAt(i) == '"' || html.charAt(i) == '\'')) {
                    int close = html.indexOf(html.charAt(i), i + 1, limit);
                    if (close < 0) {
                        return new StartTag(-1, null, limit);
                    }
                    valueStart = i + 1;
                    valueEnd = close;
                    i = close + 1;
                } else {
                    valueStart = i;
                    while (i < limit && !isSpace(html.charAt(i)) && html.charAt(i) != '>') {
                        i++;
                    }
                    valueEnd = i;
                }
            }
            if (type == null && nameEnd - nameStart == 4 && matchesAscii(html, nameStart, "type")) {
                type = valueStart < 0 ? "" : html.substring(valueStart, valueEnd);
            }
        }
        return new StartTag(-1, null, limit);
    }

    /**
     * Where a script's content ends: the first {@code </script} followed by
     * whitespace, a slash or {@code >}, as an HTML tokenizer decides. A JSON string
     * that writes it as {@code <\/script>} does not end it, which is exactly why
     * that escape exists.
     */
    static int scriptEnd(String html, int from, int end) {
        int i = from;
        while (i < end) {
            int lt = html.indexOf('<', i, end);
            if (lt < 0) {
                return -1;
            }
            int after = lt + SCRIPT_END.length();
            if (after <= end && matchesAscii(html, lt, SCRIPT_END)
                    && (after == end || isTagNameEnd(html.charAt(after)))) {
                return lt;
            }
            i = lt + 1;
        }
        return -1;
    }

    /** {@code application/ld+json}, in any case, with or without parameters. */
    static boolean isJsonLd(String type) {
        if (type == null) {
            return false;
        }
        int semicolon = type.indexOf(';');
        String base = (semicolon < 0 ? type : type.substring(0, semicolon)).strip();
        return base.length() == JSON_LD.length() && matchesAscii(base, 0, JSON_LD);
    }

    /**
     * Whether the block fits {@link #MAX_BLOCK_BYTES} in UTF-8, counted in place
     * without copying it and abandoned as soon as it does not.
     */
    static boolean fitsBlockLimit(String html, int start, int end) {
        if (end - start > MAX_BLOCK_BYTES) {
            // Every character is at least one byte.
            return false;
        }
        long bytes = 0;
        for (int i = start; i < end; i++) {
            char c = html.charAt(i);
            // Each half of a surrogate pair counts two: four bytes for the pair.
            bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isSurrogate(c) ? 2 : 3;
            if (bytes > MAX_BLOCK_BYTES) {
                return false;
            }
        }
        return true;
    }

    /** The JSON inside a block, without the HTML comment or CDATA wrapper some older pages add. */
    static String unwrap(String content) {
        String json = content.strip();
        if (json.startsWith(COMMENT_START) && json.endsWith(COMMENT_END)) {
            json = json.substring(COMMENT_START.length(), json.length() - COMMENT_END.length()).strip();
        }
        if (json.startsWith("//<![CDATA[")) {
            json = json.substring("//<![CDATA[".length());
        } else if (json.startsWith("<![CDATA[")) {
            json = json.substring("<![CDATA[".length());
        }
        if (json.endsWith("//]]>")) {
            json = json.substring(0, json.length() - "//]]>".length());
        } else if (json.endsWith("]]>")) {
            json = json.substring(0, json.length() - "]]>".length());
        }
        return json.strip();
    }

    /** One block's JSON, read within every limit; empty when it is malformed or goes over one. */
    static Optional<JsonNode> readBlock(String json) {
        if (json == null || json.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(json);
            return root == null || root.isMissingNode() ? Optional.empty() : Optional.of(root);
        } catch (JsonProcessingException | RuntimeException malformedOrOverLimit) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // Recognising a JobPosting
    // ------------------------------------------------------------------

    /**
     * Typed {@code JobPosting}, alone or among other types. A bare term counts only
     * when the node's context is schema.org or unstated, which is how pages write it;
     * a full schema.org IRI counts whatever the context says.
     */
    static boolean isJobPosting(JsonNode node, JsonNode context) {
        JsonNode type = node.get("@type");
        List<String> types = new ArrayList<>();
        if (type != null && type.isTextual()) {
            types.add(type.asText());
        } else if (type != null && type.isArray()) {
            for (JsonNode element : type) {
                if (element.isTextual()) {
                    types.add(element.asText());
                }
            }
        }
        for (String value : types) {
            if (JOB_POSTING_IRIS.contains(value)) {
                return true;
            }
        }
        for (String value : types) {
            if (JOB_POSTING_TERMS.contains(value)) {
                return isSchemaOrg(context);
            }
        }
        return false;
    }

    private static boolean isSchemaOrg(JsonNode context) {
        if (context == null || context.isNull()) {
            return true;
        }
        if (context.isTextual()) {
            return isSchemaOrgIri(context.asText());
        }
        if (context.isObject()) {
            JsonNode vocab = context.get("@vocab");
            return vocab != null && vocab.isTextual() && isSchemaOrgIri(vocab.asText());
        }
        if (context.isArray()) {
            for (JsonNode element : context) {
                if ((element.isTextual() || element.isObject()) && isSchemaOrg(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Read as text, never resolved or fetched. */
    private static boolean isSchemaOrgIri(String value) {
        try {
            URI uri = new URI(value.strip());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return scheme != null && host != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && (host.equalsIgnoreCase("schema.org") || host.equalsIgnoreCase("www.schema.org"));
        } catch (URISyntaxException notAnIri) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Telling one posting from another
    // ------------------------------------------------------------------

    /**
     * What makes a posting the same as another on its page, from its content alone.
     *
     * <p>The canonical URL leads, then the identifier, then the whole node. A URL is
     * only an identity when it names one posting, and a listing page often gives
     * every posting on it the page's own URL, so a shared URL is qualified by the
     * identifier when there is one, and otherwise by the title. Two postings that
     * differ in either are never folded together.
     */
    static String identityKey(ObjectNode node, String contentHash) {
        String url = canonicalUrl(node.get("url"));
        String identifier = identifierValue(node.get("identifier"));
        if (url != null && identifier != null) {
            return "url:" + url + "|id:" + identifier;
        }
        if (url != null) {
            String title = normalizedTitle(node.get("title"));
            return "url:" + url + (title != null ? "|title:" + title : "|json:" + contentHash);
        }
        if (identifier != null) {
            return "id:" + identifier;
        }
        return "json:" + contentHash;
    }

    /** An absolute http(s) URL, as text; the case-insensitive parts lowered, the fragment and a trailing slash dropped. */
    static String canonicalUrl(JsonNode url) {
        if (url == null || !url.isTextual()) {
            return null;
        }
        String raw = url.asText().strip();
        if (raw.isEmpty() || raw.length() > 2048) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException notAUrl) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return null;
        }
        StringBuilder canonical = new StringBuilder(scheme.toLowerCase(Locale.ROOT))
                .append("://").append(host.toLowerCase(Locale.ROOT));
        if (uri.getPort() != -1) {
            canonical.append(':').append(uri.getPort());
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        canonical.append(path);
        if (uri.getRawQuery() != null) {
            canonical.append('?').append(uri.getRawQuery());
        }
        return canonical.toString();
    }

    /** Text, or a PropertyValue's text or whole-number {@code value}. */
    static String identifierValue(JsonNode identifier) {
        if (identifier == null) {
            return null;
        }
        JsonNode value = identifier.isObject() ? identifier.get("value") : identifier;
        if (value == null) {
            return null;
        }
        if (value.isTextual()) {
            String text = value.asText().strip();
            return text.isEmpty() ? null : text;
        }
        if (identifier.isObject() && value.isIntegralNumber()) {
            return value.asText();
        }
        return null;
    }

    private static String normalizedTitle(JsonNode title) {
        if (title == null || !title.isTextual()) {
            return null;
        }
        String text = title.asText().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return text.isEmpty() ? null : text;
    }

    /**
     * Of two copies of the same posting, the one stating more; between equals, the
     * lower content hash. Either way the same copy wins whatever order they came in.
     */
    private static boolean richer(ObjectNode candidate, String candidateHash, Found existing) {
        int mine = presentFields(candidate);
        int theirs = presentFields(existing.node());
        if (mine != theirs) {
            return mine > theirs;
        }
        return candidateHash.compareTo(existing.hash()) < 0;
    }

    private static int presentFields(ObjectNode node) {
        int present = 0;
        for (String field : KNOWN_FIELDS) {
            if (node.hasNonNull(field)) {
                present++;
            }
        }
        return present;
    }

    /** The node with every object's keys sorted, so equal content has one spelling. */
    static String canonicalJson(JsonNode node) {
        return write(sorted(node));
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> fields = new TreeMap<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                fields.put(field.getKey(), field.getValue());
            }
            ObjectNode copy = JSON.createObjectNode();
            fields.forEach((name, value) -> copy.set(name, sorted(value)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JSON.createArrayNode();
            for (JsonNode element : node) {
                copy.add(sorted(element));
            }
            return copy;
        }
        return node;
    }

    private static String compact(JsonNode node) {
        return write(node);
    }

    private static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException cannotHappenForATree) {
            throw new IllegalStateException("A parsed JSON tree could not be written back", cannotHappenForATree);
        }
    }

    // ------------------------------------------------------------------
    // Characters
    // ------------------------------------------------------------------

    /** Case-insensitive for ASCII letters only, as HTML tag and attribute names are. */
    private static boolean matchesAscii(String text, int offset, String lowerCase) {
        if (offset < 0 || offset + lowerCase.length() > text.length()) {
            return false;
        }
        for (int i = 0; i < lowerCase.length(); i++) {
            char c = text.charAt(offset + i);
            char lowered = c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
            if (lowered != lowerCase.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTagNameEnd(char c) {
        return isSpace(c) || c == '>' || c == '/';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }
}
