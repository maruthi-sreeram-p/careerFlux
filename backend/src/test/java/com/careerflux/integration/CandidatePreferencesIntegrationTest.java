package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidatePreferenceValue;
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
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * Saving career preferences, more than once.
 *
 * <p>This endpoint had no test of any kind, which is exactly how it shipped
 * unable to save twice: every fixture in the suite wrote preferences into an
 * empty profile, and that is the one case that always worked. The second save
 * returned 409 because the replacement deleted and re-inserted the same rows,
 * and Hibernate runs queued inserts before queued deletes.
 *
 * <p>So the tests here are deliberately sequential rather than each starting
 * from a clean profile. A test that resets state between saves would pass
 * against the bug.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CandidatePreferencesIntegrationTest {

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
    void signInAsAFreshStudent() throws Exception {
        User user = new User();
        user.setEmail("prefs-" + UUID.randomUUID() + "@example.com");
        user.setFullName("Preferences Student");
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

    // ------------------------------------------------------------- helpers

    /**
     * Saves a complete payload. Always complete: the endpoint replaces, so a
     * partial body silently clears whatever it omits.
     */
    private void save(List<String> targetRoles, List<String> locations,
                      List<String> workModes, int expectedStatus) throws Exception {
        mockMvc.perform(put("/api/candidate/preferences")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(targetRoles, locations, workModes)))
                .andExpect(status().is(expectedStatus));
    }

    private void save(List<String> targetRoles, List<String> locations,
                      List<String> workModes) throws Exception {
        save(targetRoles, locations, workModes, 200);
    }

    private String payload(List<String> targetRoles, List<String> locations,
                           List<String> workModes) throws Exception {
        return objectMapper.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
                put("targetRoles", targetRoles);
                put("industries", List.of());
                put("locations", locations);
                put("workModes", workModes);
                put("employmentTypes", List.of("FULL_TIME"));
                put("preferredCompanies", List.of());
                put("openToRelocation", false);
                put("immediateAlerts", false);
                put("dailyDigest", false);
            }});
    }

    /** Stored values for one type, in display order. */
    private List<String> stored(PreferenceType type) {
        return preferenceValues.findByCandidateIdAndValueType(candidateId, type).stream()
                .sorted(java.util.Comparator.comparingInt(CandidatePreferenceValue::getDisplayOrder))
                .map(CandidatePreferenceValue::getValue)
                .toList();
    }

    private List<CandidatePreferenceValue> rows(PreferenceType type) {
        return preferenceValues.findByCandidateIdAndValueType(candidateId, type);
    }

    /** What the API hands back, which is what the screen renders. */
    private List<String> returned(String field) throws Exception {
        String body = mockMvc.perform(get("/api/candidate/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body).path("preferences").path(field);
        return objectMapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
    }

    // ------------------------------------------------------- the core bug

    @Nested
    @DisplayName("saving more than once")
    class Repeatedly {

        @Test
        @DisplayName("an identical second save succeeds")
        void identicalSecondSave() throws Exception {
            // The regression test for the 409. Before the fix this returned
            // "That change conflicts with data that already exists."
            save(List.of("Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"));
            save(List.of("Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"), 200);

            assertThat(stored(PreferenceType.TARGET_ROLE)).containsExactly("Backend Developer");
            assertThat(stored(PreferenceType.LOCATION)).containsExactly("Bengaluru");
        }

        @Test
        @DisplayName("a third save succeeds too, so this is not a one-shot fix")
        void thirdSave() throws Exception {
            save(List.of("Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"));
            save(List.of("Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"));
            save(List.of("Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"), 200);

            assertThat(rows(PreferenceType.TARGET_ROLE)).hasSize(1);
        }

        @Test
        @DisplayName("an unchanged row is kept, not deleted and recreated")
        void unchangedRowsAreRetained() throws Exception {
            // The point of a diff rather than a flush: a save that changes one
            // value must not churn every other row. Row identity is the evidence.
            save(List.of("Backend Developer", "Java Developer"), List.of("Bengaluru"), List.of("HYBRID"));
            UUID keptId = rows(PreferenceType.TARGET_ROLE).stream()
                    .filter(row -> row.getValue().equals("Backend Developer"))
                    .findFirst().orElseThrow().getId();

            save(List.of("Backend Developer", "Data Engineer"), List.of("Bengaluru"), List.of("HYBRID"));

            UUID afterId = rows(PreferenceType.TARGET_ROLE).stream()
                    .filter(row -> row.getValue().equals("Backend Developer"))
                    .findFirst().orElseThrow().getId();
            assertThat(afterId)
                    .describedAs("the untouched value should be the same row as before")
                    .isEqualTo(keptId);
        }
    }

    // ---------------------------------------------------------- the edits

    @Nested
    @DisplayName("the edits a student actually makes")
    class Edits {

        @Test
        @DisplayName("first save into an empty profile")
        void firstSave() throws Exception {
            save(List.of("Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"));
            assertThat(stored(PreferenceType.TARGET_ROLE)).containsExactly("Backend Developer");
            assertThat(rows(PreferenceType.TARGET_ROLE).get(0).getDisplayOrder()).isZero();
        }

        @Test
        @DisplayName("adding one value keeps the others")
        void addOne() throws Exception {
            save(List.of("Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"));
            save(List.of("Backend Developer", "Java Developer"), List.of("Bengaluru"), List.of("HYBRID"));

            assertThat(stored(PreferenceType.TARGET_ROLE))
                    .containsExactly("Backend Developer", "Java Developer");
            assertThat(rows(PreferenceType.TARGET_ROLE)).hasSize(2);
        }

        @Test
        @DisplayName("removing one value leaves the rest")
        void removeOne() throws Exception {
            save(List.of("Backend Developer", "Java Developer"), List.of("Bengaluru"), List.of("HYBRID"));
            save(List.of("Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"));

            assertThat(stored(PreferenceType.TARGET_ROLE)).containsExactly("Backend Developer");
            assertThat(rows(PreferenceType.TARGET_ROLE)).hasSize(1);
        }

        @Test
        @DisplayName("replacing one value")
        void replaceOne() throws Exception {
            save(List.of("Backend Developer", "Java Developer"), List.of("Bengaluru"), List.of("HYBRID"));
            save(List.of("Backend Developer", "Data Engineer"), List.of("Bengaluru"), List.of("HYBRID"));

            assertThat(stored(PreferenceType.TARGET_ROLE))
                    .containsExactly("Backend Developer", "Data Engineer");
        }

        @Test
        @DisplayName("replacing several values across several types")
        void replaceMany() throws Exception {
            save(List.of("Backend Developer", "Java Developer"),
                    List.of("Bengaluru", "Hyderabad"), List.of("HYBRID", "REMOTE"));
            save(List.of("Data Engineer", "ML Engineer"),
                    List.of("Pune", "Chennai"), List.of("ONSITE"));

            assertThat(stored(PreferenceType.TARGET_ROLE)).containsExactly("Data Engineer", "ML Engineer");
            assertThat(stored(PreferenceType.LOCATION)).containsExactly("Pune", "Chennai");
            assertThat(stored(PreferenceType.WORK_MODE)).containsExactly("ONSITE");
        }

        @Test
        @DisplayName("clearing every value")
        void clearAll() throws Exception {
            save(List.of("Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"));
            save(List.of(), List.of(), List.of());

            assertThat(rows(PreferenceType.TARGET_ROLE)).isEmpty();
            assertThat(rows(PreferenceType.LOCATION)).isEmpty();
            assertThat(rows(PreferenceType.WORK_MODE)).isEmpty();
        }

        @Test
        @DisplayName("empty to populated, and populated back to empty")
        void bothDirections() throws Exception {
            save(List.of(), List.of(), List.of());
            assertThat(rows(PreferenceType.TARGET_ROLE)).isEmpty();

            save(List.of("Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"));
            assertThat(rows(PreferenceType.TARGET_ROLE)).hasSize(1);

            save(List.of(), List.of(), List.of());
            assertThat(rows(PreferenceType.TARGET_ROLE)).isEmpty();
        }
    }

    // ------------------------------------------------------------ ordering

    @Nested
    @DisplayName("the order the student put them in")
    class Ordering {

        @Test
        @DisplayName("reordering the same values takes effect")
        void reorderOnly() throws Exception {
            // The case a naive value-only diff gets wrong: the sets are equal,
            // so nothing is written and the student's reorder silently vanishes.
            save(List.of("Backend Developer", "Java Developer"), List.of("Bengaluru"), List.of("HYBRID"));
            save(List.of("Java Developer", "Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"));

            assertThat(stored(PreferenceType.TARGET_ROLE))
                    .containsExactly("Java Developer", "Backend Developer");
            assertThat(returned("targetRoles"))
                    .describedAs("the API must return the order the student saved")
                    .containsExactly("Java Developer", "Backend Developer");
        }

        @Test
        @DisplayName("display order is contiguous from zero after an insert in the middle")
        void ordersAreContiguous() throws Exception {
            save(List.of("A Role", "C Role"), List.of("Bengaluru"), List.of("HYBRID"));
            save(List.of("A Role", "B Role", "C Role"), List.of("Bengaluru"), List.of("HYBRID"));

            assertThat(rows(PreferenceType.TARGET_ROLE).stream()
                    .sorted(java.util.Comparator.comparingInt(CandidatePreferenceValue::getDisplayOrder))
                    .map(CandidatePreferenceValue::getDisplayOrder).toList())
                    .containsExactly(0, 1, 2);
            assertThat(stored(PreferenceType.TARGET_ROLE))
                    .containsExactly("A Role", "B Role", "C Role");
        }
    }

    // ------------------------------------------------------- normalisation

    @Nested
    @DisplayName("what the request is normalised to")
    class Normalisation {

        @Test
        @DisplayName("duplicates in the request collapse, case-insensitively")
        void requestDuplicates() throws Exception {
            save(List.of("Java", "java", "Java"), List.of("Bengaluru"), List.of("HYBRID"));

            assertThat(rows(PreferenceType.TARGET_ROLE)).hasSize(1);
            assertThat(stored(PreferenceType.TARGET_ROLE)).containsExactly("Java");
        }

        @Test
        @DisplayName("a case change is a real change, because the constraint is case-sensitive")
        void caseChangeIsAChange() throws Exception {
            // The database treats "Java" and "java" as different rows, so the
            // diff must compare exactly. Matching case-insensitively here would
            // silently refuse to apply a student's capitalisation fix.
            save(List.of("Java"), List.of("Bengaluru"), List.of("HYBRID"));
            save(List.of("java"), List.of("Bengaluru"), List.of("HYBRID"));

            assertThat(stored(PreferenceType.TARGET_ROLE)).containsExactly("java");
            assertThat(rows(PreferenceType.TARGET_ROLE)).hasSize(1);
        }

        @Test
        @DisplayName("blank entries are dropped and values are trimmed")
        void blanksAndWhitespace() throws Exception {
            save(java.util.Arrays.asList("  Backend Developer  ", "", "   "),
                    List.of("Bengaluru"), List.of("HYBRID"));

            assertThat(stored(PreferenceType.TARGET_ROLE)).containsExactly("Backend Developer");
        }

        @Test
        @DisplayName("an unrecognised enum value is dropped rather than stored")
        void enumCanonicalisation() throws Exception {
            save(List.of("Backend Developer"), List.of("Bengaluru"),
                    java.util.Arrays.asList("hybrid", "NOT_A_MODE"));

            assertThat(stored(PreferenceType.WORK_MODE))
                    .describedAs("lower case is canonicalised, nonsense is dropped")
                    .containsExactly("HYBRID");
        }

        @Test
        @DisplayName("an over-long value is truncated to the column width")
        void truncation() throws Exception {
            save(List.of("x".repeat(400)), List.of("Bengaluru"), List.of("HYBRID"));
            assertThat(stored(PreferenceType.TARGET_ROLE).get(0)).hasSize(200);
        }
    }

    // ------------------------------------------------------------ rematch

    @Nested
    @DisplayName("what a save asks the matcher to do")
    class Rematch {

        @Test
        @DisplayName("a successful save leaves exactly one queue row for the candidate")
        void oneQueueRow() throws Exception {
            save(List.of("Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"));
            save(List.of("Backend Developer", "Java Developer"), List.of("Bengaluru"), List.of("HYBRID"));
            save(List.of("Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"));

            // Three saves, one row: RematchQueue collapses per candidate. This
            // is unchanged by the fix and is what keeps repeated edits cheap.
            assertThat(rematchRequests.findByCandidateId(candidateId))
                    .describedAs("requests must collapse to one row per candidate")
                    .isPresent();
            assertThat(rematchRequests.findAll().stream()
                    .filter(r -> r.getCandidate().getId().equals(candidateId))
                    .count())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an identical save asks for no rescore")
        void identicalSaveDoesNotEnqueue() throws Exception {
            // Phase 15A left this enqueueing, as a documented consequence of
            // making the save succeed. Phase 15B decides it at the save
            // boundary instead: nothing changed, so nothing is rescored.
            // PreferenceRematchSuppressionTest covers the decision in full.
            save(List.of("Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"));
            rematchRequests.deleteAll();

            save(List.of("Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"));

            assertThat(rematchRequests.findByCandidateId(candidateId)).isNotPresent();
        }
    }

    // ----------------------------------------------------------- integrity

    @Nested
    @DisplayName("integrity")
    class Integrity {

        @Test
        @DisplayName("a rejected save changes nothing")
        void rejectedSaveIsAtomic() throws Exception {
            save(List.of("Backend Developer", "Java Developer"),
                    List.of("Bengaluru", "Hyderabad"), List.of("HYBRID"));
            List<String> before = stored(PreferenceType.TARGET_ROLE);

            // Malformed body: rejected before the service runs. The point is
            // that a failed request leaves the previous state exactly intact.
            mockMvc.perform(put("/api/candidate/preferences")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ this is not json"))
                    .andExpect(status().is4xxClientError());

            assertThat(stored(PreferenceType.TARGET_ROLE)).isEqualTo(before);
            assertThat(stored(PreferenceType.LOCATION)).containsExactly("Bengaluru", "Hyderabad");
        }

        @Test
        @DisplayName("no duplicate rows are ever produced")
        void noDuplicates() throws Exception {
            for (int i = 0; i < 4; i++) {
                save(List.of("Backend Developer", "Java Developer"),
                        List.of("Bengaluru", "Hyderabad"), List.of("HYBRID", "REMOTE"));
            }
            for (PreferenceType type : List.of(PreferenceType.TARGET_ROLE,
                    PreferenceType.LOCATION, PreferenceType.WORK_MODE)) {
                List<String> values = stored(type);
                assertThat(values).describedAs("%s", type).doesNotHaveDuplicates();
            }
            assertThat(rows(PreferenceType.TARGET_ROLE)).hasSize(2);
        }

        @Test
        @DisplayName("another student's preferences are untouched")
        void otherStudentsAreUnaffected() throws Exception {
            save(List.of("Backend Developer"), List.of("Bengaluru"), List.of("HYBRID"));
            UUID mine = candidateId;

            signInAsAFreshStudent();
            save(List.of("Data Engineer"), List.of("Pune"), List.of("ONSITE"));

            assertThat(preferenceValues
                    .findByCandidateIdAndValueType(mine, PreferenceType.TARGET_ROLE).stream()
                    .map(CandidatePreferenceValue::getValue).toList())
                    .containsExactly("Backend Developer");
        }
    }
}
