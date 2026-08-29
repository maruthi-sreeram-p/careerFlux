package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
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
 * Authentication and authorization, exercised through the real filter chain.
 *
 * <p>These are the tests that would catch the failures nobody notices until it
 * matters: an endpoint left open, a candidate reading another candidate's data,
 * a refresh token accepted where an access token was required.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthAndAuthorizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private JsonNode register(String email) throws Exception {
        String body = """
                {"email":"%s","password":"IntegrationTest123!","fullName":"Test Person"}
                """.formatted(email);
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    @DisplayName("registering creates a candidate profile and returns a usable token")
    void registerCreatesProfile() throws Exception {
        JsonNode session = register("register-flow@example.com");

        assertThat(session.get("accessToken").asText()).isNotBlank();
        assertThat(session.get("user").get("role").asText()).isEqualTo("STUDENT");
        assertThat(session.get("user").get("candidateId").asText()).isNotBlank();
        assertThat(session.get("user").get("onboardingStage").asText()).isEqualTo("RESUME_UPLOAD");

        mockMvc.perform(get("/api/candidate/profile")
                        .header("Authorization", "Bearer " + session.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("register-flow@example.com"));
    }

    @Test
    @DisplayName("the same email cannot be registered twice")
    void duplicateEmailConflicts() throws Exception {
        register("duplicate@example.com");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"DUPLICATE@example.com","password":"IntegrationTest123!","fullName":"Other"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("a short password is rejected with field-level detail")
    void weakPasswordRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"weak@example.com","password":"short","fullName":"Weak"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("password"));
    }

    @Test
    @DisplayName("a wrong password and an unknown account produce the same answer")
    void loginDoesNotRevealWhichAccountsExist() throws Exception {
        register("real@example.com");

        String wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"real@example.com","password":"WrongPassword123"}
                                """))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        String unknownAccount = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@example.com","password":"WrongPassword123"}
                                """))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(wrongPassword).get("message").asText())
                .isEqualTo(objectMapper.readTree(unknownAccount).get("message").asText());
    }

    @Test
    @DisplayName("protected endpoints reject a missing, malformed or unsigned token")
    void protectedEndpointsRequireAValidToken() throws Exception {
        mockMvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/dashboard").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/dashboard")
                        .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.forged"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an access token is not accepted where a refresh token is required")
    void tokenTypesAreNotInterchangeable() throws Exception {
        JsonNode session = register("token-types@example.com");

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("refreshToken", session.get("accessToken").asText()))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("refreshToken", session.get("refreshToken").asText()))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a candidate cannot reach the admin routes")
    void candidatesAreKeptOutOfAdmin() throws Exception {
        JsonNode session = register("not-an-admin@example.com");
        String token = session.get("accessToken").asText();

        mockMvc.perform(get("/api/admin/ops/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/sources").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sneaky","baseUrl":"https://example.invalid"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an administrator can reach the admin routes")
    void adminsCanReachAdmin() throws Exception {
        User admin = new User();
        admin.setEmail("admin-test@example.com");
        admin.setFullName("Admin");
        admin.setPasswordHash(passwordEncoder.encode("AdminPassword123!"));
        admin.setRole(UserRole.PLATFORM_ADMIN);
        userRepository.save(admin);

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin-test@example.com","password":"AdminPassword123!"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(response).get("accessToken").asText();

        mockMvc.perform(get("/api/admin/ops/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").isNumber());
    }

    @Test
    @DisplayName("a disabled account stops working immediately, without waiting for token expiry")
    void disablingAnAccountTakesEffectAtOnce() throws Exception {
        JsonNode session = register("disabled@example.com");
        String token = session.get("accessToken").asText();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        User user = userRepository.findByEmailIgnoreCase("disabled@example.com").orElseThrow();
        user.setStatus(com.careerflux.user.UserStatus.DISABLED);
        userRepository.saveAndFlush(user);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("one candidate cannot read another candidate's resume")
    void candidateDataIsIsolated() throws Exception {
        JsonNode owner = register("owner@example.com");
        JsonNode stranger = register("stranger@example.com");

        String upload = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/candidate/resume")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "resume.txt", "text/plain",
                                "Owner Person\nSkills: Java, Spring Boot".getBytes()))
                        .header("Authorization", "Bearer " + owner.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String resumeId = objectMapper.readTree(upload).get("resume").get("id").asText();

        // The owner can download it.
        mockMvc.perform(get("/api/candidate/resumes/" + resumeId + "/file")
                        .header("Authorization", "Bearer " + owner.get("accessToken").asText()))
                .andExpect(status().isOk());

        // Anyone else gets a 404, not a 403, so the id is not confirmed to exist.
        mockMvc.perform(get("/api/candidate/resumes/" + resumeId + "/file")
                        .header("Authorization", "Bearer " + stranger.get("accessToken").asText()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("public endpoints stay public")
    void publicEndpointsAreReachable() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@example.com"}
                                """))
                .andExpect(status().isAccepted());
    }
}
