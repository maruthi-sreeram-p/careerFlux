package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.careerflux.auth.AuthService;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.InstitutionStatus;
import com.careerflux.institution.repository.InstitutionRepository;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * What makes a session valid, and everything that ends one.
 *
 * <p>Through the real filter chain, with real tokens. Where a token has to be
 * built by hand — expired, signed with another key, or signed correctly but
 * claiming a role its owner does not have — it is signed with the test
 * profile's own secret, which is the strongest position an attacker could be
 * in short of the production key.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SessionSecurityIntegrationTest {

    private static final String PASSWORD = "SessionTest123!";
    private static final String NEW_PASSWORD = "SessionTest456!";
    private static final String BAD_CREDENTIALS = "Email or password is incorrect.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InstitutionRepository institutionRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private CareerFluxProperties properties;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private record Session(String access, String refresh) {
    }

    /** Unique per call, so the shared sign-in limiter never sees one address twice across tests. */
    private static String email(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private Session register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","fullName":"Session Test"}
                                """.formatted(email, PASSWORD)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Session(body.get("accessToken").asText(), body.get("refreshToken").asText());
    }

    private MvcResult loginResult(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andReturn();
    }

    private Session login(String email, String password) throws Exception {
        MvcResult result = loginResult(email, password);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Session(body.get("accessToken").asText(), body.get("refreshToken").asText());
    }

    private int me(String bearer) throws Exception {
        var request = get("/api/auth/me");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private int refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private String message(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("message").asText();
    }

    private SecretKey testKey() {
        return Keys.hmacShaKeyFor(properties.security().jwt().secret().getBytes(StandardCharsets.UTF_8));
    }

    private static String forge(SecretKey key, UUID subject, String role, String type,
                                Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .subject(subject.toString())
                .claims(Map.of("email", "forged@example.com", "role", role, "typ", type,
                        "iat_ms", issuedAt.toEpochMilli()))
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private User userFor(String email) {
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    // ---------------------------------------------------------------- tokens

    @Nested
    @DisplayName("which tokens are honoured")
    class Tokens {

        @Test
        @DisplayName("a signed-in session works, and a wrong password and an unknown address answer alike")
        void validLoginAndUniformFailure() throws Exception {
            String email = email("valid");
            register(email);

            assertThat(me(login(email, PASSWORD).access())).isEqualTo(200);

            MvcResult wrong = loginResult(email, "NotThePassword1!");
            MvcResult unknown = loginResult(email("unknown"), "NotThePassword1!");
            assertThat(wrong.getResponse().getStatus()).isEqualTo(403);
            assertThat(unknown.getResponse().getStatus()).isEqualTo(403);
            assertThat(message(wrong)).isEqualTo(BAD_CREDENTIALS).isEqualTo(message(unknown));
        }

        @Test
        @DisplayName("no token, a malformed one, or one signed with another key is refused")
        void missingMalformedAndForeign() throws Exception {
            String email = email("foreign");
            register(email);
            UUID id = userFor(email).getId();
            SecretKey foreignKey = Keys.hmacShaKeyFor("a-completely-different-key-0123456789abcdef!!"
                    .getBytes(StandardCharsets.UTF_8));

            assertThat(me(null)).isEqualTo(401);
            assertThat(me("not-a-token")).isEqualTo(401);
            assertThat(me("aaaa.bbbb.cccc")).isEqualTo(401);
            assertThat(me(forge(foreignKey, id, "STUDENT", "access", Instant.now(),
                    Instant.now().plus(Duration.ofHours(1))))).isEqualTo(401);
        }

        @Test
        @DisplayName("an expired token is refused even though its signature is good")
        void expiredIsRefused() throws Exception {
            String email = email("expired");
            register(email);
            UUID id = userFor(email).getId();
            Instant past = Instant.now().minus(Duration.ofHours(3));

            assertThat(me(forge(testKey(), id, "STUDENT", "access", past, past.plus(Duration.ofHours(2)))))
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("a refresh token is not an access token, and an access token cannot refresh")
        void typesAreNotInterchangeable() throws Exception {
            String email = email("types");
            Session session = register(email);

            assertThat(me(session.refresh())).isEqualTo(401);
            assertThat(refresh(session.access())).isEqualTo(403);
            assertThat(refresh(session.refresh())).isEqualTo(200);
        }

        @Test
        @DisplayName("a correctly signed token claiming a staff role still carries only the stored role")
        void forgedRoleClaimGrantsNothing() throws Exception {
            String email = email("forged-role");
            register(email);
            UUID id = userFor(email).getId();
            String claimsCoordinator = forge(testKey(), id, "PLACEMENT_COORDINATOR", "access",
                    Instant.now(), Instant.now().plus(Duration.ofHours(1)));

            assertThat(mockMvc.perform(get("/api/institution/students")
                            .header("Authorization", "Bearer " + claimsCoordinator))
                    .andReturn().getResponse().getStatus()).isEqualTo(403);

            JsonNode session = objectMapper.readTree(mockMvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer " + claimsCoordinator))
                    .andReturn().getResponse().getContentAsString());
            assertThat(session.get("role").asText()).isEqualTo("STUDENT");
        }

        @Test
        @DisplayName("editing a real token's payload breaks its signature")
        void tamperedPayloadIsRefused() throws Exception {
            String email = email("tampered");
            Session session = register(email);
            String[] parts = session.access().split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                    .replace("\"STUDENT\"", "\"PLACEMENT_COORDINATOR\"");
            String tampered = parts[0] + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                    + "." + parts[2];

            assertThat(me(tampered)).isEqualTo(401);
        }

        @Test
        @DisplayName("disabling an account ends its sessions on the next request")
        void disabledAccount() throws Exception {
            String email = email("disabled");
            Session session = register(email);
            User user = userFor(email);
            user.setStatus(UserStatus.DISABLED);
            userRepository.saveAndFlush(user);

            assertThat(me(session.access())).isEqualTo(401);
            assertThat(refresh(session.refresh())).isEqualTo(403);
        }
    }

    // --------------------------------------------------------- password reset

    @Nested
    @DisplayName("a password reset")
    class PasswordReset {

        @Test
        @DisplayName("stores a digest of the token, never the token")
        void storedAsADigest() throws Exception {
            String email = email("digest");
            register(email);

            String raw = authService.beginPasswordReset(email).orElseThrow();
            String stored = userFor(email).getPasswordResetToken();

            assertThat(stored).isNotEqualTo(raw).isEqualTo(sha256Hex(raw)).hasSize(64);
        }

        @Test
        @DisplayName("the stored digest is not itself a working reset token")
        void digestIsNotAToken() throws Exception {
            String email = email("digest-token");
            register(email);
            authService.beginPasswordReset(email);
            String stored = userFor(email).getPasswordResetToken();

            int status = mockMvc.perform(post("/api/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"" + stored + "\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(400);
            assertThat(login(email, PASSWORD).access()).isNotBlank();
        }

        @Test
        @DisplayName("ends every session that existed before it, and the link works once")
        void resetRevokesEverything() throws Exception {
            String email = email("reset");
            Session before = register(email);
            Session otherDevice = login(email, PASSWORD);
            String raw = authService.beginPasswordReset(email).orElseThrow();

            String resetBody = "{\"token\":\"" + raw + "\",\"password\":\"" + NEW_PASSWORD + "\"}";
            assertThat(mockMvc.perform(post("/api/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON).content(resetBody))
                    .andReturn().getResponse().getStatus()).isEqualTo(204);

            assertThat(me(before.access())).isEqualTo(401);
            assertThat(me(otherDevice.access())).isEqualTo(401);
            assertThat(refresh(before.refresh())).isEqualTo(403);
            assertThat(refresh(otherDevice.refresh())).isEqualTo(403);

            assertThat(loginResult(email, PASSWORD).getResponse().getStatus()).isEqualTo(403);
            assertThat(me(login(email, NEW_PASSWORD).access())).isEqualTo(200);

            assertThat(mockMvc.perform(post("/api/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON).content(resetBody))
                    .andReturn().getResponse().getStatus())
                    .describedAs("a used link is spent")
                    .isEqualTo(400);
        }
    }

    // -------------------------------------------------------- password change

    @Nested
    @DisplayName("a password change")
    class PasswordChange {

        @Test
        @DisplayName("ends every session, this one included")
        void changeRevokesEverything() throws Exception {
            String email = email("change");
            Session here = register(email);
            Session elsewhere = login(email, PASSWORD);

            assertThat(mockMvc.perform(post("/api/auth/change-password")
                            .header("Authorization", "Bearer " + here.access())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\""
                                    + NEW_PASSWORD + "\"}"))
                    .andReturn().getResponse().getStatus()).isEqualTo(204);

            assertThat(me(here.access())).isEqualTo(401);
            assertThat(me(elsewhere.access())).isEqualTo(401);
            assertThat(refresh(here.refresh())).isEqualTo(403);
            assertThat(refresh(elsewhere.refresh())).isEqualTo(403);
            assertThat(me(login(email, NEW_PASSWORD).access())).isEqualTo(200);
        }

        @Test
        @DisplayName("a wrong current password changes nothing and ends nothing")
        void wrongCurrentPasswordRevokesNothing() throws Exception {
            String email = email("change-wrong");
            Session session = register(email);

            assertThat(mockMvc.perform(post("/api/auth/change-password")
                            .header("Authorization", "Bearer " + session.access())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"NotMyPassword1!\",\"newPassword\":\""
                                    + NEW_PASSWORD + "\"}"))
                    .andReturn().getResponse().getStatus()).isEqualTo(400);

            assertThat(me(session.access())).isEqualTo(200);
            assertThat(userFor(email).getSessionsValidAfter()).isNull();
        }
    }

    // -------------------------------------------------------- suspended college

    @Nested
    @DisplayName("a suspended college")
    class SuspendedCollege {

        private User studentOf(Institution college, String email) {
            User user = new User();
            user.setEmail(email);
            user.setFullName("Suspended Student");
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            user.setRole(UserRole.STUDENT);
            user.setStatus(UserStatus.ACTIVE);
            user.setInstitution(college);
            return userRepository.saveAndFlush(user);
        }

        private Institution college(InstitutionStatus status) {
            String tag = UUID.randomUUID().toString().substring(0, 8);
            Institution college = new Institution();
            college.setName("Suspension College " + tag);
            college.setSlug("suspension-" + tag);
            college.setShortName("Suspension");
            college.setStatus(status);
            college.setEmailDomains("suspension-" + tag + ".edu");
            return institutionRepository.saveAndFlush(college);
        }

        @Test
        @DisplayName("ends existing sessions, refuses refresh, and refuses sign-in with a reason")
        void suspensionEndsAccess() throws Exception {
            Institution college = college(InstitutionStatus.ACTIVE);
            String email = "member-" + UUID.randomUUID().toString().substring(0, 8) + "@" + college.getEmailDomains();
            studentOf(college, email);
            Session session = login(email, PASSWORD);
            assertThat(me(session.access())).isEqualTo(200);

            college.setStatus(InstitutionStatus.SUSPENDED);
            institutionRepository.saveAndFlush(college);

            assertThat(me(session.access())).isEqualTo(401);
            assertThat(refresh(session.refresh())).isEqualTo(403);
            MvcResult refused = loginResult(email, PASSWORD);
            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(message(refused)).contains("suspended");

            college.setStatus(InstitutionStatus.ACTIVE);
            institutionRepository.saveAndFlush(college);
            assertThat(me(login(email, PASSWORD).access()))
                    .describedAs("reinstating the college restores access")
                    .isEqualTo(200);
        }
    }
}
