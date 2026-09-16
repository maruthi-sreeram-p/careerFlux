package com.careerflux.privacy.erasure;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.shortlist.repository.ShortlistRepository;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carries out one erasure, inside a transaction its caller opened.
 *
 * <p>Anonymises in place rather than deleting. The account and the candidate
 * profile keep their rows, stripped of identity, because the college's
 * placement history refers to the profile and V10 and V13 cascade a deleted
 * profile into that history. Everything that belongs to the student alone is
 * deleted outright.
 *
 * <p><b>What survives, and why.</b> The role, college, department and batch stay
 * on the account, because historical placement reporting counts by them. The
 * shortlist entries and stage changes stay exactly as recorded, except that a
 * stage change the student made no longer carries their name. Audit rows are
 * untouched; they already identify people by id and role alone. Consent history
 * stays: it records which notice was accepted when and holds nothing personal.
 *
 * <p><b>Verified CGPA.</b> Removed. It may stay only where an existing placement
 * record explicitly requires it as evidence, and no placement record in this
 * schema holds or refers to a CGPA: shortlist entries (V10, V13) and stage
 * changes (V13) record who, which drive, which stage and when, nothing more.
 * Nothing here infers that a figure was the basis of a past selection.
 */
@Component
public class ErasureExecutor {

    /** What an erased account and the stage changes it made are labelled as. */
    public static final String ERASED_NAME = "Erased student";

    private final ErasureOperations operations;
    private final AccountErasureRepository erasures;
    private final UserRepository users;
    private final CandidateProfileRepository profiles;
    private final ShortlistRepository shortlists;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public ErasureExecutor(ErasureOperations operations, AccountErasureRepository erasures, UserRepository users,
                           CandidateProfileRepository profiles, ShortlistRepository shortlists,
                           PasswordEncoder passwordEncoder, ObjectMapper objectMapper) {
        this.operations = operations;
        this.erasures = erasures;
        this.users = users;
        this.profiles = profiles;
        this.shortlists = shortlists;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    /**
     * Erases the account a claimed request is about and completes the request.
     *
     * @param resumeFilesRemoved how many files the caller moved out of reach
     *                           before this transaction began
     * @return what was removed and kept, as counts
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Integer> erase(UUID erasureId, Instant at, int resumeFilesRemoved) {
        AccountErasure request = erasures.findById(erasureId)
                .orElseThrow(() -> new IllegalStateException("Erasure request disappeared while being carried out"));
        UUID userId = request.getSubjectUserId();
        UUID candidateId = profiles.findByUserId(userId).map(CandidateProfile::getId).orElse(null);

        Map<String, Integer> counts = new LinkedHashMap<>();
        if (candidateId != null) {
            // Proposals before resumes: a proposal refers to the resume it read.
            counts.put("aiProposals", operations.deleteProposals(candidateId));
            counts.put("resumes", operations.deleteResumes(candidateId));
            counts.put("skills", operations.deleteSkills(candidateId));
            counts.put("privateSkills", operations.deletePrivateSkills(candidateId));
            counts.put("experiences", operations.deleteExperiences(candidateId));
            counts.put("education", operations.deleteEducation(candidateId));
            counts.put("preferenceValues", operations.deletePreferenceValues(candidateId));
            counts.put("preferences", operations.deletePreferences(candidateId));
            counts.put("jobInteractions", operations.deleteJobInteractions(candidateId));
            counts.put("jobMatches", operations.deleteJobMatches(candidateId));
            counts.put("rematchRequests", operations.deleteRematchRequests(candidateId));
        }
        counts.put("notifications", operations.deleteNotifications(userId));
        counts.put("aiUsageCounters", operations.deleteAiUsageCounters(userId));
        counts.put("stageChangeLabelsAnonymised", operations.anonymiseStageChangeLabels(userId, ERASED_NAME));
        counts.put("resumeFiles", resumeFilesRemoved);

        // The bulk statements above cleared the persistence context, so what is
        // changed below is loaded fresh.
        User account = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("The account to erase no longer exists"));
        anonymiseAccount(account);
        users.saveAndFlush(account);

        if (candidateId != null) {
            CandidateProfile profile = profiles.findById(candidateId)
                    .orElseThrow(() -> new IllegalStateException("The profile to erase no longer exists"));
            anonymiseProfile(profile);
            profiles.saveAndFlush(profile);
            counts.put("placementRecordsPreserved", shortlists.findForCandidate(candidateId).size());
        }

        AccountErasure completed = erasures.findById(erasureId)
                .orElseThrow(() -> new IllegalStateException("Erasure request disappeared while being carried out"));
        completed.complete(at, json(counts));
        erasures.saveAndFlush(completed);
        return counts;
    }

    /**
     * Identity goes; the facts placement reporting counts by stay.
     *
     * <p>The address becomes one that cannot receive mail and is unique to this
     * row. The password becomes the hash of a random value that is discarded at
     * once, so nothing can match it and nothing is left to guess. Every session is
     * ended.
     */
    private void anonymiseAccount(User account) {
        account.setEmail("erased+" + account.getId() + "@erased.invalid");
        account.setFullName(ERASED_NAME);
        // 64 characters: BCrypt refuses anything over 72 bytes.
        account.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "")));
        account.setPasswordResetToken(null);
        account.setPasswordResetExpiresAt(null);
        account.setRollNumber(null);
        account.setLastLoginAt(null);
        account.setEmailVerified(false);
        account.setStatus(UserStatus.ERASED);
        account.revokeSessions();
    }

    private static void anonymiseProfile(CandidateProfile profile) {
        profile.setHeadline(null);
        profile.setSummary(null);
        profile.setLocation(null);
        profile.setPhone(null);
        profile.setLinkedinUrl(null);
        profile.setGithubUrl(null);
        profile.setPortfolioUrl(null);
        profile.setPrimaryRole(null);
        profile.setSeniority(Seniority.UNSPECIFIED);
        profile.setYearsExperience(null);
        profile.setProfileCompleteness(0);
        profile.recordReportedCgpa(null);
        profile.recordVerifiedCgpa(null, null);
    }

    private String json(Map<String, Integer> counts) {
        try {
            return objectMapper.writeValueAsString(counts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not record erasure counts", e);
        }
    }
}
