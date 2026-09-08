package com.careerflux.candidate.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.careerflux.ai.proposal.dto.ProposalDtos.ProposedItem;
import com.careerflux.candidate.CandidateProfileChangedEvent;
import com.careerflux.candidate.domain.CandidateEducation;
import com.careerflux.candidate.domain.CandidateExperience;
import com.careerflux.candidate.domain.CandidatePreferenceValue;
import com.careerflux.candidate.domain.CandidatePreferences;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.CandidateSkill;
import com.careerflux.candidate.domain.OnboardingStage;
import com.careerflux.candidate.domain.PreferenceType;
import com.careerflux.candidate.domain.Resume;
import com.careerflux.candidate.domain.SkillOrigin;
import com.careerflux.candidate.dto.CandidateDtos.CandidateProfileResponse;
import com.careerflux.candidate.dto.CandidateDtos.EducationItem;
import com.careerflux.candidate.dto.CandidateDtos.ExperienceItem;
import com.careerflux.candidate.dto.CandidateDtos.PreferencesPayload;
import com.careerflux.candidate.dto.CandidateDtos.ProfileUpdateRequest;
import com.careerflux.candidate.dto.CandidateDtos.SkillItem;
import com.careerflux.candidate.repository.CandidatePreferenceValueRepository;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.repository.CandidateSkillRepository;
import com.careerflux.candidate.repository.ResumeRepository;
import com.careerflux.common.TextUtils;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.skill.Skill;
import com.careerflux.skill.SkillResolver;
import com.careerflux.user.User;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the candidate aggregate: profile, skills, experience, education and
 * preferences. Every write path goes through here so profile completeness and
 * the onboarding stage stay consistent no matter whether the data arrived from a
 * resume parse or from the candidate editing a form.
 */
@Service
public class CandidateProfileService {

    private static final Logger log = LoggerFactory.getLogger(CandidateProfileService.class);
    private static final int MAX_SKILLS = 60;

    private final CandidateProfileRepository profileRepository;
    private final CandidateSkillRepository skillRepository;
    private final CandidatePreferenceValueRepository preferenceValueRepository;
    private final ResumeRepository resumeRepository;
    private final SkillResolver skillResolver;
    private final CandidateMapper mapper;
    private final ApplicationEventPublisher events;

    public CandidateProfileService(CandidateProfileRepository profileRepository,
                                   CandidateSkillRepository skillRepository,
                                   CandidatePreferenceValueRepository preferenceValueRepository,
                                   ResumeRepository resumeRepository,
                                   SkillResolver skillResolver,
                                   CandidateMapper mapper,
                                   ApplicationEventPublisher events) {
        this.profileRepository = profileRepository;
        this.skillRepository = skillRepository;
        this.preferenceValueRepository = preferenceValueRepository;
        this.resumeRepository = resumeRepository;
        this.skillResolver = skillResolver;
        this.mapper = mapper;
        this.events = events;
    }

    @Transactional
    public CandidateProfile createForUser(User user) {
        CandidateProfile profile = new CandidateProfile();
        profile.setUser(user);
        // Stamped once, here. Every tenant check downstream reads this column,
        // so it must be set at the only moment a profile comes into existence.
        profile.setInstitution(user.getInstitution());
        profile.setOnboardingStage(OnboardingStage.RESUME_UPLOAD);

        CandidatePreferences preferences = new CandidatePreferences();
        preferences.setCandidate(profile);
        profile.setPreferences(preferences);

        return profileRepository.save(profile);
    }

