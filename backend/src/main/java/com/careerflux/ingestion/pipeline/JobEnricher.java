package com.careerflux.ingestion.pipeline;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.careerflux.ai.AiClient;
import com.careerflux.ai.dto.ExtractedJobAttributes;
import com.careerflux.common.TextUtils;
import com.careerflux.common.error.AiUnavailableException;
import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.job.domain.EnrichmentStatus;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobSkill;
import com.careerflux.job.domain.SkillExtractionMethod;
import com.careerflux.job.domain.SkillRequirement;
import com.careerflux.skill.Skill;
import com.careerflux.skill.SkillRepository;
import com.careerflux.skill.SkillResolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Extracts structured attributes and required skills from a job description.
 *
 * <p>Two extraction paths, in this order:
 * <ol>
 *   <li><b>Dictionary matching</b> against the canonical skill table. Always
 *       runs, always cheap, always available.</li>
 *   <li><b>Model extraction</b>, when AI is configured. Used to catch skills the
 *       dictionary has not seen and to read requirement phrasing the regexes
 *       miss.</li>
 * </ol>
 *
 * <p>Enrichment only ever fills gaps. A field the source stated, or that
 * {@link JobNormalizer} already derived from an explicit statement, is never
 * overwritten by a model guess — the publisher's own words outrank an inference.
 */
@Component
public class JobEnricher {

    private static final Logger log = LoggerFactory.getLogger(JobEnricher.class);
    private static final int MAX_SKILLS_PER_JOB = 25;
    private static final int MAX_DESCRIPTION_CHARS = 12_000;

    private static final String SYSTEM_PROMPT = """
            You read job descriptions and return structured attributes.

            Rules:
            - Report only what the description states. Never invent a requirement.
            - Leave a field null when the description does not state it.
            - requiredSkills: technologies and tools the posting says are required. 1-3 words each.
            - preferredSkills: the same, for anything described as nice-to-have, a plus, or bonus.
            - seniority: one of INTERN, ENTRY, JUNIOR, MID, SENIOR, LEAD, PRINCIPAL, or null.
            - employmentType: FULL_TIME, PART_TIME, CONTRACT, INTERNSHIP, TEMPORARY, or null.
            - workMode: ONSITE, HYBRID, REMOTE, or null.
            - Experience years: numbers only, and only when the description states them.
            """;

    private final AiClient aiClient;
    private final SkillResolver skillResolver;
    private final SkillRepository skillRepository;

    private final SkillRequirementClassifier requirementClassifier;

    public JobEnricher(AiClient aiClient, SkillResolver skillResolver, SkillRepository skillRepository,
                       SkillRequirementClassifier requirementClassifier) {
        this.requirementClassifier = requirementClassifier;
        this.aiClient = aiClient;
        this.skillResolver = skillResolver;
        this.skillRepository = skillRepository;
    }

    /**
     * Enriches a single job, loading the skill dictionary itself. Use
     * {@link #enrich(Job, List)} inside a batch so the dictionary is read once
     * rather than once per job.
     */
    @Transactional
    public void enrich(Job job) {
        enrich(job, skillRepository.findAll());
    }

    /**
     * Enriches a job in place. Never throws for AI reasons: a failed model call
     * leaves the dictionary-derived skills in place and marks the job accordingly.
     */
    @Transactional
    public void enrich(Job job, List<Skill> dictionary) {
        String text = enrichmentText(job);
        if (!TextUtils.hasText(text)) {
            job.setEnrichmentStatus(EnrichmentStatus.SKIPPED);
            job.setEnrichmentEngine("none");
            return;
        }

        // Every skill the dictionary can see, then classified by where the
        // posting actually mentions it. Treating each match as REQUIRED — which
        // is what this did — produced a corpus with no PREFERRED skill in it at
        // all, and turned passing mentions into hard requirements.
        List<String> mentioned = matchDictionarySkills(text, dictionary);
        Map<String, SkillRequirement> tiers = requirementClassifier.classify(mentioned, text);
        String engine = "dictionary";
        EnrichmentStatus status = EnrichmentStatus.ENRICHED;

        if (aiClient.isAvailable()) {
            try {
                ExtractedJobAttributes attributes = aiClient.structured(SYSTEM_PROMPT,
                        "Job title: " + nullSafe(job.getTitle()) + "\n\nDescription:\n"
                                + TextUtils.truncate(text, MAX_DESCRIPTION_CHARS),
                        ExtractedJobAttributes.class);
                applyAttributes(job, attributes);
                // The model's judgement replaces the heuristic for skills it
                // ruled on, rather than being unioned into it. Unioning is what
                // made every AI-preferred skill required as well.
                overrideTier(tiers, attributes.requiredSkills(), SkillRequirement.REQUIRED);
                overrideTier(tiers, attributes.preferredSkills(), SkillRequirement.PREFERRED);
                engine = aiClient.modelName();
            } catch (AiUnavailableException ex) {
                log.debug("AI enrichment unavailable for job {}: {}", job.getId(), ex.getMessage());
                status = EnrichmentStatus.FAILED;
            }
        }

        applySkills(job, tiers, engine);

        job.setEnrichmentStatus(status);
        job.setEnrichmentEngine(engine);
        job.setSearchText(buildSearchText(job));
    }

