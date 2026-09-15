package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.careerflux.audit.AuditEvent;
import com.careerflux.audit.AuditEventRepository;
import com.careerflux.audit.AuditService;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.security.AuthenticatedUser;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may read the audit trail, and what it says about people (Phase 2A, F3).
 *
 * <p>A college's events — what its staff and students did — belong to that
 * college and are read by its placement coordinator. The Portal Admin reads the
 * platform's own trail, which holds no college's events: running the platform
 * is not a reason to read a college's record of its people. Neither trail names
 * anybody by email; a person is an account id and a role.
 *
 * <p>Audit rows are written in their own transaction and outlive each test's
 * rollback, so every assertion finds its own rows by a marker unique to the
 * test rather than by counting.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuditPrivacyIntegrationTest {

    private static final String PASSWORD = "IntegrationTest123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StaffScopeRepository staffScopeRepository;

    @Autowired
    private AuditEventRepository auditRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    private String unique;

    @BeforeEach
    void setUp() {
        unique = Long.toString(System.nanoTime());
    }

    @Test
    @DisplayName("a college's events are read by its own placement coordinator, by account and role")
    void collegeReadsItsOwnTrail() throws Exception {
        User coordinator = staff("audit-pc", UserRole.PLACEMENT_COORDINATOR, institutions.example());
        String token = login(coordinator);
        String code = createDepartment(token);

        JsonNode page = json(get("/api/institution/audit?size=200"), token, 200);
        JsonNode row = rowWith(page, "DEPARTMENT_CREATED", code);

        assertThat(row).describedAs("the department it just created").isNotNull();
        assertThat(row.get("actorUserId").asText()).isEqualTo(coordinator.getId().toString());
        assertThat(row.get("actorRole").asText()).isEqualTo("PLACEMENT_COORDINATOR");
        assertThat(row.get("actorLabel").asText()).isEqualTo("PLACEMENT_COORDINATOR");
        assertThat(page.toString()).describedAs("no address anywhere in the trail").doesNotContain("@");
    }

    @Test
    @DisplayName("another college never sees it")
    void anotherCollegeCannotReadIt() throws Exception {
        String ours = login(staff("audit-ours", UserRole.PLACEMENT_COORDINATOR, institutions.example()));
        String code = createDepartment(ours);

        String theirs = login(staff("audit-theirs", UserRole.PLACEMENT_COORDINATOR, institutions.rival()));
        JsonNode page = json(get("/api/institution/audit?size=200"), theirs, 200);

        assertThat(rowWith(page, "DEPARTMENT_CREATED", code)).isNull();
    }

    @Test
    @DisplayName("the Portal Admin's trail holds no college's events and no addresses")
    void platformTrailHoldsNoCollegeEvents() throws Exception {
        String college = login(staff("audit-college", UserRole.PLACEMENT_COORDINATOR, institutions.example()));
        String code = createDepartment(college);
        String admin = login(staff("audit-admin", UserRole.PORTAL_ADMIN, null));

        JsonNode page = json(get("/api/admin/ops/audit?size=200"), admin, 200);

        assertThat(rowWith(page, "DEPARTMENT_CREATED", code)).isNull();
        assertThat(page.get("content").findValuesAsText("actorRole"))
                .describedAs("nothing a college's staff or students did")
                .doesNotContain("PLACEMENT_COORDINATOR", "DEPARTMENT_COORDINATOR", "STUDENT");
        assertThat(page.toString()).doesNotContain("@");
    }

    @Test
    @DisplayName("the Portal Admin's own work is in their trail, attributed by role and id")
    void platformTrailHoldsThePortalAdminsWork() throws Exception {
        User admin = staff("audit-operator", UserRole.PORTAL_ADMIN, null);
        String action = "PLATFORM_CHECK_" + unique;
        runAs(admin, () -> auditService.record(action, "Platform", UUID.randomUUID(), "operator work"));

        JsonNode page = json(get("/api/admin/ops/audit?size=200"), login(admin), 200);
        JsonNode row = rowWith(page, action, null);

        assertThat(row).isNotNull();
        assertThat(row.get("actorUserId").asText()).isEqualTo(admin.getId().toString());
        assertThat(row.get("actorRole").asText()).isEqualTo("PORTAL_ADMIN");
    }

    @Test
    @DisplayName("nobody else reads a college's trail: not a department coordinator, a student or the Portal Admin")
    void onlyThePlacementCoordinatorReadsTheCollegeTrail() throws Exception {
        User coordinator = staff("audit-dc", UserRole.DEPARTMENT_COORDINATOR, institutions.example());
        staffScopeRepository.saveAndFlush(StaffScope.forDepartment(
                coordinator, institutions.example(), institutions.exampleCse()));
        String student = register("audit-student-" + unique + "@example.com");
        String admin = login(staff("audit-admin-refused", UserRole.PORTAL_ADMIN, null));

        for (String token : List.of(login(coordinator), student, admin)) {
            mockMvc.perform(get("/api/institution/audit").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/institution/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a college's placement coordinator cannot read the platform's trail")
    void collegeCannotReadThePlatformTrail() throws Exception {
        String college = login(staff("audit-pc-platform", UserRole.PLACEMENT_COORDINATOR,
                institutions.example()));

        mockMvc.perform(get("/api/admin/ops/audit").header("Authorization", "Bearer " + college))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a stored row names its actor by id, role and college, never by address")
    void storedRowsCarryIdentityNotAddress() throws Exception {
        User coordinator = staff("audit-stored", UserRole.PLACEMENT_COORDINATOR, institutions.example());
        String code = createDepartment(login(coordinator));

        AuditEvent stored = auditRepository
                .findByInstitutionIdOrderByOccurredAtDesc(institutions.example().getId(), PageRequest.of(0, 500))
                .stream()
                .filter(event -> "DEPARTMENT_CREATED".equals(event.getAction())
                        && event.getDetail() != null && event.getDetail().contains(code))
                .findFirst().orElseThrow();

        assertThat(stored.getActor()).doesNotContain("@");
        assertThat(stored.getActorUserId()).isEqualTo(coordinator.getId());
        assertThat(stored.getActorRole()).isEqualTo("PLACEMENT_COORDINATOR");
        assertThat(stored.getInstitutionId()).isEqualTo(institutions.example().getId());
    }

    @Test
    @DisplayName("a registration belongs to the student's college and names the student by id")
    void registrationBelongsToTheCollege() throws Exception {
        String email = "audit-registers-" + unique + "@example.com";
        register(email);
        User student = userRepository.findByEmailIgnoreCase(email).orElseThrow();

        AuditEvent registered = collegeRow("USER_REGISTERED", student.getId());
        assertThat(registered.getActorUserId()).isEqualTo(student.getId());
        assertThat(registered.getActorRole()).isEqualTo("STUDENT");
        assertThat(registered.getActor()).doesNotContain("@");

        String admin = login(staff("audit-admin-registration", UserRole.PORTAL_ADMIN, null));
        JsonNode platform = json(get("/api/admin/ops/audit?size=200"), admin, 200);
        assertThat(platform.get("content").findValuesAsText("entityId"))
                .doesNotContain(student.getId().toString());
    }

    @Test
    @DisplayName("a reset request belongs to the account's college but is not attributed to the account holder")
    void resetRequestIsNotAttributed() throws Exception {
        String email = "audit-reset-" + unique + "@example.com";
        register(email);
        User student = userRepository.findByEmailIgnoreCase(email).orElseThrow();

        mockMvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().is2xxSuccessful());

        AuditEvent requested = collegeRow("PASSWORD_RESET_REQUESTED", student.getId());
        // Anybody may ask for a reset for any address, so it is not the student's act.
        assertThat(requested.getActorUserId()).isNull();
        assertThat(requested.getActor()).isEqualTo("unauthenticated");
    }

    @Test
    @DisplayName("text written before the change never shows an address")
    void olderAddressesAreRemovedOnTheWayOut() throws Exception {
        AuditEvent legacy = new AuditEvent();
        legacy.setActor("legacy.person@example.com");
        legacy.setAction("LEGACY_" + unique);
        legacy.setDetail("reviewed by legacy.person@example.com");
        legacy.setOccurredAt(Instant.now());
        auditRepository.saveAndFlush(legacy);

        String admin = login(staff("audit-admin-legacy", UserRole.PORTAL_ADMIN, null));
        JsonNode row = rowWith(json(get("/api/admin/ops/audit?size=200"), admin, 200), "LEGACY_" + unique, null);

        assertThat(row).isNotNull();
        assertThat(row.toString()).doesNotContain("@").contains("[address removed]");
    }

    // --------------------------------------------------------------- helpers

    private AuditEvent collegeRow(String action, UUID entityId) {
        return auditRepository
                .findByInstitutionIdOrderByOccurredAtDesc(institutions.example().getId(), PageRequest.of(0, 500))
                .stream()
                .filter(event -> action.equals(event.getAction())
                        && entityId.toString().equals(event.getEntityId()))
                .findFirst().orElseThrow();
    }

    /** The row with this action, and this text in its detail when one is named. */
    private static JsonNode rowWith(JsonNode page, String action, String detailContains) {
        List<JsonNode> rows = new ArrayList<>();
        page.get("content").forEach(rows::add);
        return rows.stream()
                .filter(row -> action.equals(row.get("action").asText()))
                .filter(row -> detailContains == null
                        || (row.hasNonNull("detail") && row.get("detail").asText().contains(detailContains)))
                .findFirst().orElse(null);
    }

    private String createDepartment(String token) throws Exception {
        String code = "AU" + unique.substring(unique.length() - 6);
        mockMvc.perform(post("/api/institution/departments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Audit Department %s\",\"code\":\"%s\"}".formatted(unique, code)))
                .andExpect(status().isCreated());
        return code;
    }

    private void runAs(User user, Runnable action) {
        AuthenticatedUser principal = new AuthenticatedUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String register(String email) throws Exception {
        return json(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\",\"fullName\":\"Audit Student\"}"
                        .formatted(email, PASSWORD)), null, 201).get("accessToken").asText();
    }

    private User staff(String name, UserRole role, Institution institution) {
        User user = new User();
        user.setEmail(name + "-" + unique + (institution == null ? "@careerflux.local"
                : "@" + (institution.getId().equals(institutions.rival().getId()) ? "rival.edu" : "example.com")));
        user.setFullName("Staff Member");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institution);
        return userRepository.saveAndFlush(user);
    }

    private String login(User user) throws Exception {
        return json(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(user.getEmail(), PASSWORD)),
                null, 200).get("accessToken").asText();
    }

    private JsonNode json(MockHttpServletRequestBuilder request, String token, int expected) throws Exception {
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return objectMapper.readTree(mockMvc.perform(request)
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString());
    }
}