    @Transactional(readOnly = true)
    public Optional<CandidateProfile> findByUserId(UUID userId) {
        return profileRepository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public CandidateProfile requireByUserId(UUID userId) {
        return profileRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No candidate profile for this account."));
    }

    @Transactional(readOnly = true)
    public CandidateProfileResponse getProfile(UUID userId) {
        CandidateProfile profile = requireByUserId(userId);
        return toResponse(profile);
    }

    @Transactional(readOnly = true)
    public CandidateProfileResponse toResponse(CandidateProfile profile) {
        List<CandidateSkill> skills = skillRepository.findByCandidateId(profile.getId());
        List<CandidatePreferenceValue> values =
                preferenceValueRepository.findByCandidateIdOrderByValueTypeAscDisplayOrderAsc(profile.getId());
        Resume resume = resumeRepository
                .findFirstByCandidateIdAndActiveTrueOrderByUploadedAtDesc(profile.getId())
                .orElse(null);
        return mapper.toResponse(profile, skills, values, resume);
    }

    @Transactional
    public CandidateProfileResponse updateProfile(UUID userId, ProfileUpdateRequest request) {
        CandidateProfile profile = requireByUserId(userId);

        profile.setHeadline(trimToNull(request.headline()));
        profile.setSummary(trimToNull(request.summary()));
        profile.setLocation(trimToNull(request.location()));
        profile.setPhone(trimToNull(request.phone()));
        profile.setLinkedinUrl(trimToNull(request.linkedinUrl()));
        profile.setGithubUrl(trimToNull(request.githubUrl()));
        profile.setPortfolioUrl(trimToNull(request.portfolioUrl()));
        profile.setPrimaryRole(trimToNull(request.primaryRole()));
        profile.setSeniority(parseEnum(Seniority.class, request.seniority(), Seniority.UNSPECIFIED));
        profile.setYearsExperience(request.yearsExperience());

        // Clearing and repopulating a collection in one flush makes Hibernate order
        // the inserts before the deletes, which trips the unique constraint on
        // (candidate_id, skill_id) whenever a skill survives the edit. Flushing the
        // removals first keeps the replace-in-place semantics the API promises.
        boolean replacingCollections = request.skills() != null
                || request.experiences() != null
                || request.education() != null;
        if (replacingCollections) {
            if (request.skills() != null) {
                profile.getSkills().clear();
            }
            if (request.experiences() != null) {
                profile.getExperiences().clear();
            }
            if (request.education() != null) {
                profile.getEducation().clear();
            }
            profileRepository.saveAndFlush(profile);
        }

        if (request.skills() != null) {
            replaceSkills(profile, request.skills());
        }
        if (request.experiences() != null) {
            replaceExperiences(profile, request.experiences());
        }
        if (request.education() != null) {
            replaceEducation(profile, request.education());
        }

        advanceOnboarding(profile, OnboardingStage.PREFERENCES);
        recomputeCompleteness(profile);
        profileRepository.save(profile);
        events.publishEvent(new CandidateProfileChangedEvent(profile.getId(), "profile edited"));
        return toResponse(profile);
    }

    /**
     * Replaces a candidate's career preferences.
     *
     * <p>A rescore is asked for only when this save actually changed something.
     * Rescoring a candidate against the corpus takes minutes, and re-opening the
     * preferences screen and pressing save without editing anything is a normal
     * thing to do; before this, that queued a full recomputation to arrive at
     * exactly the numbers already stored.
     *
     * <p><b>Anything, not just anything matching reads.</b> Only four of the six
     * value types and one of the nine scalars currently reach
     * {@code CandidateSnapshot}, so a narrower rule would suppress more. It would
     * also be tied to the scorer's present internals: the day salary or industry
     * is wired into scoring, matches would silently stop updating and nothing
     * would report it. A redundant rescore is rare and cheap to notice; a stale
     * match is neither.
     */
    @Transactional
    public CandidateProfileResponse savePreferences(UUID userId, PreferencesPayload payload) {
        CandidateProfile profile = requireByUserId(userId);
        CandidatePreferences preferences = profile.getPreferences();

        // A profile that had no preferences row now has one, which is a change
        // however empty the payload is.
        boolean changed = preferences == null;
        if (preferences == null) {
            preferences = new CandidatePreferences();
            profile.setPreferences(preferences);
        }
        OnboardingStage stageBefore = profile.getOnboardingStage();

        changed |= applyPreferenceScalars(preferences, payload);

        // Each call reports whether it wrote anything. Deliberately not
        // short-circuited: every type must be applied regardless of what an
        // earlier one found.
        changed |= replacePreferenceValues(profile, PreferenceType.TARGET_ROLE, payload.targetRoles(), null);
        changed |= replacePreferenceValues(profile, PreferenceType.INDUSTRY, payload.industries(), null);
        changed |= replacePreferenceValues(profile, PreferenceType.LOCATION, payload.locations(), null);
        changed |= replacePreferenceValues(profile, PreferenceType.WORK_MODE, payload.workModes(), WorkMode.class);
        changed |= replacePreferenceValues(profile, PreferenceType.EMPLOYMENT_TYPE, payload.employmentTypes(),
                EmploymentType.class);
        changed |= replacePreferenceValues(profile, PreferenceType.PREFERRED_COMPANY,
                payload.preferredCompanies(), null);

        advanceOnboarding(profile, OnboardingStage.COMPLETE);
        recomputeCompleteness(profile);
        // Reaching the end of onboarding is itself worth a rescore: it is the
        // point at which this candidate starts being treated as matchable.
        changed |= profile.getOnboardingStage() != stageBefore;
        profileRepository.save(profile);
        if (changed) {
            events.publishEvent(new CandidateProfileChangedEvent(profile.getId(), "preferences saved"));
        }
        return toResponse(profile);
    }

    /**
     * Writes the parts of an AI proposal the candidate explicitly accepted.
     *
     * <p>This is the only path by which a resume reading reaches the profile,
     * and it takes decisions rather than an extraction. Nothing is inferred
     * here: each item was shown to the candidate with what their profile
     * currently says beside it, and only the ones they acted on arrive.
     *
     * <p>Two things it deliberately cannot do. It never clears a field —
     * an accepted item always carries a value, so there is no path from "the
     * resume was silent" to "the profile is now blank". And it never touches
     * CGPA, its scale, its source or who recorded it: those are the
     * institution's record and are not in the vocabulary of this method.
     *
     * @param accepted items whose {@code proposedValue} is the final value —
     *                 already substituted where the candidate typed their own
     * @param modelRead whether a language model produced the reading, which
     *                  decides the provenance recorded against new skills
     * @return the keys that actually changed something, for the response
     */
    @Transactional
    public List<String> applyAcceptedProposal(CandidateProfile profile,
                                              List<ProposedItem> accepted,
                                              boolean modelRead) {
        List<String> applied = new ArrayList<>();
        if (accepted == null || accepted.isEmpty()) {
            return applied;
        }
        SkillOrigin origin = modelRead ? SkillOrigin.AI_SUGGESTION : SkillOrigin.RESUME;

        for (ProposedItem item : accepted) {
            String key = item.key();
            String value = trimToNull(item.proposedValue());
            if (key == null || value == null) {
                continue;
            }
            boolean changed = switch (sectionOf(key)) {
                case "field" -> applyField(profile, key.substring(6), value);
                case "skill" -> addSkill(profile, item.data().get("slug"), origin);
                case "experience" -> addExperience(profile, item.data());
                case "education" -> addEducation(profile, item.data());
                // "academic" and anything unrecognised reach here. The service
                // refuses them before this point; ignoring them again costs
                // nothing and means a new key can never become a silent write.
                default -> false;
            };
            if (changed) {
                applied.add(key);
            }
        }

        if (!applied.isEmpty()) {
            recomputeCompleteness(profile);
            profileRepository.save(profile);
        }
        return applied;
    }

    private static String sectionOf(String key) {
        int colon = key.indexOf(':');
        return colon < 0 ? key : key.substring(0, colon);
    }

    /**
     * Writes one scalar field.
     *
     * <p>Full name lives on the account rather than the profile, which is why it
     * is handled here rather than being a column like the rest.
     */
    private boolean applyField(CandidateProfile profile, String field, String value) {
        switch (field) {
            case "fullName" -> {
                if (profile.getUser() == null) {
                    return false;
                }
                profile.getUser().setFullName(TextUtils.truncate(value, 160));
            }
            case "phone" -> profile.setPhone(TextUtils.truncate(value, 40));
            case "location" -> profile.setLocation(TextUtils.truncate(value, 160));
            case "headline" -> profile.setHeadline(TextUtils.truncate(value, 200));
            case "summary" -> profile.setSummary(value);
            case "primaryRole" -> profile.setPrimaryRole(TextUtils.truncate(value, 120));
            case "linkedinUrl" -> profile.setLinkedinUrl(TextUtils.truncate(value, 300));
            case "githubUrl" -> profile.setGithubUrl(TextUtils.truncate(value, 300));
            case "portfolioUrl" -> profile.setPortfolioUrl(TextUtils.truncate(value, 300));
            case "seniority" -> profile.setSeniority(parseEnum(Seniority.class, value, Seniority.UNSPECIFIED));
            case "yearsExperience" -> {
                BigDecimal years = parseDecimal(value);
                if (years == null) {
                    return false;
                }
                profile.setYearsExperience(years.setScale(1, java.math.RoundingMode.HALF_UP));
            }
            // No case writes cgpa, cgpaScale, cgpaSource, cgpaRecordedBy or
            // cgpaRecordedAt, and this default is what keeps it that way if a
            // key for one is ever proposed.
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean addSkill(CandidateProfile profile, String slug, SkillOrigin origin) {
        if (!TextUtils.hasText(slug) || profile.getSkills().size() >= MAX_SKILLS) {
            return false;
        }
        Optional<Skill> resolved = skillResolver.resolve(slug);
        if (resolved.isEmpty()) {
            return false;
        }
        UUID skillId = resolved.get().getId();
        boolean held = profile.getSkills().stream()
                .anyMatch(existing -> existing.getSkill() != null
                        && skillId.equals(existing.getSkill().getId()));
        if (held) {
            return false;
        }
        CandidateSkill candidateSkill = new CandidateSkill();
        candidateSkill.setCandidate(profile);
        candidateSkill.setSkill(resolved.get());
        candidateSkill.setOrigin(origin);
        candidateSkill.setEvidence(origin == SkillOrigin.AI_SUGGESTION
                ? "Read from your resume and confirmed by you"
                : "Read from your resume");
        profile.getSkills().add(candidateSkill);
        return true;
    }

    private boolean addExperience(CandidateProfile profile, Map<String, String> data) {
        String company = data.get("companyName");
        String title = data.get("title");
        if (!TextUtils.hasText(company) && !TextUtils.hasText(title)) {
            return false;
        }
        CandidateExperience experience = new CandidateExperience();
        experience.setCandidate(profile);
        experience.setCompanyName(TextUtils.truncate(orPlaceholder(company, "Unspecified"), 200));
        experience.setTitle(TextUtils.truncate(orPlaceholder(title, "Unspecified"), 200));
        experience.setLocation(TextUtils.truncate(trimToNull(data.get("location")), 160));
        experience.setStartDate(parseFlexibleDate(data.get("startDate")));
        experience.setEndDate(parseFlexibleDate(data.get("endDate")));
        experience.setCurrent(Boolean.parseBoolean(data.get("current")));
        experience.setDescription(trimToNull(data.get("description")));
        experience.setDisplayOrder(profile.getExperiences().size());
        profile.getExperiences().add(experience);
        return true;
    }

    private boolean addEducation(CandidateProfile profile, Map<String, String> data) {
        String institution = trimToNull(data.get("institution"));
        if (institution == null) {
            return false;
        }
        CandidateEducation entry = new CandidateEducation();
        entry.setCandidate(profile);
        entry.setInstitution(TextUtils.truncate(institution, 200));
        entry.setDegree(TextUtils.truncate(trimToNull(data.get("degree")), 160));
        entry.setFieldOfStudy(TextUtils.truncate(trimToNull(data.get("fieldOfStudy")), 160));
        entry.setStartYear(parseYear(data.get("startYear")));
        entry.setEndYear(parseYear(data.get("endYear")));
        // The grade is stored on the education entry as the free text it was
        // written as. It is not a CGPA and is never read as one.
        entry.setGrade(TextUtils.truncate(trimToNull(data.get("grade")), 60));
        entry.setDisplayOrder(profile.getEducation().size());
        profile.getEducation().add(entry);
        return true;
    }

    private static Integer parseYear(String value) {
        try {
            return TextUtils.hasText(value) ? Integer.valueOf(value.strip()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(String value) {
        try {
            return new BigDecimal(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Moves a candidate into review once there is something for them to review. */
    @Transactional
    public void markAwaitingProposalReview(CandidateProfile profile) {
        advanceOnboarding(profile, OnboardingStage.PROFILE_REVIEW);
        profileRepository.save(profile);
    }

    private void replaceSkills(CandidateProfile profile, List<SkillItem> items) {
        Set<String> seen = new LinkedHashSet<>();
        for (SkillItem item : items) {
            if (seen.size() >= MAX_SKILLS) {
                break;
            }
            Optional<Skill> resolved = skillResolver.resolve(item.name());
            if (resolved.isEmpty() || !seen.add(resolved.get().getSlug())) {
                continue;
            }
            CandidateSkill skill = new CandidateSkill();
            skill.setCandidate(profile);
            skill.setSkill(resolved.get());
            skill.setProficiency(trimToNull(item.proficiency()));
            skill.setYears(item.years());
            skill.setOrigin(parseEnum(SkillOrigin.class, item.origin(), SkillOrigin.MANUAL));
            skill.setEvidence(TextUtils.truncate(trimToNull(item.evidence()), 400));
            profile.getSkills().add(skill);
        }
    }

    private void replaceExperiences(CandidateProfile profile, List<ExperienceItem> items) {
        int order = 0;
        for (ExperienceItem item : items) {
            CandidateExperience experience = new CandidateExperience();
            experience.setCandidate(profile);
            experience.setCompanyName(TextUtils.truncate(item.companyName().strip(), 200));
            experience.setTitle(TextUtils.truncate(item.title().strip(), 200));
            experience.setLocation(TextUtils.truncate(trimToNull(item.location()), 160));
            experience.setStartDate(item.startDate());
            experience.setEndDate(item.current() ? null : item.endDate());
            experience.setCurrent(item.current());
            experience.setDescription(trimToNull(item.description()));
            experience.setDisplayOrder(order++);
            profile.getExperiences().add(experience);
        }
    }

    private void replaceEducation(CandidateProfile profile, List<EducationItem> items) {
        int order = 0;
        for (EducationItem item : items) {
            CandidateEducation entry = new CandidateEducation();
            entry.setCandidate(profile);
            entry.setInstitution(TextUtils.truncate(item.institution().strip(), 200));
            entry.setDegree(TextUtils.truncate(trimToNull(item.degree()), 160));
            entry.setFieldOfStudy(TextUtils.truncate(trimToNull(item.fieldOfStudy()), 160));
            entry.setStartYear(item.startYear());
            entry.setEndYear(item.endYear());
            entry.setGrade(TextUtils.truncate(trimToNull(item.grade()), 60));
            entry.setDisplayOrder(order++);
            profile.getEducation().add(entry);
        }
    }

    /**
     * Brings one preference type in line with the request, by difference.
     *
     * <p>This used to delete every row and re-insert the lot. That reads as the
     * obvious way to "replace" a list, and it could not save the same values
     * twice: Spring Data's derived delete queues {@code em.remove} calls rather
     * than issuing SQL, and Hibernate's action queue runs inserts <em>before</em>
     * deletes. Re-inserting a value whose old row was still present violated
     * {@code uq_candidate_preference_values}, so any save that kept even one
     * existing value failed with a conflict. In practice that was every real
     * edit a student made after their first.
     *
     * <p>Working by difference removes the collision rather than sequencing
     * around it: the rows to insert and the rows to delete are disjoint by
     * construction, because a value is in exactly one of those sets. Ordering
     * between them stops mattering at all, which a flush would not achieve — it
     * would only make the ordering safe for as long as somebody remembered it.
     *
     * <p>It also stops a save churning rows it did not change, so a student who
     * edits one entry keeps the identity of the others.
     *
     * <p><b>Comparison is exact.</b> The unique constraint is case-sensitive, so
     * "Java" and "java" are genuinely different rows and changing between them
     * is a real edit that must be applied. That is deliberately not the same as
     * the request-level de-duplication below, which stays case-insensitive so a
     * student cannot enter both spellings at once.
     */
    private <E extends Enum<E>> boolean replacePreferenceValues(CandidateProfile profile, PreferenceType type,
                                                                List<String> values, Class<E> constrainedTo) {
        List<String> requested = normalisePreferenceValues(values, constrainedTo);
        List<CandidatePreferenceValue> existing =
                preferenceValueRepository.findByCandidateIdAndValueType(profile.getId(), type);

        // The constraint guarantees one row per value, so this cannot collide.
        Map<String, CandidatePreferenceValue> existingByValue = new HashMap<>();
        for (CandidatePreferenceValue row : existing) {
            existingByValue.put(row.getValue(), row);
        }

        List<CandidatePreferenceValue> inserts = new ArrayList<>();
        Set<String> retained = new HashSet<>();
        boolean reordered = false;
        for (int position = 0; position < requested.size(); position++) {
            String value = requested.get(position);
            CandidatePreferenceValue row = existingByValue.get(value);
            if (row == null) {
                inserts.add(CandidatePreferenceValue.of(profile, type, value, position));
                continue;
            }
            retained.add(value);
            if (row.getDisplayOrder() != position) {
                reordered = true;
                // Managed entity: this is a dirty-checked UPDATE. display_order
                // is in no unique constraint, so reordering cannot collide, and
                // without it a student reordering their list would see nothing
                // happen — the values are equal, so nothing else would change.
                row.setDisplayOrder(position);
            }
        }

        List<CandidatePreferenceValue> removals = existing.stream()
                .filter(row -> !retained.contains(row.getValue()))
                .toList();

        if (!removals.isEmpty()) {
            preferenceValueRepository.deleteAll(removals);
        }
        if (!inserts.isEmpty()) {
            preferenceValueRepository.saveAll(inserts);
        }
        // Reported from the same comparison that decided what to write, so the
        // answer cannot drift from what actually happened. Reordering counts:
        // the values are equal but the stored order is not, and Phase 15A
        // treats that as a real edit.
        return !inserts.isEmpty() || !removals.isEmpty() || reordered;
    }

    /**
     * Applies the scalar preferences and says whether any of them moved.
     *
     * <p>Compared against the values already stored, using the same
     * normalisation the setters apply, so a payload that round-trips a
     * previously saved value reads as unchanged rather than as an edit.
     */
    private boolean applyPreferenceScalars(CandidatePreferences preferences, PreferencesPayload payload) {
        String currency = trimToNull(payload.salaryCurrency());
        String period = trimToNull(payload.salaryPeriod());

        boolean changed = numberChanged(preferences.getSalaryMin(), payload.salaryMin())
                || numberChanged(preferences.getSalaryMax(), payload.salaryMax())
                || !java.util.Objects.equals(preferences.getSalaryCurrency(), currency)
                || !java.util.Objects.equals(preferences.getSalaryPeriod(), period)
                || preferences.isOpenToRelocation() != payload.openToRelocation()
                || numberChanged(preferences.getMinExperienceYears(), payload.minExperienceYears())
                || numberChanged(preferences.getMaxExperienceYears(), payload.maxExperienceYears())
                || preferences.isImmediateAlerts() != payload.immediateAlerts()
                || preferences.isDailyDigest() != payload.dailyDigest();

        preferences.setSalaryMin(payload.salaryMin());
        preferences.setSalaryMax(payload.salaryMax());
        preferences.setSalaryCurrency(currency);
        preferences.setSalaryPeriod(period);
        preferences.setOpenToRelocation(payload.openToRelocation());
        preferences.setMinExperienceYears(payload.minExperienceYears());
        preferences.setMaxExperienceYears(payload.maxExperienceYears());
        preferences.setImmediateAlerts(payload.immediateAlerts());
        preferences.setDailyDigest(payload.dailyDigest());
        return changed;
    }

    /**
     * Whether a decimal preference actually moved.
     *
     * <p>{@code BigDecimal.equals} compares scale as well as value, so a client
     * echoing back "1200000.00" where "1200000" was stored would read as an
     * edit and cost a rescore for nothing. Numeric comparison is the honest
     * question here.
     */
    private boolean numberChanged(java.math.BigDecimal stored, java.math.BigDecimal incoming) {
        if (stored == null || incoming == null) {
            return stored != incoming;
        }
        return stored.compareTo(incoming) != 0;
    }

    /**
     * The request as it will be stored: trimmed, canonicalised, truncated and
     * de-duplicated, in the order the student gave.
     *
     * <p>Unchanged rules, lifted out of the replacement above so the difference
     * is computed against exactly what would have been written.
     */
    private <E extends Enum<E>> List<String> normalisePreferenceValues(List<String> values,
                                                                       Class<E> constrainedTo) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalised = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : values) {
            String value = trimToNull(raw);
            if (value == null) {
                continue;
            }
            if (constrainedTo != null) {
                // Enum-backed preferences are stored in their canonical enum form so
                // matching can compare them without re-parsing free text.
                E parsed = parseEnum(constrainedTo, value, null);
                if (parsed == null) {
                    continue;
                }
                value = parsed.name();
            }
            value = TextUtils.truncate(value, 200);
            if (!seen.add(value.toLowerCase(Locale.ROOT))) {
                continue;
            }
            normalised.add(value);
        }
        return normalised;
    }

    /**
     * Moves the candidate forward through onboarding, never backwards. Re-uploading
     * a resume after finishing should not drop someone back to step two.
     */
    private void advanceOnboarding(CandidateProfile profile, OnboardingStage target) {
        if (profile.getOnboardingStage().ordinal() < target.ordinal()) {
            profile.setOnboardingStage(target);
        }
    }

    /**
     * Completeness is a simple weighted count of the fields that actually improve
     * matching. It is computed, never stored by hand, so it cannot drift from the
     * data it describes.
     */
    void recomputeCompleteness(CandidateProfile profile) {
        int score = 0;
        if (TextUtils.hasText(profile.getHeadline())) {
            score += 10;
        }
        if (TextUtils.hasText(profile.getSummary())) {
            score += 10;
        }
        if (TextUtils.hasText(profile.getLocation())) {
            score += 10;
        }
        if (TextUtils.hasText(profile.getPrimaryRole())) {
            score += 10;
        }
        if (profile.getSeniority() != null && profile.getSeniority().isKnown()) {
            score += 10;
        }
        if (profile.getYearsExperience() != null) {
            score += 10;
        }
        int skillCount = profile.getSkills().size();
        if (skillCount >= 8) {
            score += 20;
        } else if (skillCount >= 3) {
            score += 12;
        } else if (skillCount > 0) {
            score += 6;
        }
        if (!profile.getExperiences().isEmpty()) {
            score += 10;
        }
        if (!profile.getEducation().isEmpty()) {
            score += 5;
        }
        if (!preferenceValueRepository
                .findByCandidateIdAndValueType(profile.getId(), PreferenceType.TARGET_ROLE).isEmpty()) {
            score += 5;
        }
        profile.setProfileCompleteness(Math.min(100, score));
    }

    private LocalDate parseFlexibleDate(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            if (trimmed.matches("\\d{4}")) {
                return LocalDate.of(Integer.parseInt(trimmed), 1, 1);
            }
            if (trimmed.matches("\\d{4}-\\d{2}")) {
                return LocalDate.parse(trimmed + "-01");
            }
            if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(trimmed);
            }
        } catch (DateTimeParseException | NumberFormatException ex) {
            log.debug("Ignoring unparseable date '{}' from resume extraction", trimmed);
        }
        return null;
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String orPlaceholder(String value, String placeholder) {
        return TextUtils.hasText(value) ? value.strip() : placeholder;
    }
}
