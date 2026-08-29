package com.careerflux.matching.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.careerflux.common.TextUtils;
import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.job.domain.SkillRequirement;
import com.careerflux.matching.role.RoleSkill;
import com.careerflux.matching.role.ScorableRole;
import com.careerflux.matching.domain.BlockerType;
import com.careerflux.matching.domain.ConfidenceLevel;
import com.careerflux.matching.domain.EligibilityStatus;
import com.careerflux.matching.domain.MatchComponent;
import com.careerflux.matching.domain.MatchDimension;

import org.springframework.stereotype.Component;

/**
 * Scores one candidate against one job, and explains itself while doing it.
 *
 * <p>Entirely deterministic. No model call, no learned weight: a candidate
 * asking "why 84?" is answered with the arithmetic.
 *
 * <p><b>Four separate answers, never collapsed into one.</b>
 *
 * <ol>
 *   <li><b>Eligibility</b> — can they apply? Decided only from conditions the
 *       posting explicitly states. A missing skill is never a blocker.
 *   <li><b>Compatibility</b> — how well do they fit? The plain weighted average,
 *       never capped or adjusted after the fact.
 *   <li><b>Skill gaps</b> — what is missing, by tier.
 *   <li><b>Confidence</b> — how much of the weight could be compared at all.
 * </ol>
 *
 * <p><b>Unknown is not a score.</b> A dimension that cannot be compared is
 * excluded from the average and its weight redistributed over those that can.
 * The previous model awarded a neutral 70 for missing information, which was
 * simultaneously a passing score and the visibility floor — so a profile
 * compared against nothing was presented as a moderate match.
 *
 * <p><b>Skills are scored by tier, normalised over the tiers a posting uses.</b>
 * A job listing eight optional technologies alongside three requirements is no
 * longer punished for being thorough: optional carries a tenth of the weight,
 * not eight thirteenths of it.
 */
@Component
public class MatchScorer {

    /** Bump when the rules change, so old rows stay interpretable. */
    public static final String VERSION = "rules-2";

    /** Relative pull of each skill tier, before normalising over those present. */
    private static final double TIER_REQUIRED = 0.70;
    private static final double TIER_PREFERRED = 0.20;
    private static final double TIER_OPTIONAL = 0.10;

    private static final int MAX_LISTED_STRENGTHS = 8;
    private static final int MAX_LISTED_GAPS = 5;

    private final CareerFluxProperties properties;

    public MatchScorer(CareerFluxProperties properties) {
        this.properties = properties;
    }

    public Scorecard score(CandidateSnapshot candidate, ScorableRole role) {
        List<MatchComponent> components = new ArrayList<>();
        Counter order = new Counter();

        SkillOutcome skills = scoreSkills(candidate, role, components, order);
        Map<MatchDimension, DimensionResult> results = new EnumMap<>(MatchDimension.class);
        results.put(MatchDimension.SKILLS, skills.result());
        results.put(MatchDimension.ROLE, scoreRole(candidate, role, components, order));
        results.put(MatchDimension.EXPERIENCE, scoreExperience(candidate, role, components, order));
        results.put(MatchDimension.LOCATION, scoreLocation(candidate, role, components, order));
        results.put(MatchDimension.SENIORITY, scoreSeniority(candidate, role, components, order));
        results.put(MatchDimension.WORK_MODE, scoreWorkMode(candidate, role, components, order));

        List<Blocker> blockers = findBlockers(candidate, role, components, order);
        addContext(candidate, role, components, order);

        Confidence confidence = confidence(results);
        Integer compatibility = confidence.level() == ConfidenceLevel.INSUFFICIENT
                ? null
                : weightedAverage(results);

        EligibilityStatus eligibility = eligibility(blockers, skills, role, confidence);

        return new Scorecard(compatibility, eligibility, blockers, confidence, results, components,
                skills.matchedRequired(), skills.missingRequired(), skills.missingPreferred());
    }

    // ---------------------------------------------------------------- weights

