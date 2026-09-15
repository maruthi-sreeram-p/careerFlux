package com.careerflux.ai.proposal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.careerflux.ai.dto.ExtractedResume;
import com.careerflux.ai.proposal.dto.ProposalDtos.ProposalSection;
import com.careerflux.ai.proposal.dto.ProposalDtos.ProposedItem;
import com.careerflux.candidate.domain.CandidateEducation;
import com.careerflux.candidate.domain.CandidateExperience;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.CandidateSkill;
import com.careerflux.common.TextUtils;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.skill.Skill;
import com.careerflux.skill.SkillResolver;

import org.springframework.stereotype.Component;

/**
 * Turns "here is what the resume says" into "here is what we would change".
 *
 * <p>The whole point of this class is that it decides nothing. It compares the
 * profile the student already has against the reading of their resume and
 * labels each difference; what happens next is the student's. Two rules follow
 * from that and are worth stating outright:
 *
 * <ul>
 *   <li><b>An absence is never a correction.</b> A resume that does not mention
 *       a phone number is not evidence that the student has no phone. Anything
 *       the reader did not find is recorded as MISSING and cannot be acted on,
 *       so there is no path by which a quiet CV empties a filled-in profile.
 *   <li><b>Neither value is "the right one".</b> When both sides have a value
 *       and they differ, both are shown and the item says CONFLICT. It does not
 *       say the profile is out of date, because the reader has no way to know
 *       that and saying so would push the student towards the machine's answer.
 * </ul>
 *
 * <p>Skills are resolved here rather than at approval, through the existing
 * deterministic {@link SkillResolver}. A student should not be offered "Sprnig
 * Boot" and told afterwards that it could not be added; and resolving once, at
 * the moment the proposal is written, means the slug they approve is the slug
 * that gets saved even if the dictionary changes in between.
 */
@Component
public class ProfileProposalBuilder {

    /** Same ceiling the profile itself enforces, applied before the student sees a list. */
    private static final int MAX_SKILLS = 60;

    /**
     * The widest range that is a plausible career length. Outside it the number
     * is not refused — it is shown as UNCERTAIN, because a student can tell at a
     * glance whether "203 years" is a typo and this code cannot.
     */
    private static final double MAX_PLAUSIBLE_YEARS = 60d;

    private final SkillResolver skillResolver;

    public ProfileProposalBuilder(SkillResolver skillResolver) {
        this.skillResolver = skillResolver;
    }

    public List<ProposedItem> build(CandidateProfile profile, ExtractedResume extracted) {
        List<ProposedItem> items = new ArrayList<>();
        if (extracted == null) {
            return items;
        }

        // --- who they are -------------------------------------------------
        items.add(text("fullName", ProposalSection.PERSONAL, "Full name",
                profile.getUser() == null ? null : profile.getUser().getFullName(),
                extracted.fullName()));
        items.add(text("phone", ProposalSection.PERSONAL, "Phone",
                profile.getPhone(), extracted.phone()));
        items.add(text("location", ProposalSection.PERSONAL, "Location",
                profile.getLocation(), extracted.location()));

        // --- how they describe their work ---------------------------------
        items.add(text("headline", ProposalSection.PROFESSIONAL, "Headline",
                profile.getHeadline(), extracted.headline()));
        items.add(text("summary", ProposalSection.PROFESSIONAL, "Summary",
                profile.getSummary(), extracted.summary()));
        items.add(text("primaryRole", ProposalSection.PROFESSIONAL, "Primary role",
                profile.getPrimaryRole(), extracted.primaryRole()));
        items.add(seniority(profile, extracted));
        items.add(yearsExperience(profile, extracted));

        // --- links --------------------------------------------------------
        items.add(link("linkedinUrl", "LinkedIn", profile.getLinkedinUrl(), extracted.linkedinUrl()));
        items.add(link("githubUrl", "GitHub", profile.getGithubUrl(), extracted.githubUrl()));
        items.add(link("portfolioUrl", "Portfolio", profile.getPortfolioUrl(), extracted.portfolioUrl()));

        items.addAll(skills(profile, extracted.skills()));
        items.addAll(experiences(profile, extracted.experiences()));
        items.addAll(education(profile, extracted.education()));
        items.addAll(academicInformation(profile, extracted.education()));

        return items;
    }

