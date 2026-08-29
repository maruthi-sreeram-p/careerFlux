package com.careerflux.ingestion.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.careerflux.common.TextUtils;
import com.careerflux.job.domain.SkillRequirement;

import org.springframework.stereotype.Component;

/**
 * Decides whether a skill a posting mentions is demanded, preferred, or merely
 * mentioned.
 *
 * <p>This exists because the previous behaviour wrote every dictionary match as
 * {@code REQUIRED}. The corpus showed the result exactly: 7,609 job-skill rows,
 * all REQUIRED, not one PREFERRED. "You will collaborate with our Python team"
 * made Python a hard requirement, and matching then punished every candidate who
 * did not have it.
 *
 * <p>Classification reads <em>where</em> a skill appears, in two passes:
 *
 * <ol>
 *   <li><b>Section.</b> Postings that use headings say plainly which list a
 *       skill belongs to. A skill under "Requirements" is required; one under
 *       "Nice to have" is preferred. This is the confident path, and it is
 *       available for roughly a third of real postings.
 *   <li><b>Sentence.</b> Everything else falls back to the language immediately
 *       around the mention — "must have", "at least 3 years of", "a plus". This
 *       is weaker but still evidence, and far better than assuming.
 * </ol>
 *
 * <p>Anything with neither signal is {@link SkillRequirement#OPTIONAL}. The
 * default direction matters: under-demanding costs a candidate a few points of
 * compatibility, while over-demanding invents an obligation the employer never
 * stated and can push a good match below the visibility floor.
 *
 * <p>Deterministic and free. No model call, so it can be re-run over the whole
 * corpus as often as needed.
 */
@Component
public class SkillRequirementClassifier {

    /** Separates list items in a canonicalised block, so a cue cannot cross one. */
    private static final char ITEM_BREAK = '\n';

    /** Bullet glyphs that begin a list item rather than a heading. */
    private static final char BULLET = '•';
    private static final char MIDDLE_DOT = '·';

    /** How far around a mention counts as "the language around it". */
    private static final int SENTENCE_WINDOW = 240;