    private double weight(MatchDimension dimension) {
        CareerFluxProperties.Matching m = properties.matching();
        return switch (dimension) {
            case SKILLS -> m.weightSkills();
            case ROLE -> m.weightRole();
            case EXPERIENCE -> m.weightExperience();
            case LOCATION -> m.weightLocation();
            case SENIORITY -> m.weightSeniority();
            case WORK_MODE -> m.weightWorkMode();
            case CONTEXT -> 0;
        };
    }

    /**
     * The weighted average over the dimensions that could be compared.
     *
     * <p>Renormalising rather than defaulting is the whole point: an unknown
     * dimension neither pays out nor penalises, and the number means "of what
     * could be compared, this is the fit".
     */
    private Integer weightedAverage(Map<MatchDimension, DimensionResult> results) {
        double weighted = 0;
        double total = 0;
        for (Map.Entry<MatchDimension, DimensionResult> entry : results.entrySet()) {
            if (!entry.getValue().isKnown()) {
                continue;
            }
            double w = weight(entry.getKey());
            weighted += entry.getValue().score() * w;
            total += w;
        }
        return total == 0 ? null : (int) Math.round(weighted / total);
    }

    private Confidence confidence(Map<MatchDimension, DimensionResult> results) {
        double known = 0;
        double total = 0;
        List<MatchDimension> missing = new ArrayList<>();
        for (Map.Entry<MatchDimension, DimensionResult> entry : results.entrySet()) {
            double w = weight(entry.getKey());
            total += w;
            if (entry.getValue().isKnown()) {
                known += w;
            } else {
                missing.add(entry.getKey());
            }
        }
        int coverage = total == 0 ? 0 : (int) Math.round(known / total * 100);

        // Skills and role together carry most of the signal. Without both there
        // is nothing left but circumstance, whatever the arithmetic coverage says.
        boolean coreMissing = !results.get(MatchDimension.SKILLS).isKnown()
                && !results.get(MatchDimension.ROLE).isKnown();

        ConfidenceLevel level;
        if (coreMissing || coverage < 30) {
            level = ConfidenceLevel.INSUFFICIENT;
        } else if (coverage >= 80) {
            level = ConfidenceLevel.HIGH;
        } else if (coverage >= 50) {
            level = ConfidenceLevel.MEDIUM;
        } else {
            level = ConfidenceLevel.LOW;
        }
        return new Confidence(level, coverage, List.copyOf(missing));
    }

    // ------------------------------------------------------------ eligibility

    /**
     * Blockers: conditions the posting states and the candidate demonstrably
     * fails. Nothing here is inferred, and no quantity of missing skills
     * produces one.
     */
    private List<Blocker> findBlockers(CandidateSnapshot candidate, ScorableRole role,
                                       List<MatchComponent> components, Counter order) {
        List<Blocker> blockers = new ArrayList<>();
        CareerFluxProperties.Matching m = properties.matching();

        BigDecimal min = role.minExperienceYears();
        BigDecimal years = candidate.yearsExperience();
        if (min != null && years != null) {
            double shortfall = min.doubleValue() - years.doubleValue();
            if (shortfall >= m.experienceBlockerYears()) {
                String detail = "The " + role.descriptor() + " asks for at least " + trim(min)
                        + " years; your profile says " + trim(years) + ".";
                blockers.add(new Blocker(BlockerType.EXPERIENCE_BELOW_MINIMUM,
                        formatShortfall(shortfall) + " below the stated minimum", detail));
                components.add(MatchComponent.blocker(MatchDimension.EXPERIENCE,
                        formatShortfall(shortfall) + " below the stated minimum", detail, order.next()));
            }
        }

        // Only an explicit on-site posting can block on place. Hybrid and
        // unspecified never do, and a different city on its own never does.
        if (role.workMode() == WorkMode.ONSITE && !locationAcceptable(candidate, role)
                && !candidate.openToRelocation()) {
            String where = describeLocation(role);
            String detail = "This role is on-site in " + where
                    + ", which is not among the locations you listed, and you are not open to relocation.";
            blockers.add(new Blocker(BlockerType.ONSITE_UNREACHABLE, "On-site in " + where, detail));
            components.add(MatchComponent.blocker(MatchDimension.LOCATION,
                    "On-site in " + where, detail, order.next()));
        }
        return blockers;
    }