    // ---------------------------------------------------------------- fields

    /** A plain text field: the ordinary NEW / UNCHANGED / CONFLICT / MISSING case. */
    private static ProposedItem text(String field, ProposalSection section, String label,
                                     String current, String proposed) {
        // Truncated here rather than on the way to the database, so the value on
        // the review screen is the value that would be saved.
        String value = fit(field, trimToNull(proposed));
        ProposalItemState state = compare(current, value);
        return ProposedItem.of("field:" + field, section, label,
                state, trimToNull(current), value, true, Map.of(), noteFor(state));
    }

    /** Shortens a proposed value to what the profile column will actually hold. */
    private static String fit(String field, String value) {
        Integer max = ProfileFieldLimits.maxLengthOf(field);
        if (value == null || max == null || value.length() <= max) {
            return value;
        }
        return TextUtils.truncate(value, max);
    }

    /**
     * A link, which is a text field that can also be visibly not a link.
     *
     * <p>Models put "github.com/name", "my GitHub" and occasionally a sentence
     * in these. A value that is not a URL is still offered — the student may
     * want to fix it up — but it is flagged rather than presented as a finding.
     */
    private static ProposedItem link(String field, String label, String current, String proposed) {
        String value = fit(field, trimToNull(proposed));
        ProposalItemState state = compare(current, value);
        if (state.isDecidable() && !looksLikeUrl(value)) {
            state = ProposalItemState.UNCERTAIN;
        }
        return ProposedItem.of("field:" + field, ProposalSection.LINKS, label,
                state, trimToNull(current), value, true, Map.of(),
                state == ProposalItemState.UNCERTAIN
                        ? "This does not look like a web address. Check it before accepting."
                        : noteFor(state));
    }

    /**
     * Seniority, which is an enum and so can be read as a word that is not one
     * of the levels CareerFlux knows.
     */
    private static ProposedItem seniority(CandidateProfile profile, ExtractedResume extracted) {
        Seniority currentLevel = profile.getSeniority();
        String current = currentLevel == null || !currentLevel.isKnown() ? null : currentLevel.name();
        String proposed = trimToNull(extracted.seniority());

        ProposalItemState state = compare(current, proposed);
        String note = noteFor(state);
        if (state.isDecidable() && parseSeniority(proposed).isEmpty()) {
            state = ProposalItemState.UNCERTAIN;
            note = "\"" + proposed + "\" is not one of the levels CareerFlux uses, "
                    + "so it would be recorded as unspecified.";
        }
        return ProposedItem.of("field:seniority", ProposalSection.PROFESSIONAL, "Seniority",
                state, current, proposed, false, Map.of(), note);
    }

    /** Years of experience, which is a number and so can be read as an implausible one. */
    private static ProposedItem yearsExperience(CandidateProfile profile, ExtractedResume extracted) {
        BigDecimal currentValue = profile.getYearsExperience();
        String current = currentValue == null ? null : currentValue.stripTrailingZeros().toPlainString();
        Double proposedValue = extracted.yearsExperience();
        String proposed = proposedValue == null ? null : trimNumber(proposedValue);

        ProposalItemState state = compare(current, proposed);
        String note = noteFor(state);
        if (state.isDecidable() && proposedValue != null
                && (proposedValue < 0 || proposedValue > MAX_PLAUSIBLE_YEARS)) {
            state = ProposalItemState.UNCERTAIN;
            note = "That is not a plausible number of years. Check it before accepting.";
        }
        return ProposedItem.of("field:yearsExperience", ProposalSection.PROFESSIONAL,
                "Years of experience", state, current, proposed, true, Map.of(), note);
    }

    // ---------------------------------------------------------------- skills