    /**
     * Dictionary matching: every canonical skill whose name appears in the text.
     *
     * <p>Boundaries are tested against the characters a skill name can contain
     * rather than by padding with spaces. Canonicalisation keeps full stops, so
     * the padded form silently missed every skill that ended a sentence — and
     * still matched names containing a stop, like ".NET", only by accident.
     */
    List<String> matchDictionarySkills(String text, List<Skill> dictionary) {
        String haystack = TextUtils.canonicalize(text);
        List<String> found = new ArrayList<>();
        for (Skill skill : dictionary) {
            if (!containsWord(haystack, TextUtils.canonicalize(skill.getCanonicalName()))) {
                continue;
            }
            if (!meansTheSkill(haystack, skill.getSlug())) {
                continue;
            }
            found.add(skill.getCanonicalName());
        }
        return found;
    }

    /**
     * Skill names that are also ordinary English words, mapped to the evidence
     * that a posting means the technology.
     *
     * <p>"Go" is the clear case in this corpus: of the standalone occurrences,
     * roughly 325 are "go-to-market" and 26 are "go live", against a handful of
     * genuine mentions like "Proficiency in Python, Java, Bash, and Go". Matching
     * on the bare word turned a sales phrase into a programming-language
     * requirement.
     *
     * <p>Evidence is demanded rather than false friends enumerated. A blocklist
     * of non-technical phrases would always be one idiom behind, and the cost of
     * being wrong is asymmetric: inventing a language requirement a student does
     * not meet is far worse than missing a mention of one they do.
     */
    private static final Map<String, Pattern> AMBIGUOUS_SKILLS = Map.of(
            "go", Pattern.compile(
                    // Named as the role's language.
                    "\\bgo(?:lang)? (?:developer|engineer|programming|program|services?"
                            + "|microservices?|routines?|modules?|code|codebase|backend|experience)\\b"
                            // Used as the object of a verb or preposition.
                            + "|\\b(?:in|with|using|learn(?:ing)?|write|writing|written|know|knows) go\\b"
                            // Named in a list of languages. Canonicalisation removes
                            // the commas, so the neighbouring conjunction is the signal.
                            + "|\\b(?:or|and) go\\b|\\bgo (?:or|and) \\w+"
                            + "|\\bgolang\\b",
                    Pattern.CASE_INSENSITIVE));

    /**
     * Whether a posting genuinely refers to an ambiguously named skill.
     *
     * <p>Unambiguous names skip this entirely; only the short common words in
     * {@link #AMBIGUOUS_SKILLS} have to earn their detection.
     */
    private boolean meansTheSkill(String canonicalText, String slug) {
        Pattern evidence = AMBIGUOUS_SKILLS.get(slug);
        return evidence == null || evidence.matcher(canonicalText).find();
    }