    /**
     * Headings that introduce a list of things the candidate must have.
     *
     * <p>{@code required} is listed separately from {@code requirements?} because
     * they are different words: a bare "Required:" heading, which 49 postings use,
     * matched neither. Everything beneath it fell through to the sentence path and
     * usually ended up OPTIONAL.
     */
    private static final Pattern HEADING_REQUIRED = Pattern.compile(
            "(?:^|\\b)(?:requirements?|required|qualifications?"
                    + "|what (?:you|we).{0,12}(?:need|looking for)"
                    + "|must[- ]haves?|essential|minimum qualifications?|basic qualifications?"
                    + "|who you are|skills? (?:and|&) experience|required skills?)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HEADING_PREFERRED = Pattern.compile(
            "(?:^|\\b)(?:nice[- ]to[- ]haves?|preferred(?: qualifications?| skills?)?|bonus(?: points?)?"
                    + "|good to have|desirable|pluses|additional skills?|it.{0,3}s a plus"
                    + "|even better|extra credit)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Language that states a skill is needed.
     *
     * <p>The additions in the second group are the ones the corpus audit showed
     * were missing. "Experience with X" is how 1,443 of 1,947 real postings
     * phrase a requirement — by far the commonest form — while "must have",
     * which the first group covers, appears in 69. Omitting it sent the majority
     * of genuinely required skills to OPTIONAL.
     *
     * <p>Preference language is still checked first and wins ties, so
     * "familiarity with Kafka is a plus" stays preferred despite containing
     * "with".
     */
    private static final Pattern PHRASE_REQUIRED = Pattern.compile(
            "\\b(?:must have|must possess|required|require[sd]?|mandatory|essential"
                    + "|minimum(?: of)?|at least \\d+\\+? years?|\\d+\\+? years? of (?:hands[- ]on )?experience"
                    + "|strong (?:experience|proficiency|background|command)|proficiency in"
                    + "|demonstrated experience|solid (?:experience|understanding)"
                    // Added after the corpus audit; see the note above.
                    + "|experience (?:with|in|using|building|working with)"
                    + "|hands[- ]on (?:experience|knowledge)|expertise (?:in|with)"
                    + "|working knowledge of|background in|proficient (?:in|with)"
                    + "|skilled (?:in|with)|competency (?:in|with)"
                    // Final pass. "Experience working on" alone appears in 338
                    // postings and "deep understanding of" in 313; skills sitting
                    // in those bullets were falling through to OPTIONAL.
                    + "|experience working (?:on|in|with)"
                    + "|deep (?:understanding|knowledge) of"
                    + "|ability to (?:write|build|develop)"
                    + "|comfortable (?:with|in)"
                    + "|well[- ]versed (?:in|with)"
                    // Soft skills are asked for in a different register. "Strong
                    // communication skills" appears in 187 postings and
                    // "excellent communication" in 217, nearly always as a
                    // requirements bullet. The span is 60 rather than 30 because
                    // over half the real cases are longer than 30 characters:
                    // "excellent problem solving and communication skills" needs 33.
                    + "|(?:strong|excellent|exceptional|outstanding)[\\w ,-]{0,60}skills"
                    + "|(?:strong|excellent|exceptional) (?:communication|collaboration"
                    + "|leadership|interpersonal|analytical|problem[- ]solving))\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PHRASE_PREFERRED = Pattern.compile(
            "\\b(?:nice to have|preferred|preferable|a plus|bonus|ideally|desirable"
                    + "|would be great|familiarity with|exposure to|good to have"
                    + "|advantageous|we.{0,3}d love)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Language that describes working alongside a technology rather than using
     * it. These are the mentions that were becoming requirements.
     */
    private static final Pattern PHRASE_CONTEXTUAL = Pattern.compile(
            "\\b(?:work (?:with|alongside)|collaborate with|partner with|interface with"
                    + "|our .{0,20}team|built (?:on|with)|powered by|our (?:stack|platform) (?:is|uses)"
                    + "|migrating (?:from|off)|legacy)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Classifies every named skill against the posting text.
     *
     * @param skillNames canonical skill names already found in the text
     * @param text       the description, ideally with any separate requirements
     *                   section appended
     * @return one requirement per skill, in the order given
     */
    public Map<String, SkillRequirement> classify(List<String> skillNames, String text) {
        Map<String, SkillRequirement> result = new LinkedHashMap<>();
        if (skillNames == null || skillNames.isEmpty()) {
            return result;
        }
        if (!TextUtils.hasText(text)) {
            for (String name : skillNames) {
                result.put(name, SkillRequirement.OPTIONAL);
            }
            return result;
        }

        List<Block> blocks = segment(text);
        for (String name : skillNames) {
            result.put(name, classifyOne(name, blocks));
        }
        return result;
    }

    /**
     * The strongest evidence anywhere in the document wins.
     *
     * <p>A skill listed under Requirements and mentioned again in a paragraph
     * about the team is required; the second mention does not soften the first.
     */
    private SkillRequirement classifyOne(String skillName, List<Block> blocks) {
        return explainOne(skillName, blocks).tier();
    }

    /**
     * Classifies one skill and records which cue decided it.
     *
     * <p>The cue is carried purely so an audit can show its working. Nothing in
     * scoring reads it, and it has no effect on the tier — {@link #classifyOne}
     * is this method with the explanation discarded, so the two can never
     * disagree about what a posting says.
     */
    private Explanation explainOne(String skillName, List<Block> blocks) {
        String needle = TextUtils.canonicalize(skillName);
        if (needle.isEmpty()) {
            return new Explanation(skillName, SkillRequirement.OPTIONAL, Cue.NOT_MENTIONED, null);
        }

        Explanation best = null;
        for (Block block : blocks) {
            int from = 0;
            while (true) {
                int at = indexOfWord(block.canonical(), needle, from);
                if (at < 0) {
                    break;
                }
                Explanation found = explainMention(skillName, block, at, needle.length());
                best = stronger(best, found);
                if (best.tier() == SkillRequirement.REQUIRED) {
                    return best;
                }
                from = at + needle.length();
            }
        }
        return best == null
                ? new Explanation(skillName, SkillRequirement.OPTIONAL, Cue.NOT_MENTIONED, null)
                : best;
    }

    /** Classifies every skill and reports the cue behind each decision. */
    public List<Explanation> explain(List<String> skillNames, String text) {
        if (skillNames == null || skillNames.isEmpty() || !TextUtils.hasText(text)) {
            return List.of();
        }
        List<Block> blocks = segment(text);
        List<Explanation> explanations = new ArrayList<>();
        for (String name : skillNames) {
            explanations.add(explainOne(name, blocks));
        }
        return explanations;
    }

    private Explanation explainMention(String skillName, Block block, int at, int length) {
        // The section heading is the strongest signal a posting gives, because
        // the employer put the skill in that list deliberately.
        if (block.context() != null) {
            Cue cue = block.context() == SkillRequirement.REQUIRED
                    ? Cue.HEADING_REQUIRED : Cue.HEADING_PREFERRED;
            return new Explanation(skillName, block.context(), cue, block.heading());
        }
        String around = window(block.canonical(), at, length);

        java.util.regex.Matcher required = PHRASE_REQUIRED.matcher(around);
        java.util.regex.Matcher preferred = PHRASE_PREFERRED.matcher(around);
        boolean hasRequired = required.find();
        boolean hasPreferred = preferred.find();

        // Preference language wins a tie: "3 years of backend work required.
        // Kafka would be a bonus" must not make Kafka required.
        if (hasRequired && !hasPreferred) {
            return new Explanation(skillName, SkillRequirement.REQUIRED,
                    Cue.PHRASE_REQUIRED, required.group());
        }
        if (hasPreferred) {
            return new Explanation(skillName, SkillRequirement.PREFERRED,
                    Cue.PHRASE_PREFERRED, preferred.group());
        }
        java.util.regex.Matcher contextual = PHRASE_CONTEXTUAL.matcher(around);
        if (contextual.find()) {
            return new Explanation(skillName, SkillRequirement.OPTIONAL,
                    Cue.CONTEXTUAL, contextual.group());
        }
        return new Explanation(skillName, SkillRequirement.OPTIONAL, Cue.NO_CUE, null);
    }

    /** The text immediately around a mention, clipped to sentence boundaries where possible. */
    private String window(String text, int at, int length) {
        int start = Math.max(0, at - SENTENCE_WINDOW / 2);
        int end = Math.min(text.length(), at + length + SENTENCE_WINDOW / 2);
        String slice = text.substring(start, end);

        // Trim back to the nearest sentence break so a neighbouring bullet's
        // language does not leak into this one.
        int breakBefore = lastBreakBefore(slice, at - start);
        int breakAfter = firstBreakAfter(slice, at - start + length);
        return slice.substring(breakBefore, breakAfter);
    }

    private int lastBreakBefore(String slice, int position) {
        for (int i = Math.min(position, slice.length()) - 1; i > 0; i--) {
            if (slice.charAt(i) == '\n' || slice.charAt(i) == ';') {
                return i + 1;
            }
        }
        return 0;
    }

    private int firstBreakAfter(String slice, int position) {
        for (int i = Math.max(0, position); i < slice.length(); i++) {
            if (slice.charAt(i) == '\n' || slice.charAt(i) == ';') {
                return i;
            }
        }
        return slice.length();
    }

    /**
     * Splits the posting into blocks, each tagged by the heading above it.
     *
     * <p>A heading is a short line that either ends in a colon or reads as a
     * title. Postings that use none produce a single untagged block, which sends
     * every skill down the sentence path.
     */
    List<Block> segment(String text) {
        List<Block> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        SkillRequirement context = null;
        String headingText = null;

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            SkillRequirement heading = headingContext(line);
            if (heading != null || isNeutralHeading(line)) {
                if (current.length() > 0) {
                    blocks.add(new Block(canonicalizeKeepingItems(current.toString()), context, headingText));
                    current.setLength(0);
                }
                context = heading;
                headingText = line;
                continue;
            }
            current.append(rawLine).append('\n');
        }
        if (current.length() > 0) {
            blocks.add(new Block(canonicalizeKeepingItems(current.toString()), context, headingText));
        }
        if (blocks.isEmpty()) {
            blocks.add(new Block(canonicalizeKeepingItems(text), null, null));
        }
        return blocks;
    }

    /**
     * Canonicalises a block while keeping its item boundaries.
     *
     * <p>{@link TextUtils#canonicalize} turns every newline into a space, which
     * made the sentence clipping in {@link #window} dead code: it searched for
     * line breaks in a string that could no longer contain any. The context
     * window therefore ran straight across bullet boundaries, and a cue in one
     * item was applied to a skill in the next. That is how "experience with
     * cloud platforms like AWS" picked up "Proficiency in" from the following
     * bullet and became a hard requirement.
     *
     * <p>Each item is canonicalised on its own and rejoined with a newline, so
     * the boundaries survive into the text the window actually reads. Bullet
     * glyphs are treated as separators too, because not every posting puts one
     * item per line.
     */
    private String canonicalizeKeepingItems(String text) {
        StringBuilder canonical = new StringBuilder();
        for (String item : text.split("[\r\n" + BULLET + MIDDLE_DOT + "]+")) {
            String piece = TextUtils.canonicalize(item);
            if (piece.isEmpty()) {
                continue;
            }
            if (canonical.length() > 0) {
                canonical.append(ITEM_BREAK);
            }
            canonical.append(piece);
        }
        return canonical.toString();
    }

    private SkillRequirement headingContext(String line) {
        if (!looksLikeHeading(line)) {
            return null;
        }
        // Preferred is tested first: "Preferred qualifications" contains
        // "qualifications", which would otherwise read as a requirement heading.
        if (HEADING_PREFERRED.matcher(line).find()) {
            return SkillRequirement.PREFERRED;
        }
        if (HEADING_REQUIRED.matcher(line).find()) {
            return SkillRequirement.REQUIRED;
        }
        return null;
    }

    /**
     * A heading that carries no requirement meaning — "Responsibilities", "About
     * us" — still ends the previous section, so its content does not inherit the
     * last requirement heading.
     */
    private boolean isNeutralHeading(String line) {
        return looksLikeHeading(line) && headingContext(line) == null;
    }

    /**
     * Whether a line reads as a section heading rather than prose or a bullet.
     *
     * <p>Real postings write headings in sentence case — "Nice to have", "What
     * you will do" — not title case. Requiring most words to be capitalised
     * found "Requirements" and missed "Nice to have" entirely, which left every
     * preferred skill in a real posting still classified as required.
     *
     * <p>The test is therefore shape-based: short, not a bullet, and not ending
     * like a sentence.
     */
    private boolean looksLikeHeading(String line) {
        if (line.isEmpty() || line.length() > 60) {
            return false;
        }
        char first = line.charAt(0);
        if (first == BULLET || first == MIDDLE_DOT || first == '-' || first == '*') {
            return false;
        }
        if (line.endsWith(":")) {
            return true;
        }
        return line.split("\\s+").length <= 8
                && !line.endsWith(".")
                && !line.endsWith(",")
                && !line.endsWith(";")
                && Character.isLetter(first);
    }


    /**
     * Word-boundary search over canonicalised text.
     *
     * <p>Padding the needle with spaces looks simpler and is wrong: canonicalised
     * text keeps full stops, so "Docker." at the end of a sentence never matches
     * " docker ". Boundaries are therefore tested against the characters a skill
     * name can actually contain, which lets a trailing stop end a word while
     * still matching names that contain one, like ".NET" and "Node.js".
     */
    private int indexOfWord(String haystack, String needle, int from) {
        int at = haystack.indexOf(needle, Math.max(0, from));
        while (at >= 0) {
            boolean leftClear = at == 0 || !isNameChar(haystack.charAt(at - 1));
            int after = at + needle.length();
            boolean rightClear = after >= haystack.length() || !isNameChar(haystack.charAt(after));
            if (leftClear && rightClear) {
                return at;
            }
            at = haystack.indexOf(needle, at + 1);
        }
        return -1;
    }

    /** Characters that can sit inside a skill name, so cannot end one. */
    private boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '+' || c == '#';
    }

    private Explanation stronger(Explanation left, Explanation right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.tier().ordinal() <= right.tier().ordinal() ? left : right;
    }

    /** What decided a classification. Carried for auditing only. */
    public enum Cue {
        HEADING_REQUIRED,
        HEADING_PREFERRED,
        PHRASE_REQUIRED,
        PHRASE_PREFERRED,
        CONTEXTUAL,
        NO_CUE,
        NOT_MENTIONED
    }

    /** One classification with its reason, for audit reporting. */
    public record Explanation(String skill, SkillRequirement tier, Cue cue, String evidence) {
    }

    /** One segment of a posting, with the requirement meaning of its heading. */
    record Block(String canonical, SkillRequirement context, String heading) {
    }
}
