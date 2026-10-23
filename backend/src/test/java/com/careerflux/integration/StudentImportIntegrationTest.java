package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;

import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.institution.service.StudentImportService;
import com.careerflux.support.TestInstitutions;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Adding students from an Excel sheet: who may, what is created, and what is refused. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StudentImportIntegrationTest {

    private static final String PASSWORD = "IntegrationTest123!";
    private static final String[] HEADINGS = {"Name", "College Mail", "Branch", "Academic Year", "Section"};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository users;

    @Autowired
    private CandidateProfileRepository profiles;

    @Autowired
    private StaffScopeRepository staffScopes;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    @Test
    @DisplayName("a department coordinator adds students to their own department")
    void createsAccounts() throws Exception {
        String token = signIn(coordinator("imp-coord1@example.com"));
        byte[] sheet = sheet(new String[][] {
                {"Asha Verma", "imp-asha@example.com", "CSE", "2026", "A"},
                {"Ravi Rao", "imp-ravi@example.com", "", "2026", "A"}});

        JsonNode result = upload(token, sheet, "students.xlsx", 200);

        assertThat(result.get("created").asInt()).isEqualTo(2);
        assertThat(result.get("skipped").asInt()).isZero();
        User asha = users.findByEmailIgnoreCase("imp-asha@example.com").orElseThrow();
        assertThat(asha.getRole()).isEqualTo(UserRole.STUDENT);
        assertThat(asha.getInstitution().getId()).isEqualTo(institutions.example().getId());
        assertThat(asha.getDepartment().getCode()).isEqualTo("CSE");
        assertThat(asha.getBatch().getGraduationYear()).isEqualTo(2026);
        assertThat(asha.getSection()).isEqualTo("A");
        assertThat(profiles.findByUserId(asha.getId())).isPresent();
        // Nobody chose a password: there is only a pending set-your-password link.
        assertThat(asha.getPasswordResetToken()).isNotBlank();
        assertThat(asha.getPasswordResetExpiresAt()).isAfter(java.time.Instant.now().plusSeconds(6 * 24 * 3600));
        // The department was left blank for Ravi; a coordinator with one department needs no repetition.
        assertThat(users.findByEmailIgnoreCase("imp-ravi@example.com").orElseThrow().getDepartment().getCode())
                .isEqualTo("CSE");
    }

    @Test
    @DisplayName("bad rows are reported and skipped without stopping the good ones")
    void skipsBadRows() throws Exception {
        String token = signIn(coordinator("imp-coord2@example.com"));
        users.saveAndFlush(existing("imp-taken@example.com"));
        byte[] sheet = sheet(new String[][] {
                {"Good Student", "imp-good@example.com", "CSE", "2026", ""},
                {"Not Theirs", "imp-mech@example.com", "MECH", "2026", ""},
                {"Taken", "imp-taken@example.com", "CSE", "2026", ""},
                {"Bad Email", "not-an-email", "CSE", "2026", ""},
                {"Twice", "imp-good@example.com", "CSE", "2026", ""},
                {"No Batch", "imp-nobatch@example.com", "CSE", "1999", ""},
                {"", "imp-noname@example.com", "CSE", "2026", ""}});

        JsonNode result = upload(token, sheet, "students.xlsx", 200);

        assertThat(result.get("created").asInt()).isEqualTo(1);
        assertThat(result.get("skipped").asInt()).isEqualTo(6);
        assertThat(users.existsByEmailIgnoreCase("imp-good@example.com")).isTrue();
        for (String refused : new String[] {"imp-mech@example.com", "imp-nobatch@example.com",
                "imp-noname@example.com"}) {
            assertThat(users.existsByEmailIgnoreCase(refused)).describedAs(refused).isFalse();
        }
    }

    @Test
    @DisplayName("a coordinator cannot add to a department outside their scope")
    void cannotCrossDepartment() throws Exception {
        String token = signIn(coordinator("imp-coord3@example.com"));

        JsonNode result = upload(token, sheet(new String[][] {
                {"Mech Student", "imp-mech2@example.com", "MECH", "2026", ""}}), "students.xlsx", 200);

        assertThat(result.get("created").asInt()).isZero();
        assertThat(result.get("rows").get(0).get("message").asText()).contains("No branch called that");
        assertThat(users.existsByEmailIgnoreCase("imp-mech2@example.com")).isFalse();
    }

    @Test
    @DisplayName("the placement coordinator can add to any department, and must name it")
    void placementCoordinatorAnyDepartment() throws Exception {
        String token = signIn(staff("imp-officer@example.com", UserRole.PLACEMENT_COORDINATOR));

        JsonNode result = upload(token, sheet(new String[][] {
                {"Mech Student", "imp-mech3@example.com", "MECH", "2026", ""},
                {"No Dept", "imp-nodept@example.com", "", "2026", ""}}), "students.xlsx", 200);

        assertThat(result.get("created").asInt()).isEqualTo(1);
        assertThat(users.findByEmailIgnoreCase("imp-mech3@example.com").orElseThrow().getDepartment().getCode())
                .isEqualTo("MECH");
        assertThat(users.existsByEmailIgnoreCase("imp-nodept@example.com")).isFalse();
    }

    @Test
    @DisplayName("a student cannot import or download the template")
    void studentsAreRefused() throws Exception {
        String token = signIn(existingStudent("imp-student@example.com"));

        upload(token, sheet(new String[][] {{"X", "imp-x@example.com", "CSE", "2026", ""}}), "students.xlsx", 403);
        mockMvc.perform(get("/api/institution/students/import/template")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        assertThat(users.existsByEmailIgnoreCase("imp-x@example.com")).isFalse();
    }

    @Test
    @DisplayName("anything that is not an .xlsx workbook is refused")
    void rejectsOtherFiles() throws Exception {
        String token = signIn(coordinator("imp-coord4@example.com"));

        upload(token, "Full Name,Email\nA,a@example.com".getBytes(), "students.csv", 400);
        upload(token, "not a zip".getBytes(), "students.xlsx", 400);
        upload(token, sheet(new String[][] {}), "students.xlsx", 200);
    }

    @Test
    @DisplayName("the template downloads for a coordinator")
    void template() throws Exception {
        String token = signIn(coordinator("imp-coord5@example.com"));
        mockMvc.perform(get("/api/institution/students/import/template")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }


    @Test
    @DisplayName("a branch may be written as its name, and a non-college address is refused")
    void branchNameAndCollegeDomain() throws Exception {
        String token = signIn(coordinator("imp-coord6@example.com"));

        JsonNode result = upload(token, sheet(new String[][] {
                {"By Name", "imp-byname@example.com", "computer science", "2026", "B"},
                {"Personal", "imp-personal@gmail.com", "CSE", "2026", "B"}}), "students.xlsx", 200);

        assertThat(result.get("created").asInt()).isEqualTo(1);
        assertThat(users.findByEmailIgnoreCase("imp-byname@example.com").orElseThrow().getDepartment().getCode())
                .isEqualTo("CSE");
        assertThat(users.existsByEmailIgnoreCase("imp-personal@gmail.com")).isFalse();
        assertThat(result.get("rows").get(1).get("message").asText()).contains("college address");
    }

    @Test
    @DisplayName("Academic Year is the graduation year or the batch; a year of study is refused")
    void academicYearForms() {
        for (String graduating : new String[] {"2026", "2026.0", "2022-2026", "2022-26", "2024 / 2026"}) {
            assertThat(StudentImportService.graduationYearOf(graduating)).describedAs(graduating).isEqualTo(2026);
        }
        // Programmes differ in length, so "3rd year" cannot be turned into a batch.
        for (String refused : new String[] {"", "fifth", "1", "4", "3rd year", "III", "2026-2020"}) {
            assertThat(StudentImportService.graduationYearOf(refused)).describedAs(refused).isEqualTo(-1);
        }
    }

    // --------------------------------------------------------------- helpers

    private JsonNode upload(String token, byte[] content, String filename, int expected) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "application/octet-stream", content);
        String body = mockMvc.perform(multipart("/api/institution/students/import").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private static byte[] sheet(String[][] rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Students");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADINGS.length; i++) {
                header.createCell(i).setCellValue(HEADINGS[i]);
            }
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private User coordinator(String email) {
        Department cse = institutions.exampleCse();
        User user = staff(email, UserRole.DEPARTMENT_COORDINATOR);
        user.setDepartment(cse);
        staffScopes.save(StaffScope.forDepartment(user, institutions.example(), cse));
        return users.saveAndFlush(user);
    }

    private User staff(String email, UserRole role) {
        User user = existing(email);
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private User existingStudent(String email) {
        return users.saveAndFlush(existing(email));
    }

    private User existing(String email) {
        User user = new User();
        user.setEmail(email);
        user.setFullName("Existing " + email);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(UserRole.STUDENT);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institutions.example());
        return user;
    }

    private String signIn(User user) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
