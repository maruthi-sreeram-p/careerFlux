package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.requirement.repository.CompanyRequirementRepository;
import com.careerflux.shortlist.repository.ShortlistRepository;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.Permission;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Splitting shortlisting away from requirement authoring.
 *
 * <p>A coordinator needed to put their own department's students forward. The
 * only permission that unlocked that was {@code PLACEMENT_DRIVE_MANAGE}, which
 * also unlocks writing and publishing company requirements — unscoped, for the
 * whole college. Granting it would have worked and would have handed every
 * coordinator authority nobody asked for.
 *
 * <p>So the permission was split. What these tests are really guarding is the
 * half that did <em>not</em> move: a coordinator can now shortlist, and still
 * cannot author. The scope machinery underneath is unchanged — it already
 * confined every write to the caller's own students — and the cross-department
 * cases below exist to prove that is still true now that a coordinator can
 * reach the endpoint at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShortlistPermissionSplitTest {

    private static final String PASSWORD = "IntegrationTest123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandidateProfileRepository profileRepository;

    @Autowired
    private CandidateProfileService profileService;

    @Autowired
    private StaffScopeRepository staffScopes;

    @Autowired
    private ShortlistRepository shortlists;

    @Autowired
    private CompanyRequirementRepository requirements;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    // ------------------------------------------------------- the model itself

    @Nested
    @DisplayName("the permission model")
    class Model {

        @Test
        @DisplayName("a coordinator may shortlist but may not author a requirement")
        void coordinatorHoldsOnlyTheNarrowOne() {
            assertThat(UserRole.DEPARTMENT_COORDINATOR.has(Permission.PLACEMENT_SHORTLIST_MANAGE))
                    .describedAs("shortlisting within their department is the point of the split")
                    .isTrue();
            assertThat(UserRole.DEPARTMENT_COORDINATOR.has(Permission.PLACEMENT_DRIVE_MANAGE))
                    .describedAs("authoring a requirement is unscoped and is not theirs")
                    .isFalse();
        }

        @Test
        @DisplayName("the placement coordinator holds both")
        void placementCoordinatorHoldsBoth() {
            assertThat(UserRole.PLACEMENT_COORDINATOR.has(Permission.PLACEMENT_SHORTLIST_MANAGE)).isTrue();
            assertThat(UserRole.PLACEMENT_COORDINATOR.has(Permission.PLACEMENT_DRIVE_MANAGE)).isTrue();
        }

        @Test
        @DisplayName("nobody else acquired either of them")
        void nobodyElseGainedPlacementAuthority() {
            // The college administrator used to be listed here. It is now part of
            // the placement coordinator, which holds both by design.
            for (UserRole role : new UserRole[] {UserRole.STUDENT, UserRole.PORTAL_ADMIN}) {
                assertThat(role.has(Permission.PLACEMENT_SHORTLIST_MANAGE))
                        .describedAs("%s must not be able to shortlist", role)
                        .isFalse();
                assertThat(role.has(Permission.PLACEMENT_DRIVE_MANAGE))
                        .describedAs("%s must not be able to author a requirement", role)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("viewing was not touched")
        void viewPermissionUnchanged() {
            assertThat(UserRole.DEPARTMENT_COORDINATOR.has(Permission.PLACEMENT_DRIVE_VIEW)).isTrue();
            assertThat(UserRole.PLACEMENT_COORDINATOR.has(Permission.PLACEMENT_DRIVE_VIEW)).isTrue();
        }
    }

    // ------------------------------------------------------------ coordinator

    @Nested
    @DisplayName("a coordinator")
    class Coordinator {

        @Test
        @DisplayName("can shortlist a student in their own department, and take them off again")
        void shortlistsWithinScope() throws Exception {
            String officer = signIn(staff("split-officer@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            String coordinator = signIn(coordinatorFor(institutions.exampleCse(),
                    "split-cse-coord@example.com"));
            UUID candidate = student("split-cse-student@example.com", institutions.exampleCse());

            mockMvc.perform(post("/api/requirements/" + requirement + "/shortlist")
                            .header("Authorization", "Bearer " + coordinator)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"candidateId\":\"%s\"}".formatted(candidate)))
                    .andExpect(status().isCreated());

            assertThat(shortlists.countByRequirementId(UUID.fromString(requirement))).isEqualTo(1);

            mockMvc.perform(delete("/api/requirements/" + requirement + "/shortlist/" + candidate)
                            .header("Authorization", "Bearer " + coordinator))
                    .andExpect(status().isOk());

            assertThat(shortlists.countByRequirementId(UUID.fromString(requirement))).isZero();
        }

        @Test
        @DisplayName("cannot shortlist a student from another department")
        void cannotCrossDepartment() throws Exception {
            // The permission is now reachable, so this is the check that matters:
            // reaching the endpoint is not the same as reaching the student.
            String officer = signIn(staff("split-officer2@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            String coordinator = signIn(coordinatorFor(institutions.exampleCse(),
                    "split-cse-coord2@example.com"));
            UUID mechStudent = student("split-mech-student@example.com", institutions.exampleMech());

            mockMvc.perform(post("/api/requirements/" + requirement + "/shortlist")
                            .header("Authorization", "Bearer " + coordinator)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"candidateId\":\"%s\"}".formatted(mechStudent)))
                    // Not-found, not forbidden: a coordinator must not learn that a
                    // student id exists in a department they cannot see.
                    .andExpect(status().isNotFound());

            assertThat(shortlists.countByRequirementId(UUID.fromString(requirement))).isZero();
        }

        @Test
        @DisplayName("cannot shortlist a student from another college")
        void cannotCrossInstitution() throws Exception {
            String officer = signIn(staff("split-officer3@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            String coordinator = signIn(coordinatorFor(institutions.exampleCse(),
                    "split-cse-coord3@example.com"));
            UUID theirStudent = student("split-rival-student@example.com", institutions.rivalCse());

            mockMvc.perform(post("/api/requirements/" + requirement + "/shortlist")
                            .header("Authorization", "Bearer " + coordinator)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"candidateId\":\"%s\"}".formatted(theirStudent)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("still cannot create a company requirement")
        void cannotCreateRequirement() throws Exception {
            // The whole reason for the split. If this ever returns 201, the
            // coordinator has acquired institution-wide authoring authority.
            String coordinator = signIn(coordinatorFor(institutions.exampleCse(),
                    "split-cse-coord4@example.com"));

            mockMvc.perform(post("/api/requirements")
                            .header("Authorization", "Bearer " + coordinator)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"companyName":"Sneaky Ltd","roleTitle":"Backend Developer"}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("still cannot edit, publish or close a requirement")
        void cannotMutateRequirement() throws Exception {
            String officer = signIn(staff("split-officer5@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            String coordinator = signIn(coordinatorFor(institutions.exampleCse(),
                    "split-cse-coord5@example.com"));

            for (String body : new String[] {
                    "{\"companyName\":\"Renamed By Coordinator\"}",
                    "{\"status\":\"CLOSED\"}",
                    "{\"minCgpa\":9.9}"}) {
                mockMvc.perform(patch("/api/requirements/" + requirement)
                                .header("Authorization", "Bearer " + coordinator)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isForbidden());
            }
        }

        @Test
        @DisplayName("can still read the requirement and its candidates, as before")
        void readingIsUnchanged() throws Exception {
            String officer = signIn(staff("split-officer6@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            String coordinator = signIn(coordinatorFor(institutions.exampleCse(),
                    "split-cse-coord6@example.com"));

            mockMvc.perform(get("/api/requirements/" + requirement)
                            .header("Authorization", "Bearer " + coordinator))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/requirements/" + requirement + "/candidates")
                            .header("Authorization", "Bearer " + coordinator))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/requirements/" + requirement + "/shortlist")
                            .header("Authorization", "Bearer " + coordinator))
                    .andExpect(status().isOk());
        }
    }

    // --------------------------------------------------------------- officer

    @Nested
    @DisplayName("a placement officer")
    class Officer {

        @Test
        @DisplayName("can still shortlist and unshortlist")
        void shortlistStillWorks() throws Exception {
            String officer = signIn(staff("split-officer7@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            UUID candidate = student("split-officer-student@example.com", institutions.exampleMech());

            mockMvc.perform(post("/api/requirements/" + requirement + "/shortlist")
                            .header("Authorization", "Bearer " + officer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"candidateId\":\"%s\"}".formatted(candidate)))
                    .andExpect(status().isCreated());
            mockMvc.perform(delete("/api/requirements/" + requirement + "/shortlist/" + candidate)
                            .header("Authorization", "Bearer " + officer))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("can still author requirements")
        void authoringStillWorks() throws Exception {
            String officer = signIn(staff("split-officer8@example.com", UserRole.PLACEMENT_COORDINATOR, null));

            String created = mockMvc.perform(post("/api/requirements")
                            .header("Authorization", "Bearer " + officer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"companyName":"Still Works Ltd","roleTitle":"Backend Developer"}
                                    """))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = objectMapper.readTree(created).get("id").asText();

            mockMvc.perform(patch("/api/requirements/" + id)
                            .header("Authorization", "Bearer " + officer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"OPEN\"}"))
                    .andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------- everyone else

    @Nested
    @DisplayName("everyone else")
    class Others {

        @Test
        @DisplayName("a student cannot shortlist")
        void studentCannot() throws Exception {
            String officer = signIn(staff("split-officer9@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            UUID candidate = student("split-self-student@example.com", institutions.exampleCse());
            String studentToken = signIn(userRepository.findById(
                    profileRepository.findById(candidate).orElseThrow().getUser().getId()).orElseThrow());

            mockMvc.perform(post("/api/requirements/" + requirement + "/shortlist")
                            .header("Authorization", "Bearer " + studentToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"candidateId\":\"%s\"}".formatted(candidate)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("any placement coordinator in the college may shortlist, not only the requirement's author")
        void anyPlacementCoordinatorMay() throws Exception {
            // This used to refuse a college administrator, on the grounds that
            // administering a college is not deciding who goes forward. In the
            // four-actor model the college's administrator is the placement
            // coordinator, whose job is exactly that decision.
            String officer = signIn(staff("split-officer10@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            UUID candidate = student("split-admin-student@example.com", institutions.exampleCse());
            String colleague = signIn(staff("split-admin@example.com", UserRole.PLACEMENT_COORDINATOR, null));

            mockMvc.perform(post("/api/requirements/" + requirement + "/shortlist")
                            .header("Authorization", "Bearer " + colleague)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"candidateId\":\"%s\"}".formatted(candidate)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("an anonymous caller is refused before anything else")
        void anonymousCannot() throws Exception {
            String officer = signIn(staff("split-officer11@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);

            mockMvc.perform(post("/api/requirements/" + requirement + "/shortlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"candidateId\":\"%s\"}".formatted(UUID.randomUUID())))
                    .andExpect(status().isUnauthorized());
        }
    }

    // --------------------------------------------------------------- helpers

    /** An OPEN requirement targeting no department, so it is visible to every coordinator. */
    private String openRequirement(String officerToken) throws Exception {
        String created = mockMvc.perform(post("/api/requirements")
                        .header("Authorization", "Bearer " + officerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyName":"Split Test Ltd","roleTitle":"Backend Developer"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();
        mockMvc.perform(patch("/api/requirements/" + id)
                        .header("Authorization", "Bearer " + officerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private User coordinatorFor(Department department, String email) {
        User coordinator = staff(email, UserRole.DEPARTMENT_COORDINATOR, department);
        staffScopes.save(StaffScope.forDepartment(coordinator, institutions.example(), department));
        return coordinator;
    }

    private User staff(String email, UserRole role, Department department) {
        User user = new User();
        user.setEmail(email);
        user.setFullName("Split " + role);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institutions.example());
        if (department != null) {
            user.setDepartment(department);
        }
        return userRepository.saveAndFlush(user);
    }

    /** @return the candidate profile id, which is what the shortlist API takes */
    private UUID student(String email, Department department) {
        Institution institution = department.getInstitution();
        User user = new User();
        user.setEmail(email);
        user.setFullName("Split Student");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(UserRole.STUDENT);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institution);
        user.setDepartment(department);
        userRepository.saveAndFlush(user);
        CandidateProfile profile = profileService.createForUser(user);
        return profile.getId();
    }

    private String signIn(User user) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