    private EligibilityStatus eligibility(List<Blocker> blockers, SkillOutcome skills, ScorableRole role,
                                          Confidence confidence) {
        // A blocker still wins: something stated was actually evaluated and
        // actually failed, which is a real answer however little else is known.
        if (!blockers.isEmpty()) {
            return EligibilityStatus.NOT_ELIGIBLE;
        }
        // Too little known to have an opinion. This is the same condition that
        // withholds the score, and the two must agree: a scorecard cannot
        // sensibly report UNAVAILABLE and ELIGIBLE at once. Without it an empty
        // profile came back ELIGIBLE purely because a candidate who lists no
        // skills can be missing none.
        if (confidence.level() == ConfidenceLevel.INSUFFICIENT) {
            return EligibilityStatus.UNKNOWN;
        }
        // Nothing stated, nothing checkable. Saying ELIGIBLE would claim more
        // than the posting supports.
        boolean statesAnything = role.minExperienceYears() != null
                || role.workMode() != WorkMode.UNSPECIFIED
                || !role.skills().isEmpty();
        if (!statesAnything) {
            return EligibilityStatus.UNKNOWN;
        }
        if (!skills.missingRequired().isEmpty() || !skills.missingPreferred().isEmpty()) {
            return EligibilityStatus.ELIGIBLE_WITH_GAPS;
        }
        return EligibilityStatus.ELIGIBLE;
    }

    // ---------------------------------------------------------------- skills

    /**
     * Coverage per tier, normalised over the tiers the posting actually uses.
     *
     * <p>A posting that lists no preferred skills gets no free preferred points,
     * and a posting that lists many optional ones is not dragged down by them.
     */
    private SkillOutcome scoreSkills(CandidateSnapshot candidate, ScorableRole role,
                                     List<MatchComponent> components, Counter order) {
        List<RoleSkill> roleSkills = role.skills();
        if (roleSkills.isEmpty()) {
            components.add(MatchComponent.unknown(MatchDimension.SKILLS,
                    "This " + role.descriptor() + " does not list any skills",
                    "There is nothing to compare your skills against.", order.next()));
            return SkillOutcome.unknown(DimensionResult.unknownJob());
        }
        if (candidate.skillSlugs().isEmpty()) {
            components.add(MatchComponent.unknown(MatchDimension.SKILLS,
                    "Your profile lists no skills",
                    "Add your skills and this comparison becomes possible.", order.next()));
            return SkillOutcome.unknown(DimensionResult.unknownCandidate());
        }

        Map<SkillRequirement, List<String>> matched = new EnumMap<>(SkillRequirement.class);
        Map<SkillRequirement, List<String>> missing = new EnumMap<>(SkillRequirement.class);
        for (SkillRequirement tier : SkillRequirement.values()) {
            matched.put(tier, new ArrayList<>());
            missing.put(tier, new ArrayList<>());
        }

        Set<String> candidateSlugs = candidate.skillSlugs();
        for (RoleSkill roleSkill : roleSkills) {
            SkillRequirement tier = roleSkill.tier();
            String name = roleSkill.canonicalName();
            boolean has = candidateSlugs.contains(roleSkill.slug());
            (has ? matched : missing).get(tier).add(name);
        }

        double weightedCoverage = 0;
        double presentWeight = 0;
        for (SkillRequirement tier : SkillRequirement.values()) {
            int total = matched.get(tier).size() + missing.get(tier).size();
            if (total == 0) {
                continue;
            }
            double tierWeight = tierWeight(tier);
            presentWeight += tierWeight;
            weightedCoverage += tierWeight * ((double) matched.get(tier).size() / total);
        }
        int score = presentWeight == 0 ? 0 : (int) Math.round(weightedCoverage / presentWeight * 100);

        describeSkills(matched, missing, components, order, role.descriptor());
        return new SkillOutcome(DimensionResult.scored(score),
                List.copyOf(matched.get(SkillRequirement.REQUIRED)),
                List.copyOf(missing.get(SkillRequirement.REQUIRED)),
                List.copyOf(missing.get(SkillRequirement.PREFERRED)));
    }

