package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.careerflux.candidate.CandidateProfileChangedEvent;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.PreferenceType;
import com.careerflux.candidate.repository.CandidatePreferenceValueRepository;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.matching.rematch.RematchRequestRepository;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Whether a preferences save asks for a rescore.
 *
 * <p>Phase 15A made repeated saves succeed, and in doing so made a save that
 * changes nothing publish {@link CandidateProfileChangedEvent} and queue a
 * two-minute rescore. Previously that never happened, but only because the save
 * failed — the absence of a rematch was a symptom of the bug, not a decision.
 *
 * <p>The decision is made here, at the save boundary. Nothing in the listener,
 * the queue or the worker knows about it.
 *
 * <p><b>The rule is "did anything change", not "did anything matching-relevant
 * change".</b> Inspection showed only four of the six collections and one of the
 * nine scalars actually reach {@code CandidateSnapshot}. Suppressing on the
 * others would be correct today and silently wrong the day salary or industry is
 * wired into scoring: matches would stop updating with nothing to indicate it.
 * The rescore that rule costs is rare — a student changing only their salary
 * expectation — and the failure it avoids is invisible.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
class PreferenceRematchSuppressionTest {

    private static final String PASSWORD = "IntegrationTest123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandidateProfileService profileService;

    @Autowired
    private CandidatePreferenceValueRepository preferenceValues;

    @Autowired
    private RematchRequestRepository rematchRequests;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    private String token;
    private UUID candidateId;

