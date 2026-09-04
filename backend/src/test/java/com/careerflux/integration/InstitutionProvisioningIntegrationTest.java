package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.repository.InstitutionRepository;
import com.careerflux.institution.service.EnrolmentService;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
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
 * Onboarding a college through the API.
 *
 * <p>Before this endpoint existed, a freshly deployed pilot could not create its
 * first institution at all: the only code that could was the demo seeder, which
 * a real deployment must never run. The alternative was an INSERT typed by hand
 * against the tenant boundary table.
 *
 * <p>These tests are therefore as much about what the endpoint refuses as what
 * it creates — a mistake here does not produce a bad row, it produces a college
 * whose students cannot register, or two colleges claiming one address.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InstitutionProvisioningIntegrationTest {

    private static final String PASSWORD = "IntegrationTest123!";
    private static final String PATH = "/api/admin/institutions";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InstitutionRepository institutionRepository;

    @Autowired
    private EnrolmentService enrolmentService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    // ------------------------------------------------------------- creating

    @Test
    @DisplayName("a platform administrator onboards a college")
    void platformAdminCreatesAnInstitution() throws Exception {
        String token = signIn(seed("provisioner@careerflux.test", UserRole.PLATFORM_ADMIN, null));

        String body = mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Northgate Institute of Technology",
                                 "emailDomains":"northgate.edu",
                                 "city":"Hyderabad"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Northgate Institute of Technology"))
                .andExpect(jsonPath("$.slug").value("northgate-institute-of-technology"))
                .andExpect(jsonPath("$.emailDomains").value("northgate.edu"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        Institution created = institutionRepository.findById(id).orElseThrow();
        assertThat(created.getEmailDomains()).isEqualTo("northgate.edu");
        assertThat(created.getCity()).isEqualTo("Hyderabad");
    }

    @Test
    @DisplayName("the new college starts empty — no departments, batches, students or requirements")
    void theNewCollegeStartsClean() throws Exception {
        // A tenant that arrives pre-populated with plausible fixtures is how
        // demonstration data ends up mistaken for a college's real records.
        String token = signIn(seed("clean@careerflux.test", UserRole.PLATFORM_ADMIN, null));
        UUID id = createCollege(token, "Clean Start College", "cleanstart.edu");

        assertThat(userRepository.findAll().stream()
                .filter(user -> user.getInstitution() != null && id.equals(user.getInstitution().getId())))
                .describedAs("no users should have been invented for the new college")
                .isEmpty();
    }

    @Test
    @DisplayName("the slug is derived, never taken from the request")
    void slugIsDerivedServerSide() throws Exception {
        // The identity of a tenant is not the client's to choose. An unknown
        // field is ignored rather than honoured.
        String token = signIn(seed("slug@careerflux.test", UserRole.PLATFORM_ADMIN, null));

        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Derived Name College","emailDomains":"derived.edu",
                                 "slug":"attacker-chosen","id":"11111111-1111-1111-1111-111111111111"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("derived-name-college"))
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(
                        "11111111-1111-1111-1111-111111111111")));
    }

    // -------------------------------------------------------- authorization

    @Test
    @DisplayName("no institutional role may create a college, however senior")
    void institutionalRolesAreRefused() throws Exception {
        // A college administrator running a college is not thereby able to
        // create another one. This is the platform boundary.
        record Case(String email, UserRole role) {
        }
        for (Case each : new Case[] {
                new Case("ca@example.com", UserRole.COLLEGE_ADMIN),
                new Case("po@example.com", UserRole.PLACEMENT_OFFICER),
                new Case("pc@example.com", UserRole.PLACEMENT_COORDINATOR),
                new Case("st@example.com", UserRole.STUDENT)}) {
            String token = signIn(seed(each.email(), each.role(), institutions.example()));
            mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Sneaky College","emailDomains":"sneaky.edu"}
                                    """))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(PATH).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
        assertThat(institutionRepository.findBySlug("sneaky-college")).isEmpty();
    }

    @Test
    @DisplayName("an anonymous caller is refused before anything else happens")
    void anonymousIsRefused() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Anonymous College","emailDomains":"anon.edu"}
                                """))
                .andExpect(status().isUnauthorized());
        assertThat(institutionRepository.findBySlug("anonymous-college")).isEmpty();
    }

    // ------------------------------------------------------------ conflicts

    @Test
    @DisplayName("a domain another college already claims is refused")
    void duplicateDomainRefused() throws Exception {
        // EnrolmentService refuses to guess when two colleges claim one address,
        // so the cost of allowing this is every affected student being turned
        // away at registration.
        String token = signIn(seed("dupe@careerflux.test", UserRole.PLATFORM_ADMIN, null));

        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Copycat College","emailDomains":"%s"}
                                """.formatted(TestInstitutions.EXAMPLE_DOMAIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(TestInstitutions.EXAMPLE_DOMAIN)));
    }

    @Test
    @DisplayName("a domain that would swallow another college's subdomain is refused too")
    void overlappingDomainRefused() throws Exception {
        // A claim covers its subdomains, so claiming the parent of somebody
        // else's domain makes both colleges answer for the same student. String
        // equality would miss this; the check runs the real matching rule.
        String token = signIn(seed("overlap@careerflux.test", UserRole.PLATFORM_ADMIN, null));
        createCollege(token, "Sub College", "cse.subcollege.edu");

        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Parent College","emailDomains":"subcollege.edu"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a college with the same name is refused")
    void duplicateNameRefused() throws Exception {
        String token = signIn(seed("name@careerflux.test", UserRole.PLATFORM_ADMIN, null));
        createCollege(token, "Twice Named College", "twice-one.edu");

        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Twice Named College","emailDomains":"twice-two.edu"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a registration code another college uses is refused, in any spelling")
    void duplicateRegistrationCodeRefused() throws Exception {
        // Codes are matched case-insensitively when a student uses one, so two
        // colleges sharing one under different spellings is the same collision.
        String token = signIn(seed("code@careerflux.test", UserRole.PLATFORM_ADMIN, null));

        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Code Clash College","registrationCode":"%s"}
                                """.formatted(TestInstitutions.EXAMPLE_CODE.toLowerCase())))
                .andExpect(status().isConflict());
    }

    // ----------------------------------------------------------- validation

    @Test
    @DisplayName("a URL or an address is refused where a domain belongs")
    void invalidDomainRefused() throws Exception {
        String token = signIn(seed("bad@careerflux.test", UserRole.PLATFORM_ADMIN, null));
        for (String bad : new String[] {
                "https://northgate.edu", "northgate.edu/students", "someone@northgate.edu", "northgate"}) {
            mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Bad Domain College","emailDomains":"%s"}
                                    """.formatted(bad)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("a college claiming neither a domain nor a code is refused")
    void aCollegeNobodyCanJoinIsRefused() throws Exception {
        // Not pedantry: with no domain to match and no code to hand out, nobody
        // can ever register against it. Better to say so now than to let a
        // placement office discover it with their first student.
        String token = signIn(seed("nothing@careerflux.test", UserRole.PLATFORM_ADMIN, null));

        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Unreachable College"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("nobody can register")));
    }

    @Test
    @DisplayName("a domain is normalised on the way in")
    void domainIsNormalised() throws Exception {
        String token = signIn(seed("norm@careerflux.test", UserRole.PLATFORM_ADMIN, null));

        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Normalised College","emailDomains":" @Normalised.EDU "}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailDomains").value("normalised.edu"));
    }

    // ------------------------------------------------- the first administrator

    @Test
    @DisplayName("the first college administrator is created and belongs to the new college")
    void initialAdminIsAttachedToTheNewInstitution() throws Exception {
        String token = signIn(seed("withadmin@careerflux.test", UserRole.PLATFORM_ADMIN, null));

        String body = mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Handover College","emailDomains":"handover.edu",
                                 "initialAdmin":{"fullName":"Anita Rao",
                                                 "email":"anita@handover.edu",
                                                 "password":"HandoverAdmin!2026"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.collegeAdminEmail").value("anita@handover.edu"))
                .andExpect(jsonPath("$.collegeAdminId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        UUID institutionId = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        User admin = userRepository.findByEmailIgnoreCase("anita@handover.edu").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(UserRole.COLLEGE_ADMIN);
        assertThat(admin.getInstitutionId()).isEqualTo(institutionId);
        assertThat(admin.isActive()).isTrue();
    }

    @Test
    @DisplayName("the response never carries the password, and the stored hash is not it")
    void thePasswordIsNeverReturnedOrStoredInTheClear() throws Exception {
        String token = signIn(seed("secret@careerflux.test", UserRole.PLATFORM_ADMIN, null));
        String secret = "NeverEchoed!2026";

        String body = mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Secret College","emailDomains":"secretcollege.edu",
                                 "initialAdmin":{"fullName":"Ravi Menon",
                                                 "email":"ravi@secretcollege.edu",
                                                 "password":"%s"}}
                                """.formatted(secret)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(secret);
        assertThat(body).doesNotContain("password", "accessToken", "refreshToken");

        User admin = userRepository.findByEmailIgnoreCase("ravi@secretcollege.edu").orElseThrow();
        assertThat(admin.getPasswordHash()).isNotEqualTo(secret);
        assertThat(passwordEncoder.matches(secret, admin.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("the new administrator can sign in afterwards")
    void theNewAdminCanSignIn() throws Exception {
        String token = signIn(seed("login@careerflux.test", UserRole.PLATFORM_ADMIN, null));
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Signin College","emailDomains":"signincollege.edu",
                                 "initialAdmin":{"fullName":"Meera Iyer",
                                                 "email":"meera@signincollege.edu",
                                                 "password":"MeeraAdmin!2026"}}
                                """))
                .andExpect(status().isCreated());

        String session = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"meera@signincollege.edu","password":"MeeraAdmin!2026"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode user = objectMapper.readTree(session).get("user");
        assertThat(user.get("role").asText()).isEqualTo("COLLEGE_ADMIN");
        assertThat(user.get("institutionName").asText()).isEqualTo("Signin College");
    }

    // Rollback and concurrency live in InstitutionProvisioningCommitTest, not
    // here. This class is @Transactional, so the service's transaction joins the
    // test's and nothing is ever committed — a rollback is invisible, and a
    // second thread cannot see the row it is supposed to collide with.

    // ------------------------------------------------ registration afterwards

    @Test
    @DisplayName("a student at the new college can register by email domain")
    void selfRegistrationResolvesTheNewInstitution() throws Exception {
        // The whole point of onboarding. Self-registration is untouched; this
        // asserts the existing path now finds the college that was just made.
        String token = signIn(seed("resolve@careerflux.test", UserRole.PLATFORM_ADMIN, null));
        UUID id = createCollege(token, "Resolvable Institute", "resolvable.edu");

        Institution resolved = enrolmentService.resolveForRegistration("student@resolvable.edu", null);
        assertThat(resolved.getId()).isEqualTo(id);

        String session = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"student@resolvable.edu","password":"ResolvableStudent!2026",
                                 "fullName":"Resolvable Student"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(session).get("user").get("institutionName").asText())
                .isEqualTo("Resolvable Institute");
        assertThat(userRepository.findByEmailIgnoreCase("student@resolvable.edu").orElseThrow()
                .getInstitutionId()).isEqualTo(id);
    }

    @Test
    @DisplayName("someone from another domain is still refused")
    void unrelatedDomainStillUnresolved() throws Exception {
        String token = signIn(seed("unrelated@careerflux.test", UserRole.PLATFORM_ADMIN, null));
        createCollege(token, "Closed Institute", "closedinstitute.edu");

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"outsider@somewhere-else.edu","password":"Outsider!2026",
                                 "fullName":"Outsider"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the new college inherits nothing from any existing one")
    void nothingIsInheritedFromAnotherTenant() throws Exception {
        String token = signIn(seed("tenancy@careerflux.test", UserRole.PLATFORM_ADMIN, null));
        UUID id = createCollege(token, "Isolated Institute", "isolatedinstitute.edu");
        Institution created = institutionRepository.findById(id).orElseThrow();

        assertThat(created.getId()).isNotEqualTo(institutions.example().getId());
        assertThat(created.acceptsEmail("student@" + TestInstitutions.EXAMPLE_DOMAIN)).isFalse();
        assertThat(created.getRegistrationCode()).isNull();
        assertThat(institutions.example().acceptsEmail("student@isolatedinstitute.edu")).isFalse();
    }

    // --------------------------------------------------------------- listing

    @Test
    @DisplayName("the list shows colleges and nothing belonging to them")
    void listCarriesNoTenantData() throws Exception {
        String token = signIn(seed("lister@careerflux.test", UserRole.PLATFORM_ADMIN, null));
        String body = mockMvc.perform(get(PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(TestInstitutions.EXAMPLE_SLUG);
        assertThat(body).doesNotContain("passwordHash", "students", "registrationCode");
    }

    // --------------------------------------------------------------- helpers

    private UUID createCollege(String token, String name, String domain) throws Exception {
        String body = mockMvc.perform(post(PATH).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","emailDomains":"%s"}
                                """.formatted(name, domain)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private User seed(String email, UserRole role, Institution institution) {
        User user = new User();
        user.setEmail(email);
        user.setFullName("Test " + role.name());
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institution);
        return userRepository.saveAndFlush(user);
    }

    private String signIn(User user) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