    /**
     * Skills the student does not already have.
     *
     * <p>Only additions appear. A skill already on the profile is UNCHANGED and
     * a skill the resume does not mention is not touched at all — reading a CV
     * is not grounds for removing something the student put there themselves.
     */
    private List<ProposedItem> skills(CandidateProfile profile, List<String> proposedNames) {
        List<ProposedItem> items = new ArrayList<>();
        if (proposedNames == null || proposedNames.isEmpty()) {
            return items;
        }

        Set<String> held = new LinkedHashSet<>();
        for (CandidateSkill skill : profile.getSkills()) {
            if (skill.getSkill() != null) {
                held.add(skill.getSkill().getSlug());
            }
        }
        for (com.careerflux.candidate.domain.CandidateCustomSkill own : profile.getCustomSkills()) {
            held.add(PRIVATE + own.getNameKey());
        }

        Set<String> seen = new LinkedHashSet<>();
        for (String raw : proposedNames) {
            if (!TextUtils.hasText(raw)) {
                continue;
            }
            // Looked up, never created. The dictionary is shared by every
            // college, and a resume is the student's own document: resolving
            // here used to add every unknown skill on it to the dictionary the
            // moment it was uploaded, before the student had approved anything.
            Optional<Skill> resolved = skillResolver.lookup(raw);
            if (resolved.isEmpty()) {
                privateSkill(raw, held, seen, items);
                continue;
            }
            Skill skill = resolved.get();
            if (!seen.add(skill.getSlug())) {
                continue;
            }
            boolean alreadyHeld = held.contains(skill.getSlug());
            if (!alreadyHeld && held.size() + countNew(items) >= MAX_SKILLS) {
                break;
            }
            items.add(ProposedItem.of(
                    "skill:" + skill.getSlug(),
                    ProposalSection.SKILLS,
                    skill.getCanonicalName(),
                    alreadyHeld ? ProposalItemState.UNCHANGED : ProposalItemState.NEW,
                    alreadyHeld ? skill.getCanonicalName() : null,
                    skill.getCanonicalName(),
                    false,
                    Map.of("slug", skill.getSlug()),
                    alreadyHeld ? "Already on your profile." : null));
        }
        return items;
    }

    private static int countNew(List<ProposedItem> items) {
        return (int) items.stream().filter(i -> i.state() == ProposalItemState.NEW).count();
    }

    /** Keeps a private skill's key apart from any dictionary slug, which cannot contain a colon. */
    private static final String PRIVATE = "private:";

    /**
     * A skill the resume names that the shared dictionary does not know.
     *
     * <p>Offered as the student's own. If they accept it, it is kept on their
     * profile only — never added to the dictionary, never shown to staff and
     * never matched — which the note says, so they are not left to assume
     * otherwise.
     */
    private static void privateSkill(String raw, Set<String> held, Set<String> seen,
                                     List<ProposedItem> items) {
        String name = SkillResolver.skillName(raw);
        if (name == null) {
            return;
        }
        String key = TextUtils.skillSlug(name);
        if (key.isEmpty() || !seen.add(PRIVATE + key)) {
            return;
        }
        boolean alreadyHeld = held.contains(PRIVATE + key);
        if (!alreadyHeld && held.size() + countNew(items) >= MAX_SKILLS) {
            return;
        }
        items.add(ProposedItem.of(
                "skill:" + PRIVATE + key,
                ProposalSection.SKILLS,
                name,
                alreadyHeld ? ProposalItemState.UNCHANGED : ProposalItemState.NEW,
                alreadyHeld ? name : null,
                name,
                false,
                Map.of("name", name),
                alreadyHeld
                        ? "Already on your profile."
                        : "Kept on your profile only. CareerFlux does not recognise this skill, "
                                + "so it is not used for matching."));
    }

    // ------------------------------------------------------------ experience

