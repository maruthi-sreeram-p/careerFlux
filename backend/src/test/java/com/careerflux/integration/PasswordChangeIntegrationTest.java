package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.careerflux.institution.domain.Institution;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
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
 * Changing your own password, and nobody else's.
 *
 * <p>The endpoint existed and was correct; nothing tested it. That is the worst
 * combination for a security control, because it stays correct only until
 * somebody edits it. These tests pin the four properties that matter: the
 * account is taken from the token rather than the request, the old password has
 * to be produced, the stored value is a hash, and once changed the old password
 * stops working.
 *
 * <p>Every role goes through the same route, so the same test runs for a
 * student, a college administrator and a placement officer. A password path
 * that works for one kind of account and not another is a bug waiting for the
 * least technical user to find.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PasswordChangeIntegrationTest {

    private static final String ORIGINAL = "OriginalPass123!";
    private static final String REPLACEMENT = "ReplacementPass456!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    private User account(UserRole role, Institution institution) {
        User user = new User();
        user.setEmail(role.name().toLowerCase() + "-" + System.nanoTime() + "@password.test");
        user.setFullName("Password Tester");
        user.setPasswordHash(passwordEncoder.encode(ORIGINAL));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institution);
        return userRepository.saveAndFlush(user);
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private int loginStatus(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andReturn().getResponse().getStatus();
    }

    private int changePassword(String token, String current, String replacement) throws Exception {
        return mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                                .formatted(current, replacement)))
                .andReturn().getResponse().getStatus();
    }

    @Nested
    @DisplayName("every role changes its own password the same way")
    class HappyPath {

        private void changesOwnPassword(UserRole role) throws Exception {
            User user = account(role, institutions.example());
            String token = login(user.getEmail(), ORIGINAL);

            assertThat(changePassword(token, ORIGINAL, REPLACEMENT)).isEqualTo(204);

            assertThat(loginStatus(user.getEmail(), REPLACEMENT))
                    .describedAs("%s should be able to sign in with the new password", role)
                    .isEqualTo(200);
            assertThat(loginStatus(user.getEmail(), ORIGINAL))
                    .describedAs("and the old one must stop working immediately")
                    .isNotEqualTo(200);
        }

        @Test
        @DisplayName("a student")
        void student() throws Exception {
            changesOwnPassword(UserRole.STUDENT);
        }

        @Test
        @DisplayName("a college administrator")
        void collegeAdmin() throws Exception {
            changesOwnPassword(UserRole.COLLEGE_ADMIN);
        }

        @Test
        @DisplayName("a placement officer")
        void placementOfficer() throws Exception {
            changesOwnPassword(UserRole.PLACEMENT_OFFICER);
        }

        @Test
        @DisplayName("a placement coordinator")
        void placementCoordinator() throws Exception {
            changesOwnPassword(UserRole.PLACEMENT_COORDINATOR);
        }
    }

    @Nested
    @DisplayName("what the endpoint must refuse")
    class Refusals {

        @Test
        @DisplayName("an anonymous caller cannot change anybody's password")
        void anonymousIsRefused() throws Exception {
            mockMvc.perform(post("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                                    .formatted(ORIGINAL, REPLACEMENT)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("the wrong current password is refused, and changes nothing")
        void wrongCurrentPasswordIsRefused() throws Exception {
            User user = account(UserRole.STUDENT, institutions.example());
            String token = login(user.getEmail(), ORIGINAL);

            assertThat(changePassword(token, "NotMyPassword99!", REPLACEMENT)).isEqualTo(400);

            assertThat(loginStatus(user.getEmail(), ORIGINAL))
                    .describedAs("a refused attempt must leave the account exactly as it was")
                    .isEqualTo(200);
            assertThat(loginStatus(user.getEmail(), REPLACEMENT)).isNotEqualTo(200);
        }

        @Test
        @DisplayName("a new password below the policy length is refused")
        void shortPasswordIsRefused() throws Exception {
            User user = account(UserRole.STUDENT, institutions.example());
            String token = login(user.getEmail(), ORIGINAL);

            assertThat(changePassword(token, ORIGINAL, "short")).isEqualTo(400);
            assertThat(loginStatus(user.getEmail(), ORIGINAL)).isEqualTo(200);
        }

        @Test
        @DisplayName("naming another user in the request body changes nothing about whose password moves")
        void theBodyCannotChooseTheAccount() throws Exception {
            // The account comes from the token. There is no userId field to send,
            // so the closest an attacker gets is adding one and hoping it binds —
            // this proves it does not, and that the victim is untouched.
            User attacker = account(UserRole.STUDENT, institutions.example());
            User victim = account(UserRole.STUDENT, institutions.example());
            String token = login(attacker.getEmail(), ORIGINAL);

            int status = mockMvc.perform(post("/api/auth/change-password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"userId":"%s","email":"%s","currentPassword":"%s","newPassword":"%s"}
                                    """.formatted(victim.getId(), victim.getEmail(), ORIGINAL, REPLACEMENT)))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(204);
            assertThat(loginStatus(victim.getEmail(), ORIGINAL))
                    .describedAs("the victim's password must be untouched")
                    .isEqualTo(200);
            assertThat(loginStatus(victim.getEmail(), REPLACEMENT))
                    .describedAs("and must certainly not be the attacker's new one")
                    .isNotEqualTo(200);
            assertThat(loginStatus(attacker.getEmail(), REPLACEMENT))
                    .describedAs("only the caller's own password changed")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a token from another institution still only reaches its own account")
        void crossInstitutionIsStillJustYourself() throws Exception {
            User ours = account(UserRole.COLLEGE_ADMIN, institutions.example());
            User theirs = account(UserRole.COLLEGE_ADMIN, institutions.rival());
            String token = login(ours.getEmail(), ORIGINAL);

            assertThat(changePassword(token, ORIGINAL, REPLACEMENT)).isEqualTo(204);

            assertThat(loginStatus(theirs.getEmail(), ORIGINAL))
                    .describedAs("the other college's administrator is unaffected")
                    .isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("what is stored")
    class Storage {

        @Test
        @DisplayName("the new password is stored as a hash, never as itself")
        void passwordIsStoredHashed() throws Exception {
            User user = account(UserRole.STUDENT, institutions.example());
            String token = login(user.getEmail(), ORIGINAL);
            changePassword(token, ORIGINAL, REPLACEMENT);

            String stored = userRepository.findById(user.getId()).orElseThrow().getPasswordHash();

            assertThat(stored).doesNotContain(REPLACEMENT).doesNotContain(ORIGINAL);
            assertThat(stored).startsWith("$2");
            assertThat(passwordEncoder.matches(REPLACEMENT, stored)).isTrue();
            assertThat(passwordEncoder.matches(ORIGINAL, stored)).isFalse();
        }

        @Test
        @DisplayName("the response body carries nothing at all")
        void responseCarriesNothing() throws Exception {
            User user = account(UserRole.STUDENT, institutions.example());
            String token = login(user.getEmail(), ORIGINAL);

            String body = mockMvc.perform(post("/api/auth/change-password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                                    .formatted(ORIGINAL, REPLACEMENT)))
                    .andExpect(status().isNoContent())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).isEmpty();
        }
    }

    @Nested
    @DisplayName("a provisioned account behaves like any other")
    class ProvisionedAccounts {

        @Test
        @DisplayName("an account created by staff can sign in and then change its own password")
        void aProvisionedMemberCanRotateTheirInitialPassword() throws Exception {
            // The whole point of an administrator setting an initial password:
            // the person it belongs to must be able to replace it themselves,
            // without the administrator ever learning the new one.
            User member = account(UserRole.PLACEMENT_OFFICER, institutions.example());

            assertThat(loginStatus(member.getEmail(), ORIGINAL)).isEqualTo(200);
            String token = login(member.getEmail(), ORIGINAL);
            assertThat(changePassword(token, ORIGINAL, REPLACEMENT)).isEqualTo(204);

            assertThat(loginStatus(member.getEmail(), REPLACEMENT)).isEqualTo(200);
            assertThat(loginStatus(member.getEmail(), ORIGINAL)).isNotEqualTo(200);
        }

        @Test
        @DisplayName("a student cannot reach staff provisioning, whatever their password is")
        void aStudentCannotProvisionStaff() throws Exception {
            User student = account(UserRole.STUDENT, institutions.example());
            String token = login(student.getEmail(), ORIGINAL);

            mockMvc.perform(post("/api/institution/staff")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"fullName":"Sneaky Officer","email":"sneaky@example.com",
                                     "password":"WhateverPass123!","role":"PLACEMENT_OFFICER"}
                                    """))
                    .andExpect(status().isForbidden());

            assertThat(userRepository.findByEmailIgnoreCase("sneaky@example.com")).isEmpty();
        }

        @Test
        @DisplayName("an email already in use is refused rather than silently reassigned")
        void duplicateEmailIsRefused() throws Exception {
            User admin = account(UserRole.COLLEGE_ADMIN, institutions.example());
            User existing = account(UserRole.PLACEMENT_OFFICER, institutions.example());
            String token = login(admin.getEmail(), ORIGINAL);

            JsonNode error = objectMapper.readTree(mockMvc.perform(post("/api/institution/staff")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"fullName":"Duplicate Person","email":"%s",
                                     "password":"AnotherPass123!","role":"PLACEMENT_OFFICER"}
                                    """.formatted(existing.getEmail())))
                    .andExpect(status().isConflict())
                    .andReturn().getResponse().getContentAsString());

            assertThat(error.get("message").asText()).contains("already exists");
            // The original account keeps its own password.
            assertThat(loginStatus(existing.getEmail(), ORIGINAL)).isEqualTo(200);
        }
    }
}
