package com.careerflux.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.CandidateSkill;
import com.careerflux.candidate.domain.Resume;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.candidate.repository.ResumeRepository;
import com.careerflux.engagement.domain.InteractionType;
import com.careerflux.engagement.domain.JobInteraction;
import com.careerflux.engagement.repository.JobInteractionRepository;
import com.careerflux.job.domain.Job;
import com.careerflux.job.domain.JobStatus;
import com.careerflux.job.repository.JobRepository;
import com.careerflux.source.domain.Company;
import com.careerflux.source.repository.CompanyRepository;
import com.careerflux.institution.domain.Batch;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.domain.InstitutionStatus;
import com.careerflux.institution.repository.BatchRepository;
import com.careerflux.institution.repository.DepartmentRepository;
import com.careerflux.institution.repository.InstitutionRepository;
import com.careerflux.institution.repository.StaffScopeRepository;
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
import org.junit.jupiter.api.Nested;
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
 * Finding one student, or one cohort, without paging through the college.
 *
 * <p>The directory was an alphabetical list with nothing but Previous and Next.
 * That is usable at fourteen students and useless at four hundred, which is the
 * size a real placement office works at. These tests describe the search and
 * filters that replace the paging, and — more importantly — the boundaries they
 * must not cross while doing it.
 *
 * <p>Driven through MockMvc against the real filter chain and real logins,
 * following the pattern the authorization tests already established. A mocked
 * security context would prove the test author understood the scoping rules
 * rather than that the application enforces them, and scoping is the thing most
 * likely to break when a WHERE clause grows.
 *
 * <p>The fixture is deliberately small and fully enumerated, so every assertion
 * can name the students it expects rather than counting rows. Two of them exist
 * to be excluded: Chandra has no CGPA, and a second Anita at a different college
 * shares the first one's roll number.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StudentDiscoveryIntegrationTest {

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
    private ResumeRepository resumeRepository;

    @Autowired
    private SkillRepository skillRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private JobInteractionRepository interactionRepository;

    @Autowired
    private StaffScopeRepository staffScopeRepository;

    @Autowired
    private InstitutionRepository institutionRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestInstitutions institutions;

    private String officerToken;
    private String coordinatorToken;
    private String studentToken;

    private UUID anitaId;
    private UUID chandraId;
    private UUID rivalAnitaId;
    private UUID bhaveshId;
    private Department cse;
    private Department mech;
    private Batch batch2027;
    private Batch batch2026;
    private String unique;

    @BeforeEach
    void setUp() throws Exception {
        unique = Long.toString(System.nanoTime());

        Skill java = skill("Java", "java");
        Skill spring = skill("Spring Boot", "spring-boot");
        Skill kafka = skill("Kafka", "kafka");

        // A college of its own, created per test rather than the shared example
        // one. Several other test classes commit students into that shared
        // institution, and assertions like "exactly these four students" cannot
        // survive a neighbour adding a fifth. Scoping the officer to a private
        // tenant makes this class independent of what else has run.
        Institution example = ownInstitution();
        cse = department(example, "Computer Science", "CSE");
        mech = department(example, "Mechanical", "MECH");
        batch2027 = batch(example, "Class of 2027", 2027);
        batch2026 = batch(example, "Class of 2026", 2026);

        // Anita: CSE 2027, 8.50 on a 10 scale, nearly complete, one resume.
        anitaId = student("Anita Sharma", "anita.sharma", roll("0501"), example, cse, batch2027,
                new BigDecimal("8.50"), new BigDecimal("10.00"), 90, 1, List.of(java, spring));
        // Bhavesh: same cohort, 4.00 on a 5 scale — 8.00 once normalised — and
        // two resumes, so the resume filter has a chance to duplicate him.
        bhaveshId = student("Bhavesh Rao", "bhavesh.rao", roll("0502"), example, cse, batch2027,
                new BigDecimal("4.00"), new BigDecimal("5.00"), 60, 2, List.of(java));
        // Chandra: no CGPA at all. Exists to be excluded by every CGPA floor.
        chandraId = student("Chandra Iyer", "chandra.iyer", roll("0503"), example, mech, batch2026,
                null, new BigDecimal("10.00"), 40, 0, List.of(spring));
        student("Divya Nair", "divya.nair", roll("0504"), example, cse, batch2026,
                new BigDecimal("6.00"), new BigDecimal("10.00"), 100, 0, List.of(java, spring, kafka));

        // A different college, same first name and the same roll number.
        rivalAnitaId = student("Anita Sharma", "anita.sharma.rival", roll("0501"),
                institutions.rival(), institutions.rivalCse(), null,
                new BigDecimal("9.00"), new BigDecimal("10.00"), 95, 1, List.of(java));

        // Two dated interactions for Anita and one for Bhavesh, so the timeline
        // has something to order and something to keep separate.
        Job job = job("Backend Engineer");
        interaction(anitaId, job, InteractionType.VIEWED, Instant.now().minusSeconds(7200));
        interaction(anitaId, job, InteractionType.APPLIED, Instant.now().minusSeconds(3600));
        interaction(bhaveshId, job, InteractionType.SAVED, Instant.now().minusSeconds(1800));

        User officer = staff("discovery-officer", UserRole.PLACEMENT_COORDINATOR, example);
        officerToken = login(officer);

        User coordinator = staff("discovery-coordinator", UserRole.DEPARTMENT_COORDINATOR, example);
        staffScopeRepository.saveAndFlush(StaffScope.forDepartment(coordinator, example, cse));
        coordinatorToken = login(coordinator);

        studentToken = login(userRepository.findById(anitaId).orElseThrow());
    }

    // --------------------------------------------------------------- fixtures

    private Institution ownInstitution() {
        Institution institution = new Institution();
        institution.setName("Discovery College " + unique);
        institution.setSlug("discovery-college-" + unique);
        institution.setStatus(InstitutionStatus.ACTIVE);
        return institutionRepository.saveAndFlush(institution);
    }

    private Department department(Institution institution, String name, String code) {
        Department department = new Department();
        department.setInstitution(institution);
        department.setName(name);
        department.setCode(code);
        return departmentRepository.saveAndFlush(department);
    }

    private Batch batch(Institution institution, String name, int graduationYear) {
        Batch created = new Batch();
        created.setInstitution(institution);
        created.setName(name);
        created.setGraduationYear(graduationYear);
        return batchRepository.saveAndFlush(created);
    }

    private String roll(String suffix) {
        return "22A81A" + unique.substring(unique.length() - 4) + suffix;
    }

    private Skill skill(String name, String slug) {
        return skillRepository.findBySlug(slug).orElseGet(() -> {
            Skill created = new Skill();
            created.setCanonicalName(name);
            created.setSlug(slug);
            created.setCategory(com.careerflux.common.taxonomy.SkillCategory.LANGUAGE);
            created.setCreatedAt(Instant.now());
            return skillRepository.saveAndFlush(created);
        });
    }

    private UUID student(String name, String localPart, String rollNumber, Institution institution,
                         Department department, Batch batch, BigDecimal cgpa, BigDecimal scale,
                         int completeness, int resumeCount, List<Skill> skills) {
        User user = new User();
        user.setEmail(localPart + "-" + unique + "@discovery.test");
        user.setFullName(name);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(UserRole.STUDENT);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institution);
        user.setDepartment(department);
        user.setBatch(batch);
        user.setRollNumber(rollNumber);
        userRepository.saveAndFlush(user);

        CandidateProfile profile = new CandidateProfile();
        profile.setUser(user);
        profile.setInstitution(institution);
        profile.setCgpaScale(scale);
        profile.recordCgpa(cgpa, com.careerflux.candidate.domain.CgpaSource.INSTITUTION, null);
        profile.setProfileCompleteness(completeness);
        for (Skill skill : skills) {
            CandidateSkill candidateSkill = new CandidateSkill();
            candidateSkill.setCandidate(profile);
            candidateSkill.setSkill(skill);
            profile.getSkills().add(candidateSkill);
        }
        profileRepository.saveAndFlush(profile);

        for (int index = 0; index < resumeCount; index++) {
            Resume resume = new Resume();
            resume.setCandidate(profile);
            resume.setOriginalFilename("resume-" + index + ".pdf");
            resume.setStoragePath("test/" + user.getId() + "/" + index);
            resume.setContentType("application/pdf");
            resume.setSizeBytes(1024L);
            resumeRepository.saveAndFlush(resume);
        }
        return user.getId();
    }

    private Job job(String title) {
        Company company = new Company();
        company.setName("Discovery Co " + unique);
        company.setSlug("discovery-co-" + unique);
        companyRepository.saveAndFlush(company);

        Job created = new Job();
        created.setCompany(company);
        created.setCanonicalKey("discovery-job-" + unique);
        created.setTitle(title);
        created.setNormalizedTitle(title.toLowerCase(java.util.Locale.ROOT));
        created.setSearchText(title.toLowerCase(java.util.Locale.ROOT));
        created.setStatus(JobStatus.OPEN);
        created.setFirstObservedAt(Instant.now());
        created.setLastObservedAt(Instant.now());
        return jobRepository.saveAndFlush(created);
    }

    private void interaction(UUID studentUserId, Job job, InteractionType type, Instant when) {
        CandidateProfile profile = profileRepository.findByUserId(studentUserId).orElseThrow();
        JobInteraction record = new JobInteraction();
        record.setCandidate(profile);
        record.setJob(job);
        record.setInteractionType(type);
        record.setCreatedAt(when);
        if (type == InteractionType.APPLIED) {
            record.setAppliedAt(when);
        }
        interactionRepository.saveAndFlush(record);
    }

    private User staff(String localPart, UserRole role, Institution institution) {
        User user = new User();
        user.setEmail(localPart + "-" + unique + "@discovery.test");
        user.setFullName("Staff Member");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setInstitution(institution);
        return userRepository.saveAndFlush(user);
    }

    private String login(User user) throws Exception {
        JsonNode session = readJson(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(user.getEmail(), PASSWORD)));
        return session.get("accessToken").asText();
    }

    private JsonNode readJson(MockHttpServletRequestBuilder request) throws Exception {
        return objectMapper.readTree(mockMvc.perform(request)
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString());
    }

    // ----------------------------------------------------------------- search

    /** The names on one page of results, in the order the server returned them. */
    private List<String> names(String query) throws Exception {
        JsonNode page = readJson(get("/api/institution/students?" + query)
                .header("Authorization", "Bearer " + officerToken));
        return page.get("content").findValuesAsText("fullName");
    }

    private List<String> namesAs(String token, String query) throws Exception {
        JsonNode page = readJson(get("/api/institution/students?" + query)
                .header("Authorization", "Bearer " + token));
        return page.get("content").findValuesAsText("fullName");
    }

    private long total(String query) throws Exception {
        return readJson(get("/api/institution/students?" + query)
                .header("Authorization", "Bearer " + officerToken)).get("totalElements").asLong();
    }

    @Nested
    @DisplayName("one search box, four intentional predicates")
    class Search {

        @Test
        @DisplayName("a name matches, case-insensitively and partially")
        void nameSearch() throws Exception {
            assertThat(names("q=Anita")).containsExactly("Anita Sharma");
            assertThat(names("q=ANITA")).containsExactly("Anita Sharma");
            assertThat(names("q=anit")).containsExactly("Anita Sharma");
            assertThat(names("q=sharma")).containsExactly("Anita Sharma");
        }

        @Test
        @DisplayName("surrounding whitespace is trimmed rather than searched for")
        void searchIsTrimmed() throws Exception {
            // Passed as a parameter rather than inside the query string, so the
            // spaces reach the controller as spaces instead of as %20 literals.
            JsonNode page = readJson(get("/api/institution/students")
                    .param("q", "  anita  ")
                    .header("Authorization", "Bearer " + officerToken));

            assertThat(page.get("content").findValuesAsText("fullName"))
                    .containsExactly("Anita Sharma");
        }

        @Test
        @DisplayName("an email matches")
        void emailSearch() throws Exception {
            assertThat(names("q=bhavesh.rao")).containsExactly("Bhavesh Rao");
        }

        @Test
        @DisplayName("a roll number matches, case-insensitively and partially")
        void rollNumberSearch() throws Exception {
            assertThat(names("q=" + roll("0502"))).containsExactly("Bhavesh Rao");
            assertThat(names("q=" + roll("0502").toLowerCase())).containsExactly("Bhavesh Rao");
            assertThat(names("q=0503")).containsExactly("Chandra Iyer");
        }

        @Test
        @DisplayName("a skill matches the student's stored skills, not their name")
        void skillSearch() throws Exception {
            assertThat(names("q=Kafka")).containsExactly("Divya Nair");
            assertThat(names("q=kafka")).containsExactly("Divya Nair");
            assertThat(names("q=java")).containsExactlyInAnyOrder(
                    "Anita Sharma", "Bhavesh Rao", "Divya Nair");
        }

        @Test
        @DisplayName("a query matching nothing returns nothing, not everything")
        void unknownSearch() throws Exception {
            assertThat(names("q=zzz-no-such-student")).isEmpty();
            assertThat(total("q=zzz-no-such-student")).isZero();
        }

        @Test
        @DisplayName("an empty query is not a filter")
        void emptySearch() throws Exception {
            assertThat(names("q=")).hasSize(4);
            assertThat(names("size=25")).hasSize(4);
        }

        @Test
        @DisplayName("search never reaches outside the caller's institution")
        void searchDoesNotLeakScope() throws Exception {
            // Same name and the same roll number at another college.
            assertThat(names("q=Anita Sharma")).containsExactly("Anita Sharma");
            assertThat(total("q=Anita Sharma")).isEqualTo(1);
            assertThat(total("q=" + roll("0501"))).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- filters

    @Nested
    @DisplayName("filters")
    class Filters {

        @Test
        @DisplayName("department")
        void department() throws Exception {
            assertThat(names("departmentId=" + cse.getId()))
                    .containsExactlyInAnyOrder("Anita Sharma", "Bhavesh Rao", "Divya Nair");
            assertThat(names("departmentId=" + mech.getId()))
                    .containsExactly("Chandra Iyer");
        }

        @Test
        @DisplayName("batch")
        void batch() throws Exception {
            assertThat(names("batchId=" + batch2027.getId()))
                    .containsExactlyInAnyOrder("Anita Sharma", "Bhavesh Rao");
            assertThat(names("batchId=" + batch2026.getId()))
                    .containsExactlyInAnyOrder("Chandra Iyer", "Divya Nair");
        }

        @Test
        @DisplayName("CGPA is compared on a normalised ten-point scale")
        void cgpaIsNormalised() throws Exception {
            // Bhavesh is 4.00 out of 5, which is 8.00 — better than Divya's
            // 6.00 out of 10, and a raw comparison would rank him below her.
            assertThat(names("minCgpa=7")).containsExactlyInAnyOrder("Anita Sharma", "Bhavesh Rao");
            assertThat(names("minCgpa=7.5")).containsExactlyInAnyOrder("Anita Sharma", "Bhavesh Rao");
            assertThat(names("minCgpa=8.2")).containsExactly("Anita Sharma");
            assertThat(names("minCgpa=9")).isEmpty();
        }

        @Test
        @DisplayName("a student with no CGPA never satisfies a CGPA floor")
        void unknownCgpaIsNotIncluded() throws Exception {
            // The mandatory case. Unknown is not zero and it is not "probably
            // fine": a student with no recorded CGPA cannot be said to meet a
            // requirement, so a floor of any size leaves them out.
            assertThat(names("minCgpa=7")).doesNotContain("Chandra Iyer");
            assertThat(names("minCgpa=0.1")).doesNotContain("Chandra Iyer");
            assertThat(names("minCgpa=0")).doesNotContain("Chandra Iyer");
        }

        @Test
        @DisplayName("profile completion threshold")
        void profileCompletion() throws Exception {
            assertThat(names("minProfileCompleteness=75"))
                    .containsExactlyInAnyOrder("Anita Sharma", "Divya Nair");
            assertThat(names("minProfileCompleteness=100")).containsExactly("Divya Nair");
            assertThat(names("minProfileCompleteness=0")).hasSize(4);
        }

        @Test
        @DisplayName("the directory row carries the CGPA on both scales")
        void rowsCarryCgpa() throws Exception {
            JsonNode page = readJson(get("/api/institution/students?q=bhavesh")
                    .header("Authorization", "Bearer " + officerToken));
            JsonNode row = page.get("content").get(0);

            // 4.00 out of 5 is shown as recorded and as the 8.00 the filters use,
            // so the officer can see why a "CGPA >= 7.5" search matched them.
            assertThat(row.get("cgpa").asDouble()).isEqualTo(4.00);
            assertThat(row.get("cgpaScale").asDouble()).isEqualTo(5.00);
            assertThat(row.get("normalisedCgpa").asDouble()).isEqualTo(8.00);

            JsonNode chandra = readJson(get("/api/institution/students?q=chandra")
                    .header("Authorization", "Bearer " + officerToken))
                    .get("content").get(0);
            assertThat(chandra.get("cgpa").isNull())
                    .describedAs("a missing CGPA stays missing in the list too")
                    .isTrue();
        }

        @Test
        @DisplayName("resume uploaded, and a student with two resumes appears once")
        void resumeUploaded() throws Exception {
            assertThat(names("resumeUploaded=true"))
                    .containsExactlyInAnyOrder("Anita Sharma", "Bhavesh Rao");
            assertThat(names("resumeUploaded=true").stream().filter("Bhavesh Rao"::equals).count())
                    .describedAs("two resumes must not become two rows")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("resume not uploaded")
        void resumeMissing() throws Exception {
            assertThat(names("resumeUploaded=false"))
                    .containsExactlyInAnyOrder("Chandra Iyer", "Divya Nair");
        }

        @Test
        @DisplayName("a single skill")
        void singleSkill() throws Exception {
            assertThat(names("skills=java"))
                    .containsExactlyInAnyOrder("Anita Sharma", "Bhavesh Rao", "Divya Nair");
            assertThat(names("skills=no-such-skill")).isEmpty();
        }

        @Test
        @DisplayName("several skills mean ALL of them, and never duplicate a student")
        void multipleSkillsMeanAll() throws Exception {
            // The mandatory case: Bhavesh has Java alone and must drop out.
            assertThat(names("skills=java&skills=spring-boot"))
                    .containsExactlyInAnyOrder("Anita Sharma", "Divya Nair");
            assertThat(names("skills=java&skills=kafka")).containsExactly("Divya Nair");
            assertThat(names("skills=java&skills=spring-boot&skills=kafka"))
                    .containsExactly("Divya Nair");
            assertThat(names("skills=java&skills=no-such-skill")).isEmpty();
        }
    }

    // ----------------------------------------------------------- combinations

    @Nested
    @DisplayName("combinations")
    class Combinations {

        @Test
        @DisplayName("name and department")
        void nameAndDepartment() throws Exception {
            assertThat(names("q=anita&departmentId=" + cse.getId()))
                    .containsExactly("Anita Sharma");
            assertThat(names("q=anita&departmentId=" + mech.getId())).isEmpty();
        }

        @Test
        @DisplayName("skill and department")
        void skillAndDepartment() throws Exception {
            assertThat(names("skills=spring-boot&departmentId=" + cse.getId()))
                    .containsExactlyInAnyOrder("Anita Sharma", "Divya Nair");
        }

        @Test
        @DisplayName("skill and batch")
        void skillAndBatch() throws Exception {
            assertThat(names("skills=java&batchId=" + batch2027.getId()))
                    .containsExactlyInAnyOrder("Anita Sharma", "Bhavesh Rao");
        }

        @Test
        @DisplayName("skill and CGPA")
        void skillAndCgpa() throws Exception {
            assertThat(names("skills=java&minCgpa=7"))
                    .containsExactlyInAnyOrder("Anita Sharma", "Bhavesh Rao");
        }

        @Test
        @DisplayName("department, batch and CGPA")
        void departmentBatchAndCgpa() throws Exception {
            assertThat(names("departmentId=" + cse.getId()
                    + "&batchId=" + batch2027.getId() + "&minCgpa=8.2"))
                    .containsExactly("Anita Sharma");
        }

        @Test
        @DisplayName("the whole placement-office question at once")
        void theWholeCohortQuery() throws Exception {
            // "2027 CSE students with Java and Spring Boot, CGPA >= 7.5, resume
            // uploaded" — the workflow this slice exists for.
            String query = "skills=java&skills=spring-boot"
                    + "&departmentId=" + cse.getId()
                    + "&batchId=" + batch2027.getId()
                    + "&minCgpa=7.5&resumeUploaded=true";

            assertThat(names(query)).containsExactly("Anita Sharma");
            assertThat(total(query)).isEqualTo(1);
        }

        @Test
        @DisplayName("an impossible combination is empty, not unfiltered")
        void impossibleCombination() throws Exception {
            assertThat(names("skills=kafka&minCgpa=9")).isEmpty();
            assertThat(total("skills=kafka&minCgpa=9")).isZero();
        }
    }

    // ------------------------------------------------------------- pagination

    @Nested
    @DisplayName("pagination and sorting")
    class PagingAndSorting {

        @Test
        @DisplayName("a filtered result set pages without losing the filter")
        void filtersSurvivePagination() throws Exception {
            String filter = "skills=java&sort=name&size=2";

            assertThat(names(filter + "&page=0")).containsExactly("Anita Sharma", "Bhavesh Rao");
            assertThat(names(filter + "&page=1")).containsExactly("Divya Nair");
            assertThat(total(filter + "&page=1"))
                    .describedAs("the count describes the filtered set on every page")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a page past the end is empty and still reports the filtered total")
        void pageBeyondResults() throws Exception {
            assertThat(names("skills=java&size=2&page=9")).isEmpty();
            assertThat(total("skills=java&size=2&page=9")).isEqualTo(3);
        }

        @Test
        @DisplayName("sort by name is the default and is ascending")
        void sortByName() throws Exception {
            assertThat(names("sort=name"))
                    .containsExactly("Anita Sharma", "Bhavesh Rao", "Chandra Iyer", "Divya Nair");
            assertThat(names("size=25")).isEqualTo(names("sort=name"));
        }

        @Test
        @DisplayName("sort by CGPA is best first, on the normalised scale, unknown last")
        void sortByCgpa() throws Exception {
            // Bhavesh's 4.00/5 must sit above Divya's 6.00/10.
            assertThat(names("sort=cgpa"))
                    .containsExactly("Anita Sharma", "Bhavesh Rao", "Divya Nair", "Chandra Iyer");
        }

        @Test
        @DisplayName("sort by profile completion is most complete first")
        void sortByProfileCompletion() throws Exception {
            assertThat(names("sort=profile"))
                    .containsExactly("Divya Nair", "Anita Sharma", "Bhavesh Rao", "Chandra Iyer");
        }
    }

    // ---------------------------------------------------------- authorization

    @Nested
    @DisplayName("scope holds while searching")
    class Authorization {

        @Test
        @DisplayName("a coordinator searches only inside their department")
        void coordinatorScope() throws Exception {
            assertThat(namesAs(coordinatorToken, "size=25"))
                    .containsExactlyInAnyOrder("Anita Sharma", "Bhavesh Rao", "Divya Nair");
            assertThat(namesAs(coordinatorToken, "q=chandra"))
                    .describedAs("Chandra is in Mechanical; search must not reach them")
                    .isEmpty();
            assertThat(namesAs(coordinatorToken, "skills=spring-boot"))
                    .containsExactlyInAnyOrder("Anita Sharma", "Divya Nair");
        }

        @Test
        @DisplayName("a student cannot use the directory at all")
        void studentIsRefused() throws Exception {
            mockMvc.perform(get("/api/institution/students?q=anita")
                            .header("Authorization", "Bearer " + studentToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller is refused")
        void anonymousIsRefused() throws Exception {
            mockMvc.perform(get("/api/institution/students")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a student at another college is invisible and unreachable")
        void crossInstitutionIsolation() throws Exception {
            assertThat(names("size=25")).hasSize(4).doesNotContain("Anita Sharma Rival");

            mockMvc.perform(get("/api/institution/students/" + rivalAnitaId)
                            .header("Authorization", "Bearer " + officerToken))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a coordinator cannot open a student outside their department")
        void coordinatorCannotOpenOutsideScope() throws Exception {
            mockMvc.perform(get("/api/institution/students/" + chandraId)
                            .header("Authorization", "Bearer " + coordinatorToken))
                    .andExpect(status().isNotFound());
        }
    }

    // ---------------------------------------------------------------- detail

    @Nested
    @DisplayName("student detail")
    class Detail {

        private JsonNode detail(UUID userId, String token) throws Exception {
            return readJson(get("/api/institution/students/" + userId)
                    .header("Authorization", "Bearer " + token));
        }

        @Test
        @DisplayName("the detail describes that student, with their skills and resume state")
        void detailIsComplete() throws Exception {
            JsonNode anita = detail(anitaId, officerToken);

            assertThat(anita.get("summary").get("fullName").asText()).isEqualTo("Anita Sharma");
            assertThat(anita.get("summary").get("rollNumber").asText()).isEqualTo(roll("0501"));
            assertThat(anita.get("summary").get("resumeUploaded").asBoolean()).isTrue();
            assertThat(anita.get("skills").findValuesAsText("name"))
                    .containsExactlyInAnyOrder("Java", "Spring Boot");
            assertThat(anita.get("cgpa").asDouble()).isEqualTo(8.50);
            assertThat(anita.get("normalisedCgpa").asDouble()).isEqualTo(8.50);
        }

        @Test
        @DisplayName("a missing CGPA is reported as missing, not as zero")
        void missingCgpaIsHonest() throws Exception {
            JsonNode chandra = detail(chandraId, officerToken);

            assertThat(chandra.get("cgpa").isNull()).isTrue();
            assertThat(chandra.get("normalisedCgpa").isNull()).isTrue();
        }

        @Test
        @DisplayName("activity is newest first")
        void activityIsChronological() throws Exception {
            JsonNode activity = detail(anitaId, officerToken).get("activity");

            List<String> stamps = activity.findValuesAsText("at");
            assertThat(stamps).hasSizeGreaterThanOrEqualTo(3);
            assertThat(stamps).isSortedAccordingTo(Comparator.reverseOrder());
            assertThat(activity.findValuesAsText("type"))
                    .contains("JOB_APPLIED", "JOB_VIEWED", "RESUME_UPLOADED");
        }

        @Test
        @DisplayName("activity belongs to that student and nobody else")
        void activityDoesNotLeakBetweenStudents() throws Exception {
            List<String> anita = detail(anitaId, officerToken).get("activity")
                    .findValuesAsText("type");
            List<String> bhavesh = detail(bhaveshId, officerToken).get("activity")
                    .findValuesAsText("type");

            // Anita applied and viewed; Bhavesh only saved. Neither may carry the
            // other's events, however similar the two students look.
            assertThat(anita).contains("JOB_APPLIED").doesNotContain("JOB_SAVED");
            assertThat(bhavesh).contains("JOB_SAVED").doesNotContain("JOB_APPLIED");
        }

        @Test
        @DisplayName("a student with no activity gets an empty timeline, not somebody else's")
        void aQuietStudentHasAnEmptyTimeline() throws Exception {
            // Chandra has no resume and no interactions at all.
            assertThat(detail(chandraId, officerToken).get("activity")).isEmpty();
        }

        @Test
        @DisplayName("the detail carries no credentials")
        void detailCarriesNoSecrets() throws Exception {
            String body = detail(anitaId, officerToken).toString().toLowerCase();

            assertThat(body).doesNotContain("passwordhash").doesNotContain("password")
                    .doesNotContain("token").doesNotContain("secret");
        }
    }
}
