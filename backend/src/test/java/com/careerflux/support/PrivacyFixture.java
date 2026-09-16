package com.careerflux.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.Resume;
import com.careerflux.candidate.domain.ResumeParseStatus;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.repository.ResumeRepository;
import com.careerflux.candidate.service.ResumeStorageService;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.institution.domain.Batch;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Committed accounts, resumes and placement records for the Phase 2B privacy
 * tests, and their exact removal afterwards.
 *
 * <p>Deletion, erasure and retention open their own transactions and move real
 * files, so a test that wrapped them in one rolled-back transaction would hide
 * the very failure paths they are tested for. These tests commit instead, and
 * this fixture records every account and requirement it creates and removes
 * precisely those, in foreign-key order, then proves nothing it made is left:
 * rows, audit entries and resume files. It removes nothing it did not create,
 * apart from the retention-run audit rows written while a test was running.
 *
 * <p>Call {@link #begin()} before a test and {@link #cleanUp()} after it.
 */
@Component
@Profile("test")
public class PrivacyFixture {

    public static final String PASSWORD = "PrivacyFixture123!";

    public record Account(UUID userId, UUID profileId, String email, String fullName) {
    }

    private final UserRepository users;
    private final CandidateProfileRepository profiles;
    private final StaffScopeRepository scopes;
    private final ResumeRepository resumes;
    private final ResumeStorageService storage;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final Path resumeRoot;
    private final TransactionTemplate committed;

    private final Set<UUID> createdUsers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> createdProfiles = ConcurrentHashMap.newKeySet();
    private final Set<UUID> createdRequirements = ConcurrentHashMap.newKeySet();
    private Instant startedAt = Instant.now();

    public PrivacyFixture(UserRepository users, CandidateProfileRepository profiles, StaffScopeRepository scopes,
                          ResumeRepository resumes, ResumeStorageService storage, PasswordEncoder passwordEncoder,
                          EntityManager entityManager, ObjectMapper objectMapper, CareerFluxProperties properties,
                          PlatformTransactionManager transactionManager) {
        this.users = users;
        this.profiles = profiles;
        this.scopes = scopes;
        this.resumes = resumes;
        this.storage = storage;
        this.passwordEncoder = passwordEncoder;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.resumeRoot = Path.of(properties.storage().resumeDir()).toAbsolutePath().normalize();
        this.committed = new TransactionTemplate(transactionManager);
        this.committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void begin() {
        createdUsers.clear();
        createdProfiles.clear();
        createdRequirements.clear();
        startedAt = Instant.now();
    }

    public <T> T inTransaction(Supplier<T> work) {
        return committed.execute(status -> work.get());
    }

    // --------------------------------------------------------------- accounts

    public Account student(String fullName, Institution college, Department department, Batch batch) {
        return inTransaction(() -> {
            User user = new User();
            user.setEmail("p2b-" + UUID.randomUUID() + "@" + (college.getEmailDomains() == null
                    ? "example.com" : college.getEmailDomains().split(",")[0].strip()));
            user.setFullName(fullName);
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            user.setRole(UserRole.STUDENT);
            user.setStatus(UserStatus.ACTIVE);
            user.setInstitution(college);
            user.setDepartment(department);
            user.setBatch(batch);
            User saved = users.saveAndFlush(user);
            CandidateProfile profile = new CandidateProfile();
            profile.setUser(saved);
            profile.setInstitution(college);
            UUID profileId = profiles.saveAndFlush(profile).getId();
            createdUsers.add(saved.getId());
            createdProfiles.add(profileId);
            return new Account(saved.getId(), profileId, saved.getEmail(), fullName);
        });
    }

    public Account staff(UserRole role, Institution college, Department grantedDepartment) {
        return inTransaction(() -> {
            User user = new User();
            user.setEmail("p2b-staff-" + UUID.randomUUID() + "@example.com");
            user.setFullName("Staff Member");
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            user.setRole(role);
            user.setStatus(UserStatus.ACTIVE);
            user.setInstitution(college);
            User saved = users.saveAndFlush(user);
            if (grantedDepartment != null) {
                scopes.saveAndFlush(StaffScope.forDepartment(saved, college, grantedDepartment));
            }
            createdUsers.add(saved.getId());
            return new Account(saved.getId(), null, saved.getEmail(), saved.getFullName());
        });
    }

    public String login(MockMvc mockMvc, String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    // ---------------------------------------------------------------- resumes

    /** A committed resume with its file on disk, at a chosen upload time. */
    public UUID resume(Account student, String text, boolean active, Instant uploadedAt) {
        return inTransaction(() -> {
            CandidateProfile profile = profiles.findById(student.profileId()).orElseThrow();
            byte[] content = text.getBytes(StandardCharsets.UTF_8);
            Resume resume = new Resume();
            resume.setCandidate(profile);
            resume.setOriginalFilename("cv.txt");
            resume.setContentType("text/plain");
            resume.setSizeBytes((long) content.length);
            resume.setStoragePath(storage.store(profile.getId(), content, "cv.txt"));
            resume.setExtractedText(text);
            resume.setParseStatus(ResumeParseStatus.PARSED);
            resume.setActive(active);
            resume.setUploadedAt(uploadedAt);
            return resumes.saveAndFlush(resume).getId();
        });
    }

    public Path fileOf(UUID resumeId) {
        String key = inTransaction(() -> resumes.findById(resumeId).map(Resume::getStoragePath).orElse(null));
        return key == null ? null : resumeRoot.resolve(key);
    }

    public Path resumeRoot() {
        return resumeRoot;
    }

    // ---------------------------------------------------------- requirements

    /** An open requirement created through the API by a placement coordinator, aimed at one department. */
    public UUID openRequirement(MockMvc mockMvc, String coordinatorToken, Department department) throws Exception {
        String created = mockMvc.perform(post("/api/requirements").header("Authorization", "Bearer " + coordinatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\":\"Deccan Fintech Labs\",\"roleTitle\":\"Graduate Engineer\","
                                + "\"departmentIds\":[\"" + department.getId() + "\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(created).get("id").asText());
        createdRequirements.add(id);
        mockMvc.perform(patch("/api/requirements/" + id).header("Authorization", "Bearer " + coordinatorToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isOk());
        return id;
    }

    /**
     * Placement history with a student's own response in it: shortlisted,
     * invited by the college, and answered "interested" by the student.
     */
    public void placementHistory(MockMvc mockMvc, String coordinatorToken, UUID requirementId, Account student,
                                 String studentToken) throws Exception {
        mockMvc.perform(post("/api/requirements/" + requirementId + "/shortlist")
                        .header("Authorization", "Bearer " + coordinatorToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + student.profileId() + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/api/requirements/" + requirementId + "/shortlist/" + student.profileId() + "/stage")
                        .header("Authorization", "Bearer " + coordinatorToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"INVITED\",\"note\":\"Invited to the aptitude round\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/candidate/placements/" + requirementId + "/response")
                        .header("Authorization", "Bearer " + studentToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"interested\",\"note\":\"Looking forward to it\"}"))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- cleanup

    /** Removes everything this fixture created and proves none of it is left. */
    public void cleanUp() {
        if (createdUsers.isEmpty() && createdRequirements.isEmpty()) {
            return;
        }
        List<UUID> userIds = new ArrayList<>(createdUsers);
        List<String> userIdText = userIds.stream().map(UUID::toString).toList();
        inTransaction(() -> {
            if (!createdRequirements.isEmpty()) {
                // Cascades to their shortlists and stage changes (V10, V13).
                entityManager.createNativeQuery("delete from company_requirements where id in (:ids)")
                        .setParameter("ids", createdRequirements).executeUpdate();
            }
            if (!userIds.isEmpty()) {
                entityManager.createNativeQuery("""
                        delete from placement_stage_changes where shortlist_id in (
                          select s.id from company_requirement_shortlists s
                          join candidate_profiles p on p.id = s.candidate_id where p.user_id in (:ids))
                        """).setParameter("ids", userIds).executeUpdate();
                entityManager.createNativeQuery("""
                        delete from company_requirement_shortlists where candidate_id in (
                          select id from candidate_profiles where user_id in (:ids))
                        """).setParameter("ids", userIds).executeUpdate();
                for (String statement : List.of(
                        "delete from account_erasures where subject_user_id in (:ids)",
                        "delete from consent_records where user_id in (:ids)",
                        "delete from ai_usage_counters where scope_id in (:ids)",
                        "delete from candidate_profiles where user_id in (:ids)")) {
                    entityManager.createNativeQuery(statement).setParameter("ids", userIds).executeUpdate();
                }
                entityManager.createNativeQuery(
                                "delete from audit_events where actor_user_id in (:ids) or entity_id in (:texts)")
                        .setParameter("ids", userIds).setParameter("texts", userIdText).executeUpdate();
            }
            entityManager.createNativeQuery(
                            "delete from audit_events where action = 'RETENTION_SWEEP' and occurred_at >= :since")
                    .setParameter("since", startedAt).executeUpdate();
            if (!userIds.isEmpty()) {
                entityManager.createNativeQuery("delete from users where id in (:ids)")
                        .setParameter("ids", userIds).executeUpdate();
            }
            return null;
        });

        createdProfiles.forEach(storage::deleteAllForCandidate);
        storage.listQuarantined().stream()
                .filter(entry -> !entry.lastModified().isBefore(startedAt.minusSeconds(1)))
                .forEach(entry -> storage.purge(entry.quarantineKey()));

        // Proven, not assumed.
        if (!userIds.isEmpty()) {
            long usersLeft = inTransaction(() -> ((Number) entityManager
                    .createNativeQuery("select count(*) from users where id in (:ids)")
                    .setParameter("ids", userIds).getSingleResult()).longValue());
            long auditLeft = inTransaction(() -> ((Number) entityManager
                    .createNativeQuery("select count(*) from audit_events where actor_user_id in (:ids) "
                            + "or entity_id in (:texts)")
                    .setParameter("ids", userIds).setParameter("texts", userIdText).getSingleResult()).longValue());
            assertThat(usersLeft).describedAs("accounts left behind by the fixture").isZero();
            assertThat(auditLeft).describedAs("audit rows left behind by the fixture").isZero();
        }
        createdProfiles.forEach(profileId -> assertThat(Files.exists(resumeRoot.resolve(profileId.toString())))
                .describedAs("resume directory left behind for " + profileId).isFalse());
        createdUsers.clear();
        createdProfiles.clear();
        createdRequirements.clear();
    }
}
