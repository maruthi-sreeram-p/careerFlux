package com.careerflux.source.adapter.jsonld;

import java.util.EnumSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One JobPosting a page declared in its JSON-LD, as found — not yet mapped to anything.
 *
 * <p>Everything here is data from an untrusted page. Nothing in it is followed:
 * a {@code url}, {@code sameAs} or {@code image} is a string to read, never a
 * request to make, and a description is text, whatever it says.
 *
 * <p>Each accessor returns the property only when it has a JSON type schema.org
 * allows for it, and otherwise returns nothing: a {@code "title": ["bad"]} is no
 * title, an {@code "identifier": 123} no identifier. Where an array is allowed,
 * elements of the wrong type are dropped. Deciding what a value <em>means</em> is
 * left to the mapper.
 *
 * <p>Immutable: the node is copied in, and every structured value is copied out.
 */
public final class JobPostingNode {

    private static final Set<JsonNodeType> NONE = EnumSet.noneOf(JsonNodeType.class);
    private static final Set<JsonNodeType> TEXT = EnumSet.of(JsonNodeType.STRING);
    private static final Set<JsonNodeType> OBJECT = EnumSet.of(JsonNodeType.OBJECT);
    private static final Set<JsonNodeType> TEXT_OR_OBJECT = EnumSet.of(JsonNodeType.STRING, JsonNodeType.OBJECT);
    private static final Set<JsonNodeType> OBJECT_OR_NUMBER = EnumSet.of(JsonNodeType.OBJECT, JsonNodeType.NUMBER);

    private final ObjectNode node;
    private final String compactJson;
    private final String identityKey;

    JobPostingNode(ObjectNode node, String compactJson, String identityKey) {
        this.node = node.deepCopy();
        this.compactJson = compactJson;
        this.identityKey = identityKey;
    }

    public String title() {
        return text("title");
    }

    public String description() {
        return text("description");
    }

    public String url() {
        return text("url");
    }

    public String datePosted() {
        return text("datePosted");
    }

    public String validThrough() {
        return text("validThrough");
    }

    /** Text, or a PropertyValue object. */
    public JsonNode identifier() {
        return field("identifier", TEXT_OR_OBJECT, NONE);
    }

    /** Text, or an array of text. */
    public JsonNode employmentType() {
        return field("employmentType", TEXT, TEXT);
    }

    /** An Organization object, or text. */
    public JsonNode hiringOrganization() {
        return field("hiringOrganization", TEXT_OR_OBJECT, NONE);
    }

    /** A Place object, or an array of them. */
    public JsonNode jobLocation() {
        return field("jobLocation", OBJECT, OBJECT);
    }

    /** Text, or an array of text. */
    public JsonNode jobLocationType() {
        return field("jobLocationType", TEXT, TEXT);
    }

    /** An AdministrativeArea object, or an array of them. */
    public JsonNode applicantLocationRequirements() {
        return field("applicantLocationRequirements", OBJECT, OBJECT);
    }

    /** A MonetaryAmount object, or a number. */
    public JsonNode baseSalary() {
        return field("baseSalary", OBJECT_OR_NUMBER, NONE);
    }

    /** Text or an OccupationalExperienceRequirements object, or an array of either. */
    public JsonNode experienceRequirements() {
        return field("experienceRequirements", TEXT_OR_OBJECT, TEXT_OR_OBJECT);
    }

    /** Text or a DefinedTerm object, or an array of either. */
    public JsonNode skills() {
        return field("skills", TEXT_OR_OBJECT, TEXT_OR_OBJECT);
    }

    /** Text or an EducationalOccupationalCredential object, or an array of either. */
    public JsonNode qualifications() {
        return field("qualifications", TEXT_OR_OBJECT, TEXT_OR_OBJECT);
    }

    /** Text, or an array of text. */
    public JsonNode responsibilities() {
        return field("responsibilities", TEXT, TEXT);
    }

    /** A URL as text, or a WebPage object. */
    public JsonNode mainEntityOfPage() {
        return field("mainEntityOfPage", TEXT_OR_OBJECT, NONE);
    }

    /** A URL as text, or an array of them. */
    public JsonNode sameAs() {
        return field("sameAs", TEXT, TEXT);
    }

    /** The node exactly as the page declared it, as compact JSON. */
    public String compactJson() {
        return compactJson;
    }

    /** What made this posting distinct from the others on its page. */
    String identityKey() {
        return identityKey;
    }

    private String text(String name) {
        JsonNode value = node.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private JsonNode field(String name, Set<JsonNodeType> allowed, Set<JsonNodeType> allowedInArray) {
        JsonNode value = node.get(name);
        if (value == null) {
            return MissingNode.getInstance();
        }
        if (value.isArray()) {
            if (allowedInArray.isEmpty()) {
                return MissingNode.getInstance();
            }
            ArrayNode kept = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : value) {
                if (allowedInArray.contains(element.getNodeType())) {
                    kept.add(element.deepCopy());
                }
            }
            return kept.isEmpty() ? MissingNode.getInstance() : kept;
        }
        return allowed.contains(value.getNodeType()) ? value.deepCopy() : MissingNode.getInstance();
    }
}
