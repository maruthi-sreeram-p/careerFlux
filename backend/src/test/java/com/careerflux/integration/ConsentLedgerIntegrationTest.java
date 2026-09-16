package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.careerflux.consent.ConsentPurpose;
import com.careerflux.consent.ConsentProperties;
import com.careerflux.consent.ConsentRecord;
import com.careerflux.consent.NoticeRegistry;
import com.careerflux.consent.NoticeVersion;
import com.careerflux.consent.NoticeVersionRepository;
import com.careerflux.support.PrivacyFixture;
import com.careerflux.support.PrivacyFixture.Account;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The consent ledger (Phase 2B, F11 / R25): append-only, versioned, the
 * student's own, and closed to staff.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsentLedgerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PrivacyFixture fixture;

    @Autowired
    private TestInstitutions institutions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NoticeRegistry notices;

    @Autowired
    private NoticeVersionRepository noticeRepository;

    @Autowired
    private ConsentProperties consentProperties;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Account student;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        fixture.begin();
        student = fixture.student("Diya Reddy", institutions.example(), institutions.exampleCse(),
                institutions.exampleBatch2026());
        token = fixture.login(mockMvc, student.email());
    }

    @AfterEach
    void tearDown() {
        fixture.cleanUp();
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          String bearer, int expected) throws Exception {
        String body = mockMvc.perform(request.header("Authorization", "Bearer " + bearer))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private static JsonNode purpose(JsonNode overview, String purpose) {
        for (JsonNode state : overview.get("purposes")) {
            if (state.get("purpose").asText().equals(purpose)) {
                return state;
            }
        }
        throw new AssertionError("no state for " + purpose);
    }

    private JsonNode accept(String bearer, String noticeVersionId) throws Exception {
        return call(post("/api/consents/ai-processing/accept").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("noticeVersionId", noticeVersionId,
                        "source", "SETTINGS"))), bearer, 200);
    }

    private String aiNoticeId() throws Exception {
        return purpose(call(get("/api/consents"), token, 200), "AI_PROCESSING").get("currentNotice").get("id").asText();
    }

    private List<Map<String, Object>> aiRows(UUID userId) {
        return jdbc.queryForList("select id, accepted_at, withdrawn_at, notice_version_id from consent_records "
                + "where user_id = ? and purpose = 'AI_PROCESSING' order by created_at", userId);
    }

    @Test
    @DisplayName("nothing is agreed by default, and the notice offered is a marked placeholder")
    void offByDefault() throws Exception {
        JsonNode ai = purpose(call(get("/api/consents"), token, 200), "AI_PROCESSING");

        assertThat(ai.get("active").asBoolean()).isFalse();
        assertThat(ai.get("latest").isNull()).isTrue();
        assertThat(ai.get("currentNotice").get("placeholder").asBoolean()).isTrue();
        assertThat(ai.get("currentNotice").get("body").asText()).contains("ENGINEERING PLACEHOLDER");
    }

    @Test
    @DisplayName("accepting records one acceptance against the notice version shown")
    void acceptanceIsRecorded() throws Exception {
        String noticeId = aiNoticeId();

        JsonNode ai = purpose(accept(token, noticeId), "AI_PROCESSING");

        assertThat(ai.get("active").asBoolean()).isTrue();
        List<Map<String, Object>> rows = aiRows(student.userId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("accepted_at")).isNotNull();
        assertThat(rows.get(0).get("withdrawn_at")).isNull();
        assertThat(rows.get(0).get("notice_version_id").toString()).isEqualTo(noticeId);
    }

    @Test
    @DisplayName("withdrawing adds a row and leaves the acceptance exactly as it was")
    void withdrawalIsAppended() throws Exception {
        accept(token, aiNoticeId());
        Map<String, Object> acceptance = aiRows(student.userId()).get(0);

        JsonNode ai = purpose(call(post("/api/consents/ai-processing/withdraw"), token, 200), "AI_PROCESSING");

        assertThat(ai.get("active").asBoolean()).isFalse();
        List<Map<String, Object>> rows = aiRows(student.userId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).isEqualTo(acceptance);
        assertThat(rows.get(1).get("accepted_at")).isNull();
        assertThat(rows.get(1).get("withdrawn_at")).isNotNull();
    }

    @Test
    @DisplayName("consenting again is a new acceptance, and the whole history is kept")
    void reconsentIsANewRecord() throws Exception {
        String noticeId = aiNoticeId();
        accept(token, noticeId);
        call(post("/api/consents/ai-processing/withdraw"), token, 200);

        JsonNode overview = accept(token, noticeId);

        assertThat(purpose(overview, "AI_PROCESSING").get("active").asBoolean()).isTrue();
        assertThat(aiRows(student.userId())).hasSize(3);
        assertThat(overview.get("history")).hasSize(3);
        assertThat(overview.get("history").get(0).get("action").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("agreeing to a notice version that is not current records nothing")
    void staleNoticeIsRefused() throws Exception {
        call(post("/api/consents/ai-processing/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"noticeVersionId\":\"" + UUID.randomUUID() + "\"}"), token, 409);

        assertThat(aiRows(student.userId())).isEmpty();
    }

    @Test
    @DisplayName("a request cannot record consent for another student")
    void cannotActForSomebodyElse() throws Exception {
        Account other = fixture.student("Kiran Rao", institutions.example(), institutions.exampleCse(),
                institutions.exampleBatch2026());

        call(post("/api/consents/ai-processing/accept").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("noticeVersionId", aiNoticeId(),
                        "userId", other.userId().toString()))), token, 200);

        assertThat(aiRows(student.userId())).hasSize(1);
        assertThat(aiRows(other.userId())).isEmpty();
    }

    @Test
    @DisplayName("staff cannot read or record a student's consent, for themselves or anyone")
    void staffAreRefused() throws Exception {
        String noticeId = aiNoticeId();
        for (UserRole role : List.of(UserRole.PLACEMENT_COORDINATOR, UserRole.DEPARTMENT_COORDINATOR,
                UserRole.PORTAL_ADMIN)) {
            Account staff = fixture.staff(role,
                    role == UserRole.PORTAL_ADMIN ? null : institutions.example(),
                    role == UserRole.DEPARTMENT_COORDINATOR ? institutions.exampleCse() : null);
            String staffToken = fixture.login(mockMvc, staff.email());

            call(get("/api/consents"), staffToken, 403);
            call(post("/api/consents/ai-processing/accept").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"noticeVersionId\":\"" + noticeId + "\"}"), staffToken, 403);
        }
        assertThat(jdbc.queryForObject("select count(*) from consent_records where user_id = ?", Long.class,
                student.userId())).isZero();
    }

    @Test
    @DisplayName("a published notice cannot be rewritten, and neither it nor a consent row can be edited")
    void noticesAndRecordsAreImmutable() {
        NoticeVersion current = notices.current(ConsentPurpose.AI_PROCESSING);
        String original = current.getChecksum();
        try {
            jdbc.update("update notice_versions set checksum = ? where id = ?", "0".repeat(64), current.getId());
            NoticeRegistry uncached = new NoticeRegistry(noticeRepository, consentProperties, transactionManager);

            assertThatThrownBy(() -> uncached.current(ConsentPurpose.AI_PROCESSING))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("new version");
        } finally {
            jdbc.update("update notice_versions set checksum = ? where id = ?", original, current.getId());
        }
        assertThat(Arrays.stream(NoticeVersion.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("set"));
        assertThat(Arrays.stream(ConsentRecord.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("set"));
    }
}
