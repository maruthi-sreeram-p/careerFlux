package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.shortlist.domain.PlacementStage;
import com.careerflux.shortlist.repository.PlacementStageChangeRepository;
import com.careerflux.shortlist.repository.ShortlistRepository;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * The placement workflow over HTTP.
 *
 * <p>{@code PlacementStageTest} proves the matrix in isolation. This proves the
 * things a matrix cannot: that the rules are actually consulted, that a
 * coordinator reaching for another department's student is refused the same way
 * they always were, that a student can answer only for themselves, and that the
 * stage and its history are written together or not at all.
 *
 * <p>The stakes are worth stating. A defect here does not produce a wrong
 * number on a screen — it lets somebody record a placement decision they were
 * not entitled to make, about a student who is not theirs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PlacementWorkflowIntegrationTest {

    private static final String PASSWORD = "IntegrationTest123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandidateProfileService profileService;

    @Autowired
    private StaffScopeRepository staffScopes;

    @Autowired
    private ShortlistRepository shortlists;

    @Autowired
    private PlacementStageChangeRepository stageChanges;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------------------------------------------------------- the happy path

    @Nested
    @DisplayName("the drive as it is meant to run")
    class HappyPath {

        @Test
        @DisplayName("shortlisted, invited, the student says yes, the college selects")
        void fullWorkflow() throws Exception {
            String officer = signIn(staff("pw-officer@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());

            assertThat(stageOf(requirement, candidate.candidateId())).isEqualTo(PlacementStage.SHORTLISTED);

            staffMove(officer, requirement, candidate.candidateId(), "INVITED", 200);
            assertThat(stageOf(requirement, candidate.candidateId())).isEqualTo(PlacementStage.INVITED);

            // The student's answer, given by the student.
            studentRespond(candidate.token(), requirement, "interested", 200);
            assertThat(stageOf(requirement, candidate.candidateId())).isEqualTo(PlacementStage.INTERESTED);

            staffMove(officer, requirement, candidate.candidateId(), "SELECTED", 200);
            assertThat(stageOf(requirement, candidate.candidateId())).isEqualTo(PlacementStage.SELECTED);

            // Four rows: three moves, and nothing invented for the arrival.
            assertThat(stageChanges.countByShortlistId(shortlistId(requirement, candidate.candidateId())))
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("the college can stop at any point before the end")
        void notProceeding() throws Exception {
            String officer = signIn(staff("pw-officer2@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student2@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());

            staffMove(officer, requirement, candidate.candidateId(), "INVITED", 200);
            staffMove(officer, requirement, candidate.candidateId(), "NOT_PROCEEDING", 200);
            assertThat(stageOf(requirement, candidate.candidateId()))
                    .isEqualTo(PlacementStage.NOT_PROCEEDING);
        }

        @Test
        @DisplayName("a student can decline, and that is recorded as theirs")
        void studentDeclines() throws Exception {
            String officer = signIn(staff("pw-officer3@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student3@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());
            staffMove(officer, requirement, candidate.candidateId(), "INVITED", 200);

            studentRespond(candidate.token(), requirement, "declined", 200);

            assertThat(stageOf(requirement, candidate.candidateId())).isEqualTo(PlacementStage.DECLINED);
            var history = stageChanges.findByShortlistIdOrderByOccurredAtAsc(
                    shortlistId(requirement, candidate.candidateId()));
            var last = history.get(history.size() - 1);
            assertThat(last.getActorKind()).isEqualTo(PlacementStage.ActorKind.STUDENT);
            assertThat(last.getFromStage()).isEqualTo(PlacementStage.INVITED);
            assertThat(last.getToStage()).isEqualTo(PlacementStage.DECLINED);
        }
    }

    // -------------------------------------------------------- refused moves

    @Nested
    @DisplayName("moves the workflow refuses")
    class Refused {

        @Test
        @DisplayName("staff cannot skip to selected, or answer for the student")
        void staffCannotSkipOrAnswer() throws Exception {
            String officer = signIn(staff("pw-officer4@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student4@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());

            staffMove(officer, requirement, candidate.candidateId(), "SELECTED", 400);
            staffMove(officer, requirement, candidate.candidateId(), "INTERESTED", 400);
            staffMove(officer, requirement, candidate.candidateId(), "DECLINED", 400);
            assertThat(stageOf(requirement, candidate.candidateId()))
                    .describedAs("a refused move must not have moved anything")
                    .isEqualTo(PlacementStage.SHORTLISTED);
            assertThat(stageChanges.countByShortlistId(shortlistId(requirement, candidate.candidateId())))
                    .describedAs("a refused move must not have written history")
                    .isZero();
        }

        @Test
        @DisplayName("the same move twice is a conflict, not a silent repeat")
        void repeatedMoveIsRefused() throws Exception {
            String officer = signIn(staff("pw-officer5@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student5@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());

            staffMove(officer, requirement, candidate.candidateId(), "INVITED", 200);
            staffMove(officer, requirement, candidate.candidateId(), "INVITED", 409);
            assertThat(stageChanges.countByShortlistId(shortlistId(requirement, candidate.candidateId())))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("nothing moves out of a terminal stage")
        void terminalIsTheEnd() throws Exception {
            String officer = signIn(staff("pw-officer6@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student6@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());
            staffMove(officer, requirement, candidate.candidateId(), "NOT_PROCEEDING", 200);

            for (String target : new String[] {"INVITED", "SELECTED", "SHORTLISTED"}) {
                staffMove(officer, requirement, candidate.candidateId(), target, 400);
            }
            studentRespond(candidate.token(), requirement, "interested", 400);
        }

        @Test
        @DisplayName("a stage that does not exist is a bad request, not a server error")
        void unknownStage() throws Exception {
            String officer = signIn(staff("pw-officer7@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student7@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());

            staffMove(officer, requirement, candidate.candidateId(), "PROMOTED", 400);
        }

        @Test
        @DisplayName("a closed drive accepts no decisions")
        void closedRequirementIsFrozen() throws Exception {
            String officer = signIn(staff("pw-officer8@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student8@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());
            patchStatus(officer, requirement, "CLOSED");

            staffMove(officer, requirement, candidate.candidateId(), "INVITED", 400);
        }

        @Test
        @DisplayName("a student cannot answer an invitation after the drive closes")
        void closedRequirementFreezesTheStudentToo() throws Exception {
            // The freeze has to cover both actors. If it covers only staff, an
            // answer can still arrive after the drive was closed and reported
            // on, changing a placement record somebody has already counted.
            String officer = signIn(staff("pw-officer23@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student23@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());
            staffMove(officer, requirement, candidate.candidateId(), "INVITED", 200);
            patchStatus(officer, requirement, "CLOSED");

            studentRespond(candidate.token(), requirement, "interested", 400);
            studentRespond(candidate.token(), requirement, "declined", 400);
            assertThat(stageOf(requirement, candidate.candidateId()))
                    .isEqualTo(PlacementStage.INVITED);
            assertThat(stageChanges.countByShortlistId(
                    shortlistId(requirement, candidate.candidateId()))).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------- authority

    @Nested
    @DisplayName("who may move a candidate")
    class Authority {

        @Test
        @DisplayName("a coordinator moves their own department's student")
        void coordinatorInScope() throws Exception {
            String officer = signIn(staff("pw-officer9@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-cse-student@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());
            String coordinator = signIn(coordinatorFor(institutions.exampleCse(), "pw-coord@example.com"));

            staffMove(coordinator, requirement, candidate.candidateId(), "INVITED", 200);
        }

        @Test
        @DisplayName("a coordinator cannot move another department's student")
        void coordinatorOutOfScope() throws Exception {
            // Not-found, exactly as adding and removing already answer. A
            // coordinator must not learn that a student id exists elsewhere.
            String officer = signIn(staff("pw-officer10@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture mech = student("pw-mech-student@example.com", institutions.exampleMech());
            shortlist(officer, requirement, mech.candidateId());
            String coordinator = signIn(coordinatorFor(institutions.exampleCse(), "pw-coord2@example.com"));

            staffMove(coordinator, requirement, mech.candidateId(), "INVITED", 404);
            assertThat(stageOf(requirement, mech.candidateId())).isEqualTo(PlacementStage.SHORTLISTED);
        }

        @Test
        @DisplayName("an officer cannot reach another college's candidate")
        void crossInstitution() throws Exception {
            String officer = signIn(staff("pw-officer11@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture theirs = student("pw-rival-student@example.com", institutions.rivalCse());

            staffMove(officer, requirement, theirs.candidateId(), "INVITED", 404);
        }

        @Test
        @DisplayName("any placement coordinator in the college may move a candidate, not only the one who "
                + "shortlisted them")
        void anyPlacementCoordinatorMay() throws Exception {
            // This used to refuse a college administrator. In the four-actor model
            // the college's administrator is the placement coordinator, which runs
            // placement across the whole college.
            String officer = signIn(staff("pw-officer12@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student12@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());
            String colleague = signIn(staff("pw-admin@example.com", UserRole.PLACEMENT_COORDINATOR, null));

            staffMove(colleague, requirement, candidate.candidateId(), "INVITED", 200);
        }

        @Test
        @DisplayName("a student cannot use the staff endpoint, even on their own record")
        void studentCannotActAsStaff() throws Exception {
            String officer = signIn(staff("pw-officer13@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student13@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());
            staffMove(officer, requirement, candidate.candidateId(), "INVITED", 200);

            // The endpoint that selects is not reachable by the person being
            // selected, whatever they send.
            staffMove(candidate.token(), requirement, candidate.candidateId(), "SELECTED", 403);
            staffMove(candidate.token(), requirement, candidate.candidateId(), "INTERESTED", 403);
        }

        @Test
        @DisplayName("an anonymous caller is refused before anything else")
        void anonymousRefused() throws Exception {
            String officer = signIn(staff("pw-officer14@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);

            mockMvc.perform(patch("/api/requirements/" + requirement + "/shortlist/"
                            + UUID.randomUUID() + "/stage")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"stage\":\"INVITED\"}"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/candidate/placements")).andExpect(status().isUnauthorized());
        }
    }

    // --------------------------------------------------------- student access

    @Nested
    @DisplayName("a student and their own records")
    class StudentAccess {

        @Test
        @DisplayName("sees the drives they are on, and only those")
        void seesOwnPlacements() throws Exception {
            String officer = signIn(staff("pw-officer15@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture mine = student("pw-mine@example.com", institutions.exampleCse());
            Fixture theirs = student("pw-theirs@example.com", institutions.exampleCse());
            shortlist(officer, requirement, mine.candidateId());
            shortlist(officer, requirement, theirs.candidateId());

            String body = mockMvc.perform(get("/api/candidate/placements")
                            .header("Authorization", "Bearer " + mine.token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].stage").value("SHORTLISTED"))
                    .andReturn().getResponse().getContentAsString();

            // Nothing about the other student, and none of the college's working
            // notes about this one.
            assertThat(body).doesNotContain("pw-theirs@example.com");
            assertThat(body).doesNotContain("compatibility").doesNotContain("eligibility");
        }

        @Test
        @DisplayName("a student not on any drive sees an empty list, not an error")
        void noPlacements() throws Exception {
            Fixture nobody = student("pw-nobody@example.com", institutions.exampleCse());

            mockMvc.perform(get("/api/candidate/placements")
                            .header("Authorization", "Bearer " + nobody.token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("a student cannot answer for a drive they are not on")
        void cannotAnswerForSomebodyElsesDrive() throws Exception {
            String officer = signIn(staff("pw-officer16@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture onIt = student("pw-onit@example.com", institutions.exampleCse());
            Fixture notOnIt = student("pw-notonit@example.com", institutions.exampleCse());
            shortlist(officer, requirement, onIt.candidateId());
            staffMove(officer, requirement, onIt.candidateId(), "INVITED", 200);

            // There is no candidate id in this request to tamper with; the
            // record is found from the caller. Somebody not on the drive simply
            // has no record, and is told so.
            studentRespond(notOnIt.token(), requirement, "interested", 404);
            assertThat(stageOf(requirement, onIt.candidateId())).isEqualTo(PlacementStage.INVITED);
        }

        @Test
        @DisplayName("a student cannot answer before being invited")
        void cannotAnswerUninvited() throws Exception {
            String officer = signIn(staff("pw-officer17@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student17@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());

            studentRespond(candidate.token(), requirement, "interested", 400);
            assertThat(stageOf(requirement, candidate.candidateId()))
                    .isEqualTo(PlacementStage.SHORTLISTED);
        }

        @Test
        @DisplayName("a student cannot select themselves through their own endpoint")
        void cannotSelectThemselves() throws Exception {
            String officer = signIn(staff("pw-officer18@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student18@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());
            staffMove(officer, requirement, candidate.candidateId(), "INVITED", 200);

            studentRespond(candidate.token(), requirement, "selected", 400);
            studentRespond(candidate.token(), requirement, "SHORTLISTED", 400);
            assertThat(stageOf(requirement, candidate.candidateId())).isEqualTo(PlacementStage.INVITED);
        }

        @Test
        @DisplayName("a student reads their own history and nobody else's")
        void ownHistory() throws Exception {
            String officer = signIn(staff("pw-officer19@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture mine = student("pw-hist@example.com", institutions.exampleCse());
            Fixture other = student("pw-hist-other@example.com", institutions.exampleCse());
            shortlist(officer, requirement, mine.candidateId());
            staffMove(officer, requirement, mine.candidateId(), "INVITED", 200);

            mockMvc.perform(get("/api/candidate/placements/" + requirement + "/history")
                            .header("Authorization", "Bearer " + mine.token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].toStage").value("INVITED"));

            mockMvc.perform(get("/api/candidate/placements/" + requirement + "/history")
                            .header("Authorization", "Bearer " + other.token()))
                    .andExpect(status().isNotFound());
        }
    }

    // ---------------------------------------------------------------- history

    @Nested
    @DisplayName("the record of what happened")
    class History {

        @Test
        @DisplayName("carries the move, the actor and which kind of actor they were")
        void historyIsComplete() throws Exception {
            String officer = signIn(staff("pw-officer20@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student20@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());

            mockMvc.perform(patch(stageUrl(requirement, candidate.candidateId()))
                            .header("Authorization", "Bearer " + officer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"stage":"INVITED","note":"Strong backend fit for the drive."}
                                    """))
                    .andExpect(status().isOk());

            var history = stageChanges.findByShortlistIdOrderByOccurredAtAsc(
                    shortlistId(requirement, candidate.candidateId()));
            assertThat(history).hasSize(1);
            var only = history.get(0);
            assertThat(only.getFromStage()).isEqualTo(PlacementStage.SHORTLISTED);
            assertThat(only.getToStage()).isEqualTo(PlacementStage.INVITED);
            assertThat(only.getActorKind()).isEqualTo(PlacementStage.ActorKind.STAFF);
            assertThat(only.getActorLabel()).isNotBlank();
            assertThat(only.getNote()).isEqualTo("Strong backend fit for the drive.");
            assertThat(only.getOccurredAt()).isNotNull();
        }

        @Test
        @DisplayName("a note is optional")
        void noteIsOptional() throws Exception {
            String officer = signIn(staff("pw-officer21@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student21@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());

            staffMove(officer, requirement, candidate.candidateId(), "INVITED", 200);
            assertThat(stageChanges.findByShortlistIdOrderByOccurredAtAsc(
                    shortlistId(requirement, candidate.candidateId())).get(0).getNote()).isNull();
        }

        @Test
        @DisplayName("the JSON carries every field a screen renders")
        void theWireShapeIsComplete() throws Exception {
            // Asserted on the response body, not on the entity. The entity had
            // an id and an actor label all along; the view did not send them,
            // and a test that reads the entity cannot tell the difference.
            String officer = signIn(staff("pw-officer24@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student24@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());
            mockMvc.perform(patch(stageUrl(requirement, candidate.candidateId()))
                            .header("Authorization", "Bearer " + officer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"stage\":\"INVITED\",\"note\":\"Worth a look.\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stage").value("INVITED"))
                    .andExpect(jsonPath("$.stageLabel").value("Invited"))
                    .andExpect(jsonPath("$.awaitingStudent").value(true))
                    .andExpect(jsonPath("$.terminal").value(false))
                    .andExpect(jsonPath("$.stageChangedAt").isNotEmpty());

            mockMvc.perform(get(historyUrl(requirement, candidate.candidateId()))
                            .header("Authorization", "Bearer " + officer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").isNotEmpty())
                    .andExpect(jsonPath("$[0].fromStage").value("SHORTLISTED"))
                    .andExpect(jsonPath("$[0].toStage").value("INVITED"))
                    .andExpect(jsonPath("$[0].toStageLabel").value("Invited"))
                    .andExpect(jsonPath("$[0].actorLabel").isNotEmpty())
                    .andExpect(jsonPath("$[0].actorKind").value("STAFF"))
                    .andExpect(jsonPath("$[0].note").value("Worth a look."))
                    .andExpect(jsonPath("$[0].occurredAt").isNotEmpty());

            // And the student's own list, which is a different view of the same
            // record and has its own set of fields a screen depends on.
            mockMvc.perform(get("/api/candidate/placements")
                            .header("Authorization", "Bearer " + candidate.token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].requirementId").isNotEmpty())
                    .andExpect(jsonPath("$[0].companyName").isNotEmpty())
                    .andExpect(jsonPath("$[0].roleTitle").isNotEmpty())
                    .andExpect(jsonPath("$[0].requirementStatus").value("OPEN"))
                    .andExpect(jsonPath("$[0].stage").value("INVITED"))
                    .andExpect(jsonPath("$[0].stageLabel").value("Invited"))
                    .andExpect(jsonPath("$[0].awaitingYou").value(true))
                    .andExpect(jsonPath("$[0].closed").value(false))
                    .andExpect(jsonPath("$[0].shortlistedAt").isNotEmpty());
        }

        @Test
        @DisplayName("the trail hands out no user identifiers")
        void noIdentifiersInTheTrail() throws Exception {
            String officer = signIn(staff("pw-officer25@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-student25@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());
            staffMove(officer, requirement, candidate.candidateId(), "INVITED", 200);

            String body = mockMvc.perform(get(historyUrl(requirement, candidate.candidateId()))
                            .header("Authorization", "Bearer " + officer))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body)
                    .doesNotContain("actorUserId")
                    .doesNotContain("passwordHash")
                    .doesNotContain("pw-officer25@example.com");
        }

        @Test
        @DisplayName("staff can read a candidate's history; an out-of-scope one cannot")
        void historyIsScoped() throws Exception {
            String officer = signIn(staff("pw-officer22@example.com", UserRole.PLACEMENT_COORDINATOR, null));
            String requirement = openRequirement(officer);
            Fixture mech = student("pw-mech-hist@example.com", institutions.exampleMech());
            shortlist(officer, requirement, mech.candidateId());
            staffMove(officer, requirement, mech.candidateId(), "INVITED", 200);
            String coordinator = signIn(coordinatorFor(institutions.exampleCse(), "pw-coord3@example.com"));

            mockMvc.perform(get(historyUrl(requirement, mech.candidateId()))
                            .header("Authorization", "Bearer " + officer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
            mockMvc.perform(get(historyUrl(requirement, mech.candidateId()))
                            .header("Authorization", "Bearer " + coordinator))
                    .andExpect(status().isNotFound());
        }
    }

    // ------------------------------------------------- what the student is shown

    @Nested
    @DisplayName("what a student is shown of the college's side (D-10)")
    class StudentView {

        // Distinctive enough that finding any of them anywhere in a response
        // can only mean it was sent.
        private static final String STAFF_NAME = "Zephyrine Quillfeather-Okonkwo";
        private static final String INVITE_NOTE = "D10-STAFF-NOTE weak communication, keep as backup";
        private static final String SELECT_NOTE = "D10-STAFF-NOTE panel approved the offer";
        private static final String STUDENT_NOTE = "D10-STUDENT-NOTE happy to relocate to Pune";

        private record Drive(String officer, String requirement, Fixture candidate) {
        }

        /** Invited with a note, the student says yes with a note of their own, then selected with a note. */
        private Drive decidedDrive(String suffix) throws Exception {
            User named = staff("pw-d10-officer-" + suffix + "@example.com", UserRole.PLACEMENT_COORDINATOR, null);
            named.setFullName(STAFF_NAME);
            String officer = signIn(userRepository.saveAndFlush(named));
            String requirement = openRequirement(officer);
            Fixture candidate = student("pw-d10-student-" + suffix + "@example.com", institutions.exampleCse());
            shortlist(officer, requirement, candidate.candidateId());

            staffMoveWithNote(officer, requirement, candidate.candidateId(), "INVITED", INVITE_NOTE);
            mockMvc.perform(patch("/api/candidate/placements/" + requirement + "/response")
                            .header("Authorization", "Bearer " + candidate.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("response", "interested", "note", STUDENT_NOTE))))
                    .andExpect(status().isOk());
            staffMoveWithNote(officer, requirement, candidate.candidateId(), "SELECTED", SELECT_NOTE);
            return new Drive(officer, requirement, candidate);
        }

        private void staffMoveWithNote(String token, String requirementId, UUID candidateId,
                                       String stage, String note) throws Exception {
            mockMvc.perform(patch(stageUrl(requirementId, candidateId))
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("stage", stage, "note", note))))
                    .andExpect(status().isOk());
        }

        private String studentHistory(Drive drive) throws Exception {
            return mockMvc.perform(get("/api/candidate/placements/" + drive.requirement() + "/history")
                            .header("Authorization", "Bearer " + drive.candidate().token()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
        }

        @Test
        @DisplayName("the student's history names no staff member and carries no staff note")
        void staffNameAndNotesAreWithheld() throws Exception {
            Drive drive = decidedDrive("a");

            String body = studentHistory(drive);

            assertThat(body)
                    .doesNotContain(STAFF_NAME)
                    .doesNotContain("Quillfeather")
                    .doesNotContain(INVITE_NOTE)
                    .doesNotContain(SELECT_NOTE)
                    .doesNotContain("D10-STAFF-NOTE")
                    .doesNotContain("pw-d10-officer-a@example.com");

            var history = objectMapper.readTree(body);
            assertThat(history).hasSize(3);
            for (int staffMove : new int[] {0, 2}) {
                assertThat(history.get(staffMove).get("actorKind").asText()).isEqualTo("STAFF");
                assertThat(history.get(staffMove).get("actorLabel").isNull())
                        .describedAs("actorLabel on move %d", staffMove).isTrue();
                assertThat(history.get(staffMove).get("note").isNull())
                        .describedAs("note on move %d", staffMove).isTrue();
            }
        }

        @Test
        @DisplayName("the student still sees every move, when it happened, and their own answer")
        void theMovesAreStillThere() throws Exception {
            Drive drive = decidedDrive("b");

            mockMvc.perform(get("/api/candidate/placements/" + drive.requirement() + "/history")
                            .header("Authorization", "Bearer " + drive.candidate().token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$[0].fromStage").value("SHORTLISTED"))
                    .andExpect(jsonPath("$[0].toStage").value("INVITED"))
                    .andExpect(jsonPath("$[0].toStageLabel").value("Invited"))
                    .andExpect(jsonPath("$[0].actorKind").value("STAFF"))
                    .andExpect(jsonPath("$[0].occurredAt").isNotEmpty())
                    .andExpect(jsonPath("$[0].id").isNotEmpty())
                    .andExpect(jsonPath("$[1].fromStage").value("INVITED"))
                    .andExpect(jsonPath("$[1].toStage").value("INTERESTED"))
                    .andExpect(jsonPath("$[1].actorKind").value("STUDENT"))
                    .andExpect(jsonPath("$[1].actorLabel").value("Placement Student"))
                    .andExpect(jsonPath("$[1].note").value(STUDENT_NOTE))
                    .andExpect(jsonPath("$[1].occurredAt").isNotEmpty())
                    .andExpect(jsonPath("$[2].fromStage").value("INTERESTED"))
                    .andExpect(jsonPath("$[2].toStage").value("SELECTED"))
                    .andExpect(jsonPath("$[2].actorKind").value("STAFF"))
                    .andExpect(jsonPath("$[2].occurredAt").isNotEmpty());
        }

        @Test
        @DisplayName("staff reading the same history still see who moved the student, and why")
        void staffViewIsUnchanged() throws Exception {
            Drive drive = decidedDrive("c");

            mockMvc.perform(get(historyUrl(drive.requirement(), drive.candidate().candidateId()))
                            .header("Authorization", "Bearer " + drive.officer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$[0].actorLabel").value(STAFF_NAME))
                    .andExpect(jsonPath("$[0].note").value(INVITE_NOTE))
                    .andExpect(jsonPath("$[1].note").value(STUDENT_NOTE))
                    .andExpect(jsonPath("$[2].actorLabel").value(STAFF_NAME))
                    .andExpect(jsonPath("$[2].note").value(SELECT_NOTE));
        }

        @Test
        @DisplayName("no other response a student can get carries them either")
        void noOtherStudentResponseCarriesThem() throws Exception {
            Drive drive = decidedDrive("d");
            String token = drive.candidate().token();

            String placements = mockMvc.perform(get("/api/candidate/placements")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String export = mockMvc.perform(get("/api/candidate/export")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            for (String body : new String[] {placements, export}) {
                assertThat(body)
                        .doesNotContain(STAFF_NAME)
                        .doesNotContain("Quillfeather")
                        .doesNotContain("D10-STAFF-NOTE");
            }
            // The export was already right; this pins it, and shows the check can see a note at all.
            assertThat(export).contains(STUDENT_NOTE);
        }

        @Test
        @DisplayName("reading the student's history changes nothing that is stored")
        void readingChangesNothing() throws Exception {
            Drive drive = decidedDrive("e");
            String rows = "select c.id, c.from_stage, c.to_stage, c.actor_user_id, c.actor_label, c.actor_kind, "
                    + "c.note, c.occurred_at from placement_stage_changes c "
                    + "join company_requirement_shortlists s on s.id = c.shortlist_id "
                    + "where s.requirement_id = ? order by c.occurred_at, c.id";
            UUID requirement = UUID.fromString(drive.requirement());

            entityManager.flush();
            var before = jdbc.queryForList(rows, requirement);
            studentHistory(drive);
            entityManager.flush();
            var after = jdbc.queryForList(rows, requirement);

            assertThat(before).hasSize(3);
            assertThat(after).isEqualTo(before);
            assertThat(before.get(0).get("actor_label")).isEqualTo(STAFF_NAME);
            assertThat(before.get(0).get("note")).isEqualTo(INVITE_NOTE);
        }
    }

    // --------------------------------------------------------------- helpers

    private record Fixture(UUID candidateId, String token) {
    }

    private String stageUrl(String requirementId, UUID candidateId) {
        return "/api/requirements/" + requirementId + "/shortlist/" + candidateId + "/stage";
    }

    private String historyUrl(String requirementId, UUID candidateId) {
        return "/api/requirements/" + requirementId + "/shortlist/" + candidateId + "/history";
    }

    private void staffMove(String token, String requirementId, UUID candidateId,
                           String stage, int expected) throws Exception {
        mockMvc.perform(patch(stageUrl(requirementId, candidateId))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"%s\"}".formatted(stage)))
                .andExpect(status().is(expected));
    }

    private void studentRespond(String token, String requirementId,
                                String response, int expected) throws Exception {
        mockMvc.perform(patch("/api/candidate/placements/" + requirementId + "/response")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"%s\"}".formatted(response)))
                .andExpect(status().is(expected));
    }

    private void shortlist(String token, String requirementId, UUID candidateId) throws Exception {
        mockMvc.perform(post("/api/requirements/" + requirementId + "/shortlist")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"%s\"}".formatted(candidateId)))
                .andExpect(status().isCreated());
    }

    private PlacementStage stageOf(String requirementId, UUID candidateId) {
        return shortlists.findByRequirementIdAndCandidateId(UUID.fromString(requirementId), candidateId)
                .orElseThrow().getStage();
    }

    private UUID shortlistId(String requirementId, UUID candidateId) {
        return shortlists.findByRequirementIdAndCandidateId(UUID.fromString(requirementId), candidateId)
                .orElseThrow().getId();
    }

    /** An OPEN requirement targeting no department, so every coordinator can see it. */
    private String openRequirement(String officerToken) throws Exception {
        String created = mockMvc.perform(post("/api/requirements")
                        .header("Authorization", "Bearer " + officerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyName":"Placement Test Ltd","roleTitle":"Backend Developer"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();
        patchStatus(officerToken, id, "OPEN");
        return id;
    }

    private void patchStatus(String token, String requirementId, String status) throws Exception {
        mockMvc.perform(patch("/api/requirements/" + requirementId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"%s\"}".formatted(status)))
                .andExpect(status().isOk());
    }

    private User coordinatorFor(Department department, String email) {
        User coordinator = staff(email, UserRole.DEPARTMENT_COORDINATOR, department);
        staffScopes.save(StaffScope.forDepartment(coordinator, institutions.example(), department));
        return coordinator;
    }

    private User staff(String email, UserRole role, Department department) {
        User user = new User();
        user.setEmail(email);
        user.setFullName("Placement " + role);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institutions.example());
        if (department != null) {
            user.setDepartment(department);
        }
        return userRepository.saveAndFlush(user);
    }

    private Fixture student(String email, Department department) throws Exception {
        User user = new User();
        user.setEmail(email);
        user.setFullName("Placement Student");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(UserRole.STUDENT);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(department.getInstitution());
        user.setDepartment(department);
        userRepository.saveAndFlush(user);
        CandidateProfile profile = profileService.createForUser(user);
        return new Fixture(profile.getId(), signIn(user));
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