    private double tierWeight(SkillRequirement tier) {
        return switch (tier) {
            case REQUIRED -> TIER_REQUIRED;
            case PREFERRED -> TIER_PREFERRED;
            case OPTIONAL -> TIER_OPTIONAL;
        };
    }

    private void describeSkills(Map<SkillRequirement, List<String>> matched,
                                Map<SkillRequirement, List<String>> missing,
                                List<MatchComponent> components, Counter order,
                                String descriptor) {
        for (String name : matched.get(SkillRequirement.REQUIRED).stream()
                .limit(MAX_LISTED_STRENGTHS).toList()) {
            components.add(MatchComponent.strength(MatchDimension.SKILLS, name,
                    "Required by this " + descriptor, order.next()));
        }
        for (String name : matched.get(SkillRequirement.PREFERRED).stream().limit(3).toList()) {
            components.add(MatchComponent.strength(MatchDimension.SKILLS, name,
                    "Listed as preferred", order.next()));
        }
        for (String name : missing.get(SkillRequirement.REQUIRED).stream()
                .limit(MAX_LISTED_GAPS).toList()) {
            components.add(MatchComponent.gap(MatchDimension.SKILLS, name,
                    "Required and not on your profile", order.next()));
        }
        for (String name : missing.get(SkillRequirement.PREFERRED).stream().limit(3).toList()) {
            components.add(MatchComponent.gap(MatchDimension.SKILLS, name,
                    "Preferred, not required", order.next()));
        }
        // Optional gaps are deliberately not listed. A posting naming eight
        // technologies in passing would otherwise read as eight failures.
    }

    // ------------------------------------------------------------ experience

    private DimensionResult scoreExperience(CandidateSnapshot candidate, ScorableRole role,
                                            List<MatchComponent> components, Counter order) {
        BigDecimal years = candidate.yearsExperience();
        BigDecimal min = role.minExperienceYears();
        BigDecimal max = role.maxExperienceYears();

        boolean jobSilent = min == null && max == null;
        boolean candidateSilent = years == null;
        if (jobSilent || candidateSilent) {
            components.add(MatchComponent.unknown(MatchDimension.EXPERIENCE,
                    jobSilent && candidateSilent ? "Experience not stated on either side"
                            : jobSilent ? "This " + role.descriptor() + " states no experience range"
                            : "Your profile does not state your years of experience",
                    candidateSilent && !jobSilent ? "Add it and this match gets sharper." : null,
                    order.next()));
            return jobSilent && candidateSilent ? DimensionResult.unknownBoth()
                    : jobSilent ? DimensionResult.unknownJob() : DimensionResult.unknownCandidate();
        }

        double candidateYears = years.doubleValue();
        double lower = min == null ? 0 : min.doubleValue();
        double upper = max == null ? Double.MAX_VALUE : max.doubleValue();

        if (candidateYears >= lower && candidateYears <= upper) {
            components.add(MatchComponent.strength(MatchDimension.EXPERIENCE,
                    formatRange(min, max) + " experience", "You are inside the stated range.",
                    order.next()));
            return DimensionResult.scored(100);
        }
        if (candidateYears < lower) {
            double shortfall = lower - candidateYears;
            int score = (int) Math.round(100 - shortfall * 25);
            // A blocker-sized shortfall already has its own component.
            if (shortfall < properties.matching().experienceBlockerYears()) {
                components.add(MatchComponent.gap(MatchDimension.EXPERIENCE,
                        formatShortfall(shortfall) + " below the stated minimum",
                        "The " + role.descriptor() + " asks for " + formatRange(min, max) + ".", order.next()));
            }
            return DimensionResult.scored(score);
        }
        double excess = candidateYears - upper;
        components.add(MatchComponent.neutral(MatchDimension.EXPERIENCE,
                "More experience than the " + role.descriptor() + " asks for",
                "The " + role.descriptor() + " asks for " + formatRange(min, max) + ".", order.next()));
        return DimensionResult.scored((int) Math.round(100 - excess * 8));
    }