    /**
     * Jobs read from the resume.
     *
     * <p>Each is offered on its own so a student can take the two that are right
     * and leave the one the parser invented out of a bullet list. An entry that
     * matches something already on the profile — same employer, same title — is
     * UNCHANGED rather than a duplicate waiting to be accepted.
     */
    private static List<ProposedItem> experiences(CandidateProfile profile,
                                                  List<ExtractedResume.ExtractedExperience> proposed) {
        List<ProposedItem> items = new ArrayList<>();
        if (proposed == null || proposed.isEmpty()) {
            return items;
        }
        Set<String> held = new LinkedHashSet<>();
        for (CandidateExperience existing : profile.getExperiences()) {
            held.add(key(existing.getCompanyName(), existing.getTitle()));
        }

        int index = 0;
        for (ExtractedResume.ExtractedExperience item : proposed) {
            if (!TextUtils.hasText(item.companyName()) && !TextUtils.hasText(item.title())) {
                continue;
            }
            String company = orUnspecified(item.companyName());
            String title = orUnspecified(item.title());
            boolean alreadyHeld = held.contains(key(company, title));

            Map<String, String> data = new LinkedHashMap<>();
            put(data, "companyName", company);
            put(data, "title", title);
            put(data, "location", item.location());
            put(data, "startDate", item.startDate());
            put(data, "endDate", item.endDate());
            put(data, "current", item.current() == null ? null : String.valueOf(item.current()));
            put(data, "description", item.description());

            items.add(ProposedItem.of(
                    "experience:" + index++,
                    ProposalSection.EXPERIENCE,
                    title + " at " + company,
                    alreadyHeld ? ProposalItemState.UNCHANGED : ProposalItemState.NEW,
                    alreadyHeld ? title + " at " + company : null,
                    describeExperience(item, company, title),
                    false,
                    data,
                    alreadyHeld ? "Already on your profile." : null));
        }
        return items;
    }

    private static String describeExperience(ExtractedResume.ExtractedExperience item,
                                             String company, String title) {
        StringBuilder text = new StringBuilder(title).append(" at ").append(company);
        if (TextUtils.hasText(item.startDate()) || TextUtils.hasText(item.endDate())) {
            text.append(" (")
                    .append(TextUtils.hasText(item.startDate()) ? item.startDate().strip() : "?")
                    .append(" – ")
                    .append(Boolean.TRUE.equals(item.current()) ? "present"
                            : TextUtils.hasText(item.endDate()) ? item.endDate().strip() : "?")
                    .append(")");
        }
        return text.toString();
    }

    // ------------------------------------------------------------- education

    private static List<ProposedItem> education(CandidateProfile profile,
                                                List<ExtractedResume.ExtractedEducation> proposed) {
        List<ProposedItem> items = new ArrayList<>();
        if (proposed == null || proposed.isEmpty()) {
            return items;
        }
        Set<String> held = new LinkedHashSet<>();
        for (CandidateEducation existing : profile.getEducation()) {
            held.add(key(existing.getInstitution(), existing.getDegree()));
        }

        int index = 0;
        for (ExtractedResume.ExtractedEducation item : proposed) {
            if (!TextUtils.hasText(item.institution())) {
                continue;
            }
            String institution = item.institution().strip();
            String degree = trimToNull(item.degree());
            boolean alreadyHeld = held.contains(key(institution, degree));

            Map<String, String> data = new LinkedHashMap<>();
            put(data, "institution", institution);
            put(data, "degree", degree);
            put(data, "fieldOfStudy", item.fieldOfStudy());
            put(data, "startYear", item.startYear() == null ? null : String.valueOf(item.startYear()));
            put(data, "endYear", item.endYear() == null ? null : String.valueOf(item.endYear()));
            // The grade travels with the entry as free text, exactly as written.
            // It is never read as a CGPA; see academicInformation below.
            put(data, "grade", item.grade());

            items.add(ProposedItem.of(
                    "education:" + index++,
                    ProposalSection.EDUCATION,
                    degree == null ? institution : degree + ", " + institution,
                    alreadyHeld ? ProposalItemState.UNCHANGED : ProposalItemState.NEW,
                    alreadyHeld ? (degree == null ? institution : degree + ", " + institution) : null,
                    describeEducation(institution, degree, item),
                    false,
                    data,
                    alreadyHeld ? "Already on your profile." : null));
        }
        return items;
    }

    private static String describeEducation(String institution, String degree,
                                            ExtractedResume.ExtractedEducation item) {
        StringBuilder text = new StringBuilder();
        text.append(degree == null ? institution : degree + ", " + institution);
        if (item.endYear() != null) {
            text.append(" (").append(item.endYear()).append(")");
        }
        return text.toString();
    }

    // -------------------------------------------------------------- academic