    @BeforeEach
    void signIn() throws Exception {
        User user = new User();
        user.setEmail("suppress-" + UUID.randomUUID() + "@example.com");
        user.setFullName("Suppression Student");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(UserRole.STUDENT);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institutions.example());
        user.setDepartment(institutions.exampleCse());
        userRepository.saveAndFlush(user);
        CandidateProfile profile = profileService.createForUser(user);
        candidateId = profile.getId();

        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = objectMapper.readTree(body).get("accessToken").asText();
    }

    // --------------------------------------------------------------- helpers

    /** A complete payload. The endpoint replaces, so nothing may be omitted. */
    private Map<String, Object> payload() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetRoles", List.of("Backend Developer", "Java Developer"));
        body.put("industries", List.of());
        body.put("locations", List.of("Bengaluru"));
        body.put("workModes", List.of("HYBRID"));
        body.put("employmentTypes", List.of("FULL_TIME"));
        body.put("preferredCompanies", List.of());
        body.put("salaryMin", null);
        body.put("salaryMax", null);
        body.put("salaryCurrency", null);
        body.put("salaryPeriod", null);
        body.put("openToRelocation", false);
        body.put("minExperienceYears", null);
        body.put("maxExperienceYears", null);
        body.put("immediateAlerts", false);
        body.put("dailyDigest", false);
        return body;
    }

    private Map<String, Object> payloadWith(String key, Object value) {
        Map<String, Object> body = payload();
        body.put(key, value);
        return body;
    }

    private void save(Map<String, Object> body) throws Exception {
        mockMvc.perform(put("/api/candidate/preferences")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    /** Events and queue rows are both cleared, so the next save is observed alone. */
    private void resetObservations() {
        events.clear();
        rematchRequests.deleteAll();
    }

    private long eventsPublished() {
        return events.stream(CandidateProfileChangedEvent.class)
                .filter(event -> event.candidateId().equals(candidateId))
                .count();
    }

    private boolean rematchQueued() {
        return rematchRequests.findByCandidateId(candidateId).isPresent();
    }

    /** Runs one save and reports whether it asked for a rescore. */
    private boolean asksForRematch(Map<String, Object> body) throws Exception {
        resetObservations();
        save(body);
        boolean published = eventsPublished() > 0;
        assertThat(rematchQueued())
                .describedAs("the queue must agree with the event: published=%s", published)
                .isEqualTo(published);
        return published;
    }

    // ------------------------------------------------------ the no-op cases

    @Nested
    @DisplayName("saves that change nothing")
    class NoOps {

        @Test
        @DisplayName("an identical populated save asks for no rescore")
        void identicalPopulatedSave() throws Exception {
            save(payload());
            assertThat(asksForRematch(payload()))
                    .describedAs("nothing changed, so nothing should be rescored")
                    .isFalse();
        }

        @Test
        @DisplayName("an identical empty save asks for no rescore")
        void identicalEmptySave() throws Exception {
            Map<String, Object> empty = payload();
            empty.put("targetRoles", List.of());
            empty.put("locations", List.of());
            empty.put("workModes", List.of());
            empty.put("employmentTypes", List.of());
            save(empty);

            assertThat(asksForRematch(empty)).isFalse();
        }

        @Test
        @DisplayName("repeated identical saves each ask for nothing")
        void repeatedIdenticalSaves() throws Exception {
            save(payload());
            for (int i = 0; i < 3; i++) {
                assertThat(asksForRematch(payload()))
                        .describedAs("no-op save %d", i + 1)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("a no-op save still returns the saved state")
        void noOpStillPersistsCorrectly() throws Exception {
            save(payload());
            asksForRematch(payload());

            assertThat(preferenceValues
                    .findByCandidateIdAndValueType(candidateId, PreferenceType.TARGET_ROLE))
                    .hasSize(2);
        }
    }

    // ----------------------------------------------------- the change cases

    @Nested
    @DisplayName("saves that change something")
    class Changes {

        @Test
        @DisplayName("the first save always asks for a rescore")
        void firstSave() throws Exception {
            assertThat(asksForRematch(payload())).isTrue();
        }

        @Test
        @DisplayName("adding a value asks for a rescore")
        void addOne() throws Exception {
            save(payload());
            assertThat(asksForRematch(payloadWith("targetRoles",
                    List.of("Backend Developer", "Java Developer", "Data Engineer")))).isTrue();
        }

        @Test
        @DisplayName("removing a value asks for a rescore")
        void removeOne() throws Exception {
            save(payload());
            assertThat(asksForRematch(payloadWith("targetRoles",
                    List.of("Backend Developer")))).isTrue();
        }

        @Test
        @DisplayName("replacing a value asks for a rescore")
        void replaceOne() throws Exception {
            save(payload());
            assertThat(asksForRematch(payloadWith("targetRoles",
                    List.of("Backend Developer", "Data Engineer")))).isTrue();
        }

        @Test
        @DisplayName("reordering asks for a rescore, because order is a real edit")
        void reorder() throws Exception {
            // Phase 15A treats order as meaningful, so suppression must not
            // decide by set equality. This is the test that catches that.
            save(payload());
            assertThat(asksForRematch(payloadWith("targetRoles",
                    List.of("Java Developer", "Backend Developer")))).isTrue();
        }

        @Test
        @DisplayName("a case-only change asks for a rescore")
        void caseChange() throws Exception {
            save(payloadWith("targetRoles", List.of("Java")));
            assertThat(asksForRematch(payloadWith("targetRoles", List.of("java")))).isTrue();
        }

        @Test
        @DisplayName("changing openToRelocation asks for a rescore")
        void matchingRelevantScalar() throws Exception {
            // The one scalar of the nine that reaches MatchScorer.
            save(payload());
            assertThat(asksForRematch(payloadWith("openToRelocation", true))).isTrue();
        }

        @Test
        @DisplayName("changes across several types are one save and one rescore request")
        void severalTypesAtOnce() throws Exception {
            save(payload());
            resetObservations();

            Map<String, Object> body = payload();
            body.put("targetRoles", List.of("Data Engineer"));
            body.put("locations", List.of("Pune", "Chennai"));
            body.put("workModes", List.of("ONSITE"));
            save(body);

            assertThat(eventsPublished())
                    .describedAs("one save publishes at most one event")
                    .isEqualTo(1);
            assertThat(rematchQueued()).isTrue();
        }

        @Test
        @DisplayName("a change followed by a no-op rescores once, not twice")
        void changeThenNoOp() throws Exception {
            save(payload());
            assertThat(asksForRematch(payloadWith("locations", List.of("Pune")))).isTrue();
            assertThat(asksForRematch(payloadWith("locations", List.of("Pune")))).isFalse();
        }
    }

    // ------------------------------------- fields matching does not read

    @Nested
    @DisplayName("fields matching never reads")
    class NotReadByMatching {

        /**
         * These record a deliberate decision rather than a necessity.
         *
         * <p>Salary, the experience bounds, the alert flags, INDUSTRY and
         * PREFERRED_COMPANY never reach {@code CandidateSnapshot}, so a rescore
         * triggered by them recomputes identical numbers. Suppressing them would
         * be correct today and would become silently wrong the moment any of
         * them is wired into scoring — matches would stop updating and nothing
         * would say so. The wasted rescore is rare and visible; the stale match
         * would be neither.
         */
        @Test
        @DisplayName("changing salary asks for a rescore, conservatively")
        void salaryChange() throws Exception {
            save(payload());
            assertThat(asksForRematch(payloadWith("salaryMin", 1200000)))
                    .describedAs("deliberate: not keyed to the scorer's current internals")
                    .isTrue();
        }

        @Test
        @DisplayName("changing an alert flag asks for a rescore, conservatively")
        void alertFlagChange() throws Exception {
            save(payload());
            assertThat(asksForRematch(payloadWith("immediateAlerts", true))).isTrue();
        }

        @Test
        @DisplayName("changing industries asks for a rescore, conservatively")
        void industryChange() throws Exception {
            save(payload());
            assertThat(asksForRematch(payloadWith("industries", List.of("Fintech")))).isTrue();
        }
    }

    // ------------------------------------------------------------ integrity

    @Nested
    @DisplayName("integrity")
    class Integrity {

        @Test
        @DisplayName("a rejected save publishes nothing and queues nothing")
        void rejectedSave() throws Exception {
            save(payload());
            resetObservations();

            mockMvc.perform(put("/api/candidate/preferences")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ not json"))
                    .andExpect(status().is4xxClientError());

            assertThat(eventsPublished()).isZero();
            assertThat(rematchQueued()).isFalse();
        }

        @Test
        @DisplayName("queue collapse still holds across several changed saves")
        void collapseUnchanged() throws Exception {
            resetObservations();
            save(payload());
            save(payloadWith("locations", List.of("Pune")));
            save(payloadWith("locations", List.of("Chennai")));

            assertThat(rematchRequests.findAll().stream()
                    .filter(r -> r.getCandidate().getId().equals(candidateId))
                    .count())
                    .describedAs("three changed saves still collapse to one queue row")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("suppression is per candidate, not global")
        void suppressionIsPerCandidate() throws Exception {
            save(payload());
            UUID first = candidateId;

            signIn();
            resetObservations();
            save(payload());

            // The second student's first save is a real change and must rescore
            // even though an identical payload was just suppressed for another.
            assertThat(rematchQueued()).isTrue();
            assertThat(rematchRequests.findByCandidateId(first)).isNotPresent();
        }
    }
}