    private boolean containsWord(String haystack, String needle) {
        if (needle.isEmpty()) {
            return false;
        }
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            boolean leftClear = at == 0 || !isNameChar(haystack.charAt(at - 1));
            int after = at + needle.length();
            boolean rightClear = after >= haystack.length() || !isNameChar(haystack.charAt(after));
            if (leftClear && rightClear) {
                return true;
            }
            at = haystack.indexOf(needle, at + 1);
        }
        return false;
    }

    private boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '+' || c == '#';
    }

    private void applyAttributes(Job job, ExtractedJobAttributes attributes) {
        if (job.getSeniority() == Seniority.UNSPECIFIED) {
            job.setSeniority(parseEnum(Seniority.class, attributes.seniority(), Seniority.UNSPECIFIED));
        }
        if (job.getEmploymentType() == EmploymentType.UNSPECIFIED) {
            job.setEmploymentType(parseEnum(EmploymentType.class, attributes.employmentType(),
                    EmploymentType.UNSPECIFIED));
        }
        if (job.getWorkMode() == WorkMode.UNSPECIFIED) {
            job.setWorkMode(parseEnum(WorkMode.class, attributes.workMode(), WorkMode.UNSPECIFIED));
        }
        if (job.getMinExperienceYears() == null && attributes.minExperienceYears() != null) {
            job.setMinExperienceYears(clampYears(attributes.minExperienceYears()));
        }
        if (job.getMaxExperienceYears() == null && attributes.maxExperienceYears() != null) {
            job.setMaxExperienceYears(clampYears(attributes.maxExperienceYears()));
        }
        if (!TextUtils.hasText(job.getResponsibilities())
                && TextUtils.hasText(attributes.responsibilitiesSummary())) {
            job.setResponsibilities(attributes.responsibilitiesSummary());
        }
        if (!TextUtils.hasText(job.getRequirements()) && TextUtils.hasText(attributes.requirementsSummary())) {
            job.setRequirements(attributes.requirementsSummary());
        }
    }

    /** Adds skills strongest tier first, so the per-job cap never truncates the requirements. */
    private void applySkills(Job job, Map<String, SkillRequirement> tiers, String engine) {
        job.getSkills().clear();
        Set<String> seen = new LinkedHashSet<>();
        SkillExtractionMethod method = engine.equals("dictionary")
                ? SkillExtractionMethod.DICTIONARY
                : SkillExtractionMethod.AI;

        for (SkillRequirement requirement : SkillRequirement.values()) {
            addSkills(job, tiers.entrySet().stream()
                    .filter(entry -> entry.getValue() == requirement)
                    .map(Map.Entry::getKey)
                    .toList(), requirement, method, seen);
        }
    }

    /** Applies a tier the model asserted, overriding what the text heuristic decided. */
    private void overrideTier(Map<String, SkillRequirement> tiers, List<String> names,
                              SkillRequirement requirement) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            if (TextUtils.hasText(name)) {
                tiers.put(name.strip(), requirement);
            }
        }
    }

    private void addSkills(Job job, Collection<String> names, SkillRequirement requirement,
                           SkillExtractionMethod method, Set<String> seen) {
        for (String name : names) {
            if (seen.size() >= MAX_SKILLS_PER_JOB) {
                return;
            }
            skillResolver.resolve(name).ifPresent(skill -> {
                if (!seen.add(skill.getSlug())) {
                    return;
                }
                JobSkill jobSkill = new JobSkill();
                jobSkill.setJob(job);
                jobSkill.setSkill(skill);
                jobSkill.setRequirement(requirement);
                jobSkill.setExtractedBy(method);
                job.getSkills().add(jobSkill);
            });
        }
    }

    private String enrichmentText(Job job) {
        StringBuilder builder = new StringBuilder();
        if (TextUtils.hasText(job.getDescription())) {
            builder.append(job.getDescription());
        }
        if (TextUtils.hasText(job.getRequirements())) {
            builder.append('\n').append(job.getRequirements());
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    /** The text column plain-language search runs against. */
    /**
     * Rebuilds the searchable document for a job.
     *
     * <p>Exposed so anything that changes a job outside the enrichment step —
     * a backfill, a test fixture — produces the same document the pipeline
     * would, rather than a hand-assembled approximation that search then
     * behaves differently against.
     */
    public void refreshSearchText(Job job) {
        job.setSearchText(buildSearchText(job));
    }

    private String buildSearchText(Job job) {
        StringBuilder builder = new StringBuilder();
        builder.append(nullSafe(job.getTitle())).append(' ')
                .append(nullSafe(job.getNormalizedTitle())).append(' ')
                .append(job.getCompany() == null ? "" : job.getCompany().getName()).append(' ')
                .append(nullSafe(job.getLocationRaw())).append(' ')
                .append(job.getWorkMode().name()).append(' ')
                .append(job.getEmploymentType().name()).append(' ')
                .append(job.getSeniority().name()).append(' ');
        job.getSkills().forEach(skill -> builder.append(skill.getSkill().getCanonicalName()).append(' '));
        if (TextUtils.hasText(job.getDescription())) {
            builder.append(TextUtils.truncate(job.getDescription(), 4000));
        }
        return TextUtils.canonicalize(builder.toString());
    }

    private void addAll(Set<String> target, List<String> values) {
        if (values != null) {
            values.stream().filter(TextUtils::hasText).forEach(target::add);
        }
    }

    private BigDecimal clampYears(Double value) {
        if (value == null || value < 0 || value > 40) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(1, java.math.RoundingMode.HALF_UP);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (!TextUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