    /**
     * The grades the resume mentions, shown and never applied.
     *
     * <p>A CGPA on CareerFlux is an institutional record. {@link
     * com.careerflux.candidate.domain.CgpaSource} is explicit that a figure the
     * student supplies is shown back to them and never counts towards
     * eligibility, and {@code Cgpa} is explicit that nothing in the system
     * parses or converts a free-text grade into one. A language model reading a
     * PDF is further from an institutional record than either.
     *
     * <p>So this produces read-only lines. If the resume says 9.1 and the
     * college recorded 8.5, the student sees both and the record stays 8.5.
     * Hiding what was found would be the alternative, and it would be worse:
     * the student would have no idea their CV disagrees with their transcript.
     */
    private static List<ProposedItem> academicInformation(
            CandidateProfile profile, List<ExtractedResume.ExtractedEducation> proposed) {

        List<ProposedItem> items = new ArrayList<>();
        if (proposed == null || proposed.isEmpty()) {
            return items;
        }
        String recorded = profile.getCgpa() == null ? null
                : profile.getCgpa().stripTrailingZeros().toPlainString()
                        + " / " + profile.getCgpaScale().stripTrailingZeros().toPlainString();

        int index = 0;
        for (ExtractedResume.ExtractedEducation item : proposed) {
            String grade = trimToNull(item.grade());
            if (grade == null) {
                continue;
            }
            // CONFLICT when there is a record to disagree with, INFORMATION_FOUND
            // when there is not. Neither is actionable; the difference is only
            // what the student is being told.
            ProposalItemState state = recorded == null
                    ? ProposalItemState.INFORMATION_FOUND
                    : ProposalItemState.CONFLICT;

            items.add(ProposedItem.readOnly(
                    "academic:" + index++,
                    ProposalSection.ACADEMIC,
                    "Grade on your resume"
                            + (TextUtils.hasText(item.institution())
                                    ? " (" + item.institution().strip() + ")" : ""),
                    state,
                    recorded,
                    grade,
                    recorded == null
                            ? "Your CGPA is recorded by your college, not read from your resume. "
                                    + "This is shown for information and changes nothing."
                            : "Your recorded CGPA is unchanged. If the figure on your resume is "
                                    + "the correct one, ask your placement office to update it."));
        }
        return items;
    }

    // ------------------------------------------------------------- comparison

    private static ProposalItemState compare(String current, String proposed) {
        String proposedValue = trimToNull(proposed);
        if (proposedValue == null) {
            return ProposalItemState.MISSING;
        }
        String currentValue = trimToNull(current);
        if (currentValue == null) {
            return ProposalItemState.NEW;
        }
        return equivalent(currentValue, proposedValue)
                ? ProposalItemState.UNCHANGED
                : ProposalItemState.CONFLICT;
    }

    /**
     * Whether two values say the same thing.
     *
     * <p>Case and surrounding space are not a difference worth asking a student
     * about. Anything more clever than that — normalising phone numbers,
     * collapsing "Bengaluru" and "Bangalore" — would be the code deciding that
     * two things are the same, which is the judgement this whole workflow exists
     * to leave with the person.
     */
    private static boolean equivalent(String a, String b) {
        return a.strip().equalsIgnoreCase(b.strip());
    }

    private static String noteFor(ProposalItemState state) {
        return switch (state) {
            case MISSING -> "Your resume did not mention this. Nothing on your profile changes.";
            case UNCHANGED -> "Your profile already says this.";
            case CONFLICT -> "These differ. Nothing changes unless you choose.";
            default -> null;
        };
    }

    static Optional<Seniority> parseSeniority(String value) {
        if (!TextUtils.hasText(value)) {
            return Optional.empty();
        }
        try {
            Seniority parsed = Seniority.valueOf(value.strip().toUpperCase(Locale.ROOT));
            return parsed.isKnown() ? Optional.of(parsed) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean looksLikeUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.strip().toLowerCase(Locale.ROOT);
        if (lower.contains(" ")) {
            return false;
        }
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.contains(".");
    }

    private static String trimNumber(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String key(String first, String second) {
        return (first == null ? "" : first.strip().toLowerCase(Locale.ROOT))
                + " " + (second == null ? "" : second.strip().toLowerCase(Locale.ROOT));
    }

    private static void put(Map<String, String> data, String key, String value) {
        String trimmed = trimToNull(value);
        if (trimmed != null) {
            data.put(key, trimmed);
        }
    }

    private static String orUnspecified(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? "Unspecified" : trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
