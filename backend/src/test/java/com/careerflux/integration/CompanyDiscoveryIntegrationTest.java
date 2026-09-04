package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.careerflux.source.domain.Company;
import com.careerflux.source.repository.CompanyRepository;
import com.careerflux.source.repository.JobSourceRepository;
import com.careerflux.source.service.SourceRegistryService;
import com.careerflux.support.TestInstitutions;
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
 * Company discovery over HTTP.
 *
 * <p>What matters here is not that a name resolves — that is unit-tested without
 * a network — but that the endpoint cannot be used to do the two things it must
 * never do: reach an address CareerFlux does not reach, and register a source
 * without a person having confirmed the domain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CompanyDiscoveryIntegrationTest {

    private static final String PASSWORD = "IntegrationTest123!";
    private static final String URL = "/api/admin/sources/discover-by-company";

    /** Shared across the class so the rate-limited login is hit once per role. */
    private static final java.util.Map<UserRole, String> TOKENS =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private JobSourceRepository sourceRepository;

    @Autowired
    private SourceRegistryService registryService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    // ------------------------------------------------------------ authority

    @Nested
    @DisplayName("who may ask")
    class Authority {

        @Test
        @DisplayName("only a platform operator")
        void platformAdminOnly() throws Exception {
            String body = """
                    {"companyNames":["Nothing Here Ltd"]}""";

            mockMvc.perform(request(body, token(UserRole.PLATFORM_ADMIN)))
                    .andExpect(status().isOk());

            for (UserRole role : List.of(UserRole.PLACEMENT_OFFICER, UserRole.COLLEGE_ADMIN,
                    UserRole.PLACEMENT_COORDINATOR, UserRole.STUDENT)) {
                mockMvc.perform(request(body, token(role)))
                        .andExpect(status().isForbidden());
            }
        }

        @Test
        @DisplayName("not anonymously")
        void anonymousRefused() throws Exception {
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"companyNames":["Acme"]}"""))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ------------------------------------------------------- the SSRF guard

    @Nested
    @DisplayName("domains CareerFlux will not fetch")
    class Ssrf {

        @Test
        @DisplayName("a confirmed private address registers nothing")
        void confirmedPrivateAddressIsRefused() throws Exception {
            long before = sourceRepository.count();

            // Confirmation is not authorisation to fetch: the domain comes from
            // a browser and is checked like any other operator input.
            for (String hostile : List.of("127.0.0.1", "localhost", "10.0.0.1",
                    "169.254.169.254", "192.168.1.1", "[::1]")) {
                mockMvc.perform(request("""
                                {"companyNames":["Evil"],"confirmedDomains":["%s"]}"""
                                .formatted(hostile), token(UserRole.PLATFORM_ADMIN)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.registered.length()").value(0));
            }

            assertThat(sourceRepository.count())
                    .describedAs("no source may be created from an address we refuse to fetch")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("the refusal reports the domain back rather than failing silently")
        void refusalIsReported() throws Exception {
            mockMvc.perform(request("""
                            {"companyNames":["Evil"],"confirmedDomains":["127.0.0.1"]}""",
                            token(UserRole.PLATFORM_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.registered.length()").value(0))
                    .andExpect(jsonPath("$.withoutBoard.length()").value(1));
        }

        @Test
        @DisplayName("registering a source at a private address is refused as a bad request")
        void registrationIsGuardedToo() throws Exception {
            // The other operator-controlled path to an outbound fetch. It must
            // fail loudly here, at the point of entry, rather than being stored
            // and failing quietly on the first sync.
            for (String hostile : List.of("https://127.0.0.1/jobs", "https://169.254.169.254/",
                    "http://example.com/jobs", "https://example.com:8080/jobs")) {
                mockMvc.perform(post("/api/admin/sources")
                                .header("Authorization", "Bearer " + token(UserRole.PLATFORM_ADMIN))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Hostile","baseUrl":"%s"}""".formatted(hostile)))
                        .andExpect(status().isBadRequest());
            }
        }
    }

    // --------------------------------------------------- resolve vs discover

    @Nested
    @DisplayName("resolving is not discovering")
    class Confirmation {

        @Test
        @DisplayName("a resolve registers nothing, whatever it finds")
        void resolveOnlyNeverRegisters() throws Exception {
            long sourcesBefore = sourceRepository.count();

            mockMvc.perform(request("""
                            {"companyNames":["Razorpay","Meesho"]}""",
                            token(UserRole.PLATFORM_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.registered.length()").value(0))
                    .andExpect(jsonPath("$.alreadyKnown.length()").value(0))
                    .andExpect(jsonPath("$.withoutBoard.length()").value(0));

            assertThat(sourceRepository.count()).isEqualTo(sourcesBefore);
        }

        @Test
        @DisplayName("a known company resolves from the registry without touching the network")
        void resolvesFromTheRegistry() throws Exception {
            Company known = registryService.findOrCreateCompany(
                    "Integration Test Co", "https://integration-test-co.example");

            String body = mockMvc.perform(request("""
                            {"companyNames":["Integration Test Co"]}""",
                            token(UserRole.PLATFORM_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resolved.length()").value(1))
                    .andExpect(jsonPath("$.resolved[0].outcome").value("RESOLVED"))
                    .andExpect(jsonPath("$.resolved[0].candidates[0].evidence").value("REGISTRY"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).contains("integration-test-co.example");
            assertThat(known.getSlug()).isEqualTo("integration-test-co");
        }

        @Test
        @DisplayName("a name that resolves to nothing says so instead of guessing")
        void notFoundIsAnAnswer() throws Exception {
            mockMvc.perform(request("""
                            {"companyNames":["Zzz Nonexistent Employer Xyz"]}""",
                            token(UserRole.PLATFORM_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.notFound.length()").value(1))
                    .andExpect(jsonPath("$.notFound[0].outcome").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.notFound[0].candidates.length()").value(0));
        }

        @Test
        @DisplayName("an empty or missing name list is handled, not thrown at")
        void emptyInput() throws Exception {
            for (String body : List.of("{}", """
                    {"companyNames":[]}""", """
                    {"companyNames":[null,"","   "]}""")) {
                mockMvc.perform(request(body, token(UserRole.PLATFORM_ADMIN)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.resolved.length()").value(0))
                        .andExpect(jsonPath("$.registered.length()").value(0));
            }
        }
    }

    // --------------------------------------------------------- company linkage

    @Nested
    @DisplayName("company identity and linkage")
    class Linkage {

        @Test
        @DisplayName("a company created by ingestion gets its domain filled in later")
        void fillsTheDomainGap() {
            // The live situation: companies exist from ingestion with no website
            // and therefore no domain, so the registry cannot say which sources
            // belong to an employer.
            Company fromIngestion = registryService.findOrCreateCompany("Gap Filler Ltd", null);
            assertThat(fromIngestion.getDomain()).isNull();

            Company sameCompany = registryService.findOrCreateCompany(
                    "Gap Filler Ltd", "https://gapfiller.example");

            assertThat(sameCompany.getId()).isEqualTo(fromIngestion.getId());
            assertThat(sameCompany.getDomain()).isEqualTo("gapfiller.example");
            assertThat(companyRepository.findBySlug("gap-filler-ltd")).isPresent();
        }

        @Test
        @DisplayName("a domain already recorded is never overwritten")
        void neverOverwritesAKnownDomain() {
            Company original = registryService.findOrCreateCompany(
                    "Stable Co", "https://stable-co.example");
            assertThat(original.getDomain()).isEqualTo("stable-co.example");

            // A later guess must not quietly replace a fact somebody recorded.
            Company again = registryService.findOrCreateCompany(
                    "Stable Co", "https://something-else.example");

            assertThat(again.getId()).isEqualTo(original.getId());
            assertThat(again.getDomain()).isEqualTo("stable-co.example");
        }

        @Test
        @DisplayName("names differing only in case or spacing are one company, not duplicates")
        void slugIsTheIdentity() {
            long before = companyRepository.count();
            Company first = registryService.findOrCreateCompany("Dedupe Test", null);

            for (String spelling : List.of("dedupe test", "  DEDUPE TEST  ", "Dedupe  Test")) {
                assertThat(registryService.findOrCreateCompany(spelling, null).getId())
                        .describedAs("spelling '%s'", spelling)
                        .isEqualTo(first.getId());
            }
            assertThat(companyRepository.count())
                    .describedAs("discovery must not fork an employer into duplicates")
                    .isEqualTo(before + 1);
        }
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            String body, String token) {
        return post(URL)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /**
     * Signs in once per role and keeps the token.
     *
     * <p>The login endpoint is rate limited, deliberately, and a helper that
     * re-authenticates on every assertion trips that limit and fails the suite
     * for a reason that has nothing to do with what is being tested.
     */
    private String token(UserRole role) throws Exception {
        String cached = TOKENS.get(role);
        if (cached != null) {
            return cached;
        }
        String email = "cd-" + role.name().toLowerCase() + "@example.com";

        // Committed in its own transaction. This class is @Transactional and
        // rolls back, so an account created inside a test is gone by the next
        // one — and a cached token would then authenticate to a user that no
        // longer exists, which is a 401 that says nothing about the endpoint.
        var template = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> {
            if (userRepository.findByEmailIgnoreCase(email).isEmpty()) {
                User user = new User();
                user.setEmail(email);
                user.setFullName("Discovery " + role);
                user.setPasswordHash(passwordEncoder.encode(PASSWORD));
                user.setRole(role);
                user.setStatus(UserStatus.ACTIVE);
                if (role != UserRole.PLATFORM_ADMIN) {
                    user.setInstitution(institutions.example());
                }
                userRepository.saveAndFlush(user);
            }
        });
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("accessToken").asText();
        TOKENS.put(role, token);
        return token;
    }
}