    // ------------------------------------------------------------------ role

    private DimensionResult scoreRole(CandidateSnapshot candidate, ScorableRole role,
                                      List<MatchComponent> components, Counter order) {
        // A set, because the primary role is usually also a target role and
        // listing it twice reads as a bug: "you are looking for Backend
        // Developer, Backend Developer".
        LinkedHashSet<String> targetSet = new LinkedHashSet<>(candidate.targetRoles());
        if (TextUtils.hasText(candidate.primaryRole())) {
            targetSet.add(candidate.primaryRole());
        }
        List<String> targets = List.copyOf(targetSet);
        if (targets.isEmpty()) {
            components.add(MatchComponent.unknown(MatchDimension.ROLE,
                    "You have not set a target role",
                    "Tell CareerFlux which roles you want and this gets much more accurate.",
                    order.next()));
            return DimensionResult.unknownCandidate();
        }

        String jobTitle = TextUtils.hasText(role.normalizedTitle())
                ? role.normalizedTitle() : role.title();
        if (!TextUtils.hasText(jobTitle)) {
            components.add(MatchComponent.unknown(MatchDimension.ROLE,
                    "This " + role.descriptor() + " has no usable title", null, order.next()));
            return DimensionResult.unknownJob();
        }

        double best = 0;
        String bestTarget = null;
        for (String target : targets) {
            double similarity = TextUtils.tokenSimilarity(target, jobTitle);
            if (similarity > best) {
                best = similarity;
                bestTarget = target;
            }
        }
        // Half the tokens shared between two job titles is already a strong
        // signal, so the raw ratio is stretched rather than used directly.
        int score = (int) Math.round(Math.min(1.0, best * 1.6) * 100);

        if (best >= 0.5) {
            components.add(MatchComponent.strength(MatchDimension.ROLE,
                    "Matches your target role", bestTarget, order.next()));
        } else if (best >= 0.25) {
            components.add(MatchComponent.neutral(MatchDimension.ROLE,
                    "Adjacent to your target role", bestTarget, order.next()));
        } else {
            components.add(MatchComponent.gap(MatchDimension.ROLE,
                    "Different from the roles you are targeting",
                    "You are looking for " + String.join(", ", targets.stream().limit(3).toList()) + ".",
                    order.next()));
        }
        return DimensionResult.scored(score);
    }

    // -------------------------------------------------------------- location

    private DimensionResult scoreLocation(CandidateSnapshot candidate, ScorableRole role,
                                          List<MatchComponent> components, Counter order) {
        boolean jobSilent = role.workMode() == WorkMode.UNSPECIFIED
                && !TextUtils.hasText(role.city())
                && !TextUtils.hasText(role.locationRaw());
        boolean candidateSilent = candidate.preferredLocations().isEmpty()
                && !TextUtils.hasText(candidate.location());

        if (jobSilent || candidateSilent) {
            components.add(MatchComponent.unknown(MatchDimension.LOCATION,
                    jobSilent ? "This " + role.descriptor() + " does not say where the role is"
                            : "You have not said where you want to work",
                    candidateSilent && !jobSilent ? "Add a location preference to sharpen this." : null,
                    order.next()));
            return jobSilent && candidateSilent ? DimensionResult.unknownBoth()
                    : jobSilent ? DimensionResult.unknownJob() : DimensionResult.unknownCandidate();
        }

        if (role.workMode() == WorkMode.REMOTE) {
            boolean wantsRemote = candidate.workModes().contains(WorkMode.REMOTE)
                    || candidate.workModes().isEmpty();
            components.add(MatchComponent.strength(MatchDimension.LOCATION, "Remote",
                    wantsRemote ? null : "You did not list remote as a preference.", order.next()));
            return DimensionResult.scored(wantsRemote ? 100 : 80);
        }
        if (locationAcceptable(candidate, role)) {
            components.add(MatchComponent.strength(MatchDimension.LOCATION,
                    describeLocation(role), null, order.next()));
            return DimensionResult.scored(100);
        }
        if (candidate.openToRelocation()) {
            components.add(MatchComponent.neutral(MatchDimension.LOCATION,
                    describeLocation(role) + " — you are open to relocation", null, order.next()));
            return DimensionResult.scored(60);
        }
        // An on-site posting here has already produced a blocker; the score
        // still reflects the distance so the ordering stays sensible.
        components.add(MatchComponent.gap(MatchDimension.LOCATION, describeLocation(role),
                "Outside the locations you listed.", order.next()));
        return DimensionResult.scored(role.workMode() == WorkMode.ONSITE ? 20 : 30);
    }

