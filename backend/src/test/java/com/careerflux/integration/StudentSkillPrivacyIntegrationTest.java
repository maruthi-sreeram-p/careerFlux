package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import com.careerflux.ai.dto.ExtractedResume;
import com.careerflux.ai.proposal.ProfileProposalBuilder;
import com.careerflux.ai.proposal.dto.ProposalDtos.ProposedItem;
import com.careerflux.candidate.domain.CandidateCustomSkill;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.common.TextUtils;
import com.careerflux.skill.Skill;
import com.careerflux.skill.SkillRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * A student's own words stay the student's (Phase 2A, F4).
 *
 * <p>The skill dictionary is shared by every college: it tags job postings and
 * company requirements, and job enrichment matches every posting against all of
 * it. So a skill a student names that the dictionary does not know is kept on
 * that student's profile alone — never added to the dictionary, never shown to
 * staff, never matched — whether they typed it or accepted it from a reading of
 * their resume.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StudentSkillPrivacyIntegrationTest {

    private static final String PASSWORD = "IntegrationTest123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandidateProfileRepository profileRepository;

    @Autowired
    private SkillRepository skillRepository;

    @Autowired
    private CandidateProfileService profileService;

    @Autowired
    private ProfileProposalBuilder proposalBuilder;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    private String unique;
    private UUID studentId;
    private String studentToken;

    @BeforeEach
    void setUp() throws Exception {
        unique = Long.toString(System.nanoTime());
        String email = "skills-" + unique + "@example.com";
        studentToken = json(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\",\"fullName\":\"Skill Student\"}"
                        .formatted(email, PASSWORD)), null).get("accessToken").asText();
        User student = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        student.setDepartment(institutions.exampleCse());
        userRepository.saveAndFlush(student);
        studentId = student.getId();
    }

    /** A skill no dictionary knows, unique to this test run. */
    private String privateSkill() {
        return "Zorblang Toolkit " + unique.substring(unique.length() - 6);
    }

    @Test
    @DisplayName("a skill the dictionary does not know is not added to it")
    void unknownSkillIsNotAddedToTheDictionary() throws Exception {
        long before = skillRepository.count();

        saveSkills("Java", privateSkill());

        assertThat(skillRepository.count()).isEqualTo(before);
        assertThat(skillRepository.findBySlug(TextUtils.skillSlug(privateSkill()))).isEmpty();
        assertThat(skillRepository.findAll()).extracting(Skill::getCanonicalName)
                .noneMatch(name -> name.equalsIgnoreCase(privateSkill()));
    }

    @Test
    @DisplayName("the student keeps it: it comes back on their own profile, and survives a resave")
    void studentKeepsTheirPrivateSkill() throws Exception {
        saveSkills("Java", privateSkill());

        JsonNode skills = json(get("/api/candidate/profile"), studentToken).get("skills");
        JsonNode own = byName(skills, privateSkill());
        assertThat(own).isNotNull();
        assertThat(own.get("slug").isNull()).describedAs("not a dictionary skill").isTrue();
        assertThat(byName(skills, "Java").get("slug").asText()).isEqualTo("java");

        // Sending the list back unchanged — what the profile screen does — keeps both.
        List<String> names = new ArrayList<>(skills.findValuesAsText("name"));
        saveSkills(names.toArray(String[]::new));
        JsonNode again = json(get("/api/candidate/profile"), studentToken).get("skills");
        assertThat(again.findValuesAsText("name")).contains("Java", privateSkill());
    }

    @Test
    @DisplayName("staff see the dictionary skills and never the private one")
    void staffDoNotSeeThePrivateSkill() throws Exception {
        saveSkills("Java", privateSkill());
        String coordinator = login(staff("skills-pc-" + unique + "@example.com"));

        JsonNode detail = json(get("/api/institution/students/" + studentId), coordinator);

        assertThat(detail.get("skills").findValuesAsText("name")).contains("Java")
                .doesNotContain(privateSkill());
        assertThat(detail.toString().toLowerCase(Locale.ROOT))
                .doesNotContain(privateSkill().toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("the same private skill typed three ways is kept once")
    void privateSkillsAreKeptOnce() throws Exception {
        saveSkills(privateSkill(), privateSkill().toUpperCase(Locale.ROOT),
                privateSkill().toLowerCase(Locale.ROOT));

        JsonNode skills = json(get("/api/candidate/profile"), studentToken).get("skills");
        assertThat(skills.findValuesAsText("name").stream()
                .filter(name -> name.equalsIgnoreCase(privateSkill())).collect(Collectors.toList()))
                .hasSize(1);
    }

    @Test
    @DisplayName("reading a resume does not add its unknown skills to the dictionary, and accepting one keeps it privately")
    void resumeSuggestionsDoNotMintSkills() {
        long before = skillRepository.count();
        CandidateProfile profile = profileRepository.findByUserId(studentId).orElseThrow();
        ExtractedResume extracted = new ExtractedResume(null, null, null, null, null, null, null,
                null, null, null, null, null, List.of("Java", privateSkill()), List.of(), List.of());

        List<ProposedItem> items = proposalBuilder.build(profile, extracted);

        assertThat(skillRepository.count())
                .describedAs("building the suggestions created nothing")
                .isEqualTo(before);
        ProposedItem suggestion = items.stream()
                .filter(item -> item.key().startsWith("skill:private:"))
                .findFirst().orElseThrow();
        assertThat(suggestion.data()).containsEntry("name", privateSkill()).doesNotContainKey("slug");

        profileService.applyAcceptedProposal(profile, List.of(suggestion), true);

        assertThat(skillRepository.count())
                .describedAs("accepting it created nothing either")
                .isEqualTo(before);
        assertThat(profile.getCustomSkills()).extracting(CandidateCustomSkill::getName)
                .contains(privateSkill());
    }

    // --------------------------------------------------------------- helpers

    private JsonNode saveSkills(String... names) throws Exception {
        String skills = java.util.Arrays.stream(names)
                .map(name -> "{\"name\":\"%s\",\"origin\":\"MANUAL\"}".formatted(name))
                .collect(Collectors.joining(","));
        return json(put("/api/candidate/profile").contentType(MediaType.APPLICATION_JSON)
                .content("{\"skills\":[" + skills + "]}"), studentToken);
    }

    private static JsonNode byName(JsonNode skills, String name) {
        for (JsonNode skill : skills) {
            if (name.equals(skill.get("name").asText())) {
                return skill;
            }
        }
        return null;
    }

    private User staff(String email) {
        User user = new User();
        user.setEmail(email);
        user.setFullName("Staff Member");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(UserRole.PLACEMENT_COORDINATOR);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institutions.example());
        return userRepository.saveAndFlush(user);
    }

    private String login(User user) throws Exception {
        return json(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(user.getEmail(), PASSWORD)), null)
                .get("accessToken").asText();
    }

    private JsonNode json(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return objectMapper.readTree(mockMvc.perform(request)
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString());
    }
}
