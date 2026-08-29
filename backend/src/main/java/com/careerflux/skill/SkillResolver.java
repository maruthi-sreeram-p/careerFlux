package com.careerflux.skill;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.careerflux.common.TextUtils;
import com.careerflux.common.taxonomy.SkillCategory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves free-text skill strings to canonical {@link Skill} rows.
 *
 * <p>This is deliberately deterministic: alias lookup first, then slug lookup,
 * then a controlled create. AI is allowed to <em>propose</em> skill strings when
 * reading a resume or a job description, but it never decides what a skill is
 * called in the database, because a drifting skill vocabulary would silently
 * corrupt every match score computed against it.
 */
@Service
public class SkillResolver {

    private static final Logger log = LoggerFactory.getLogger(SkillResolver.class);
    private static final int MAX_SKILL_LENGTH = 60;

    private final SkillRepository skillRepository;
    private final SkillAliasRepository aliasRepository;

    public SkillResolver(SkillRepository skillRepository, SkillAliasRepository aliasRepository) {
        this.skillRepository = skillRepository;
        this.aliasRepository = aliasRepository;
    }

    /**
     * Resolves a raw string, creating the canonical entry when it is new and
     * plausible. Returns empty for input that is not a skill (empty, too long,
     * or a fragment of prose).
     */
    @Transactional
    public Optional<Skill> resolve(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            return Optional.empty();
        }
        String slug = TextUtils.slugify(cleaned);
        if (slug.isEmpty()) {
            return Optional.empty();
        }

        Optional<Skill> byAlias = aliasRepository.findByAlias(slug).map(SkillAlias::getSkill);
        if (byAlias.isPresent()) {
            return byAlias;
        }
        Optional<Skill> bySlug = skillRepository.findBySlug(slug);
        if (bySlug.isPresent()) {
            return bySlug;
        }

        Skill created = new Skill();
        created.setCanonicalName(cleaned);
        created.setSlug(slug);
        created.setCategory(guessCategory(slug));
        log.debug("Registered new canonical skill '{}' ({})", cleaned, slug);
        return Optional.of(skillRepository.save(created));
    }

    /**
     * Finds an existing skill, and never registers a new one.
     *
     * <p>{@link #resolve(String)} creates what it cannot find, which is right
     * for ingestion: a board that mentions a real technology the dictionary has
     * not met yet is evidence the dictionary is incomplete. It is wrong for
     * anything a person types. A placement officer who writes "Sprint Boot"
     * would otherwise mint a canonical skill from their typo, and every college
     * on the platform would inherit it.
     *
     * <p>Callers that use this are expected to report what did not resolve
     * rather than discard it silently.
     */
    public Optional<Skill> lookup(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            return Optional.empty();
        }
        String slug = TextUtils.slugify(cleaned);
        if (slug.isEmpty()) {
            return Optional.empty();
        }
        Optional<Skill> byAlias = aliasRepository.findByAlias(slug).map(SkillAlias::getSkill);
        return byAlias.isPresent() ? byAlias : skillRepository.findBySlug(slug);
    }

    /** Resolves a batch, preserving order and dropping anything unresolvable. */
    @Transactional
    public List<Skill> resolveAll(Collection<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Skill> resolved = new ArrayList<>();
        for (String raw : rawValues) {
            resolve(raw).ifPresent(skill -> {
                if (seen.add(skill.getSlug())) {
                    resolved.add(skill);
                }
            });
        }
        return resolved;
    }

    private String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip()
                .replaceAll("^[\\-\\u2022*\\s]+", "")
                .replaceAll("[.,;:]+$", "")
                .strip();
        if (trimmed.length() < 2 || trimmed.length() > MAX_SKILL_LENGTH) {
            return null;
        }
        // A "skill" with more than four words is almost always a sentence fragment.
        if (trimmed.split("\\s+").length > 4) {
            return null;
        }
        return trimmed;
    }

    /**
     * Best-effort categorisation for display grouping. Being wrong here changes
     * nothing about matching; it only affects how skills are grouped in the UI.
     */
    private SkillCategory guessCategory(String slug) {
        return switch (slug) {
            case "java", "python", "javascript", "typescript", "go", "rust", "c", "c-plus-plus", "csharp",
                 "kotlin", "scala", "ruby", "php", "swift", "sql", "r" -> SkillCategory.LANGUAGE;
            case "spring", "spring-boot", "react", "angular", "vue", "django", "flask", "express",
                 "next-js", "hibernate", "dot-net", "rails", "fastapi" -> SkillCategory.FRAMEWORK;
            case "postgresql", "mysql", "mongodb", "redis", "cassandra", "elasticsearch", "oracle",
                 "dynamodb", "sqlite", "neo4j" -> SkillCategory.DATABASE;
            case "aws", "azure", "gcp", "kubernetes", "docker", "terraform", "cloudformation" -> SkillCategory.CLOUD;
            case "git", "jira", "maven", "gradle", "jenkins", "github-actions", "kafka", "rabbitmq" -> SkillCategory.TOOL;
            case "agile", "scrum", "tdd", "ci-cd", "microservices", "rest-apis", "system-design",
                 "code-review" -> SkillCategory.PRACTICE;
            case "communication", "leadership", "mentoring", "collaboration", "ownership" -> SkillCategory.SOFT;
            default -> slug.contains("develop") || slug.contains("engineering")
                    ? SkillCategory.DOMAIN
                    : SkillCategory.OTHER;
        };
    }

    /** Display helper so the UI never has to title-case slugs itself. */
    public static String prettify(String slug) {
        if (slug == null || slug.isEmpty()) {
            return "";
        }
        String[] parts = slug.split("-");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }
}