    private boolean locationAcceptable(CandidateSnapshot candidate, ScorableRole role) {
        Set<String> acceptable = new LinkedHashSet<>(candidate.preferredLocations());
        if (TextUtils.hasText(candidate.location())) {
            acceptable.add(candidate.location().toLowerCase(Locale.ROOT));
        }
        String jobCity = candidate.normalize(role.city());
        String jobLocation = candidate.normalize(role.locationRaw());

        for (String option : acceptable) {
            String normalized = candidate.normalize(option);
            if (normalized.isEmpty()) {
                continue;
            }
            if (normalized.equals(jobCity)
                    || (!jobLocation.isEmpty() && jobLocation.contains(normalized))
                    || (!jobCity.isEmpty() && normalized.contains(jobCity))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- seniority

    private DimensionResult scoreSeniority(CandidateSnapshot candidate, ScorableRole role,
                                           List<MatchComponent> components, Counter order) {
        Seniority candidateLevel = candidate.seniority();
        Seniority jobLevel = role.seniority();
        boolean jobSilent = jobLevel == null || !jobLevel.isKnown();
        boolean candidateSilent = candidateLevel == null || !candidateLevel.isKnown();

        if (jobSilent || candidateSilent) {
            components.add(MatchComponent.unknown(MatchDimension.SENIORITY,
                    jobSilent && candidateSilent ? "Seniority not stated on either side"
                            : jobSilent ? "This " + role.descriptor() + " does not state a level"
                            : "Your profile does not state a level",
                    null, order.next()));
            return jobSilent && candidateSilent ? DimensionResult.unknownBoth()
                    : jobSilent ? DimensionResult.unknownJob() : DimensionResult.unknownCandidate();
        }

        int distance = candidateLevel.distanceTo(jobLevel);
        int score = switch (distance) {
            case 0 -> 100;
            case 1 -> 80;
            case 2 -> 55;
            default -> 25;
        };
        if (distance == 0) {
            components.add(MatchComponent.strength(MatchDimension.SENIORITY,
                    friendly(jobLevel.name()) + " level", null, order.next()));
        } else if (distance == 1) {
            components.add(MatchComponent.neutral(MatchDimension.SENIORITY,
                    "One level " + (jobLevel.ordinal() > candidateLevel.ordinal() ? "above" : "below") + " you",
                    "Posting is " + friendly(jobLevel.name()) + "; your profile says "
                            + friendly(candidateLevel.name()) + ".", order.next()));
        } else {
            components.add(MatchComponent.gap(MatchDimension.SENIORITY,
                    friendly(jobLevel.name()) + " level",
                    "Your profile says " + friendly(candidateLevel.name()) + ".", order.next()));
        }
        return DimensionResult.scored(score);
    }

    // ------------------------------------------------------------- work mode

    private DimensionResult scoreWorkMode(CandidateSnapshot candidate, ScorableRole role,
                                          List<MatchComponent> components, Counter order) {
        boolean jobSilent = role.workMode() == WorkMode.UNSPECIFIED;
        boolean candidateSilent = candidate.workModes().isEmpty();
        if (jobSilent || candidateSilent) {
            components.add(MatchComponent.unknown(MatchDimension.WORK_MODE,
                    jobSilent ? "This " + role.descriptor() + " does not say how the role is worked"
                            : "You have not set a work-mode preference",
                    candidateSilent && !jobSilent ? "Set one to sharpen this match." : null,
                    order.next()));
            return jobSilent && candidateSilent ? DimensionResult.unknownBoth()
                    : jobSilent ? DimensionResult.unknownJob() : DimensionResult.unknownCandidate();
        }
        if (candidate.workModes().contains(role.workMode())) {
            components.add(MatchComponent.strength(MatchDimension.WORK_MODE,
                    friendly(role.workMode().name()), null, order.next()));
            return DimensionResult.scored(100);
        }
        components.add(MatchComponent.gap(MatchDimension.WORK_MODE,
                friendly(role.workMode().name()),
                "You did not list this as a work mode you want.", order.next()));
        return DimensionResult.scored(40);
    }

    /** Facts worth showing that carry no weight in the score. */
    private void addContext(CandidateSnapshot candidate, ScorableRole role,
                            List<MatchComponent> components, Counter order) {
        if (role.employmentType() != EmploymentType.UNSPECIFIED
                && !candidate.employmentTypes().isEmpty()
                && !candidate.employmentTypes().contains(role.employmentType())) {
            components.add(MatchComponent.neutral(MatchDimension.CONTEXT,
                    friendly(role.employmentType().name()) + " role",
                    "You did not list this employment type as a preference.", order.next()));
        }
    }

    // --------------------------------------------------------------- helpers

    private String describeLocation(ScorableRole role) {
        if (TextUtils.hasText(role.city())) {
            return role.city();
        }
        return TextUtils.hasText(role.locationRaw()) ? role.locationRaw() : "Location not stated";
    }

    private String formatRange(BigDecimal min, BigDecimal max) {
        if (min != null && max != null) {
            return trim(min) + "–" + trim(max) + " years";
        }
        if (min != null) {
            return trim(min) + "+ years";
        }
        return "up to " + trim(max) + " years";
    }

    private String formatShortfall(double shortfall) {
        if (shortfall < 1) {
            return "A few months";
        }
        long whole = Math.round(shortfall);
        return whole + (whole == 1 ? " year" : " years");
    }

    private String trim(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String friendly(String enumName) {
        String lower = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Keeps component ordering monotonic without threading an int through everything. */
    private static final class Counter {
        private int value;

        int next() {
            return value++;
        }
    }

    // ----------------------------------------------------------------- types

    /** A stated condition the candidate does not meet. */
    public record Blocker(BlockerType type, String label, String detail) {
    }

    public record Confidence(ConfidenceLevel level, int coveragePercent, List<MatchDimension> unknown) {
    }

    private record SkillOutcome(DimensionResult result, List<String> matchedRequired,
                                List<String> missingRequired, List<String> missingPreferred) {

        static SkillOutcome unknown(DimensionResult result) {
            return new SkillOutcome(result, List.of(), List.of(), List.of());
        }
    }

    /**
     * Everything the scorer concluded, with the four questions kept apart.
     *
     * @param compatibility null when confidence is insufficient — the absence of
     *                      a number is the answer, not a low one
     */
    public record Scorecard(
            Integer compatibility,
            EligibilityStatus eligibility,
            List<Blocker> blockers,
            Confidence confidence,
            Map<MatchDimension, DimensionResult> dimensions,
            List<MatchComponent> components,
            List<String> matchedRequiredSkills,
            List<String> missingRequiredSkills,
            List<String> missingPreferredSkills) {

        public boolean isAvailable() {
            return compatibility != null;
        }

        public int score(MatchDimension dimension) {
            DimensionResult result = dimensions.get(dimension);
            return result != null && result.isKnown() ? result.score() : 0;
        }
    }
}
