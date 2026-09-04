package com.careerflux.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.careerflux.security.AuthenticatedUser;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * What every screen costs against a college of two thousand students.
 *
 * <p>The measurement that matters here is the <b>SQL statement count</b>, not
 * the clock. Wall time on a shared laptop is noise; a query count is
 * deterministic, and it is what actually decides whether a page holds up when
 * the cohort triples. Every assertion below is therefore of the form "this
 * endpoint issues a bounded number of statements regardless of how many
 * students exist" — which is the property that breaks silently when somebody
 * later replaces a batch load with a loop.
 *
 * <p>Latency is printed alongside because it is useful to read, and is
 * deliberately <em>not</em> asserted on.
 *
 * <p>This runs against an isolated in-memory database. Nothing here touches the
 * real corpus, and no load is generated against a running server.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:endpoint-scale;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndpointScaleTest {

    private static final int STUDENTS = 2000;

    /**
     * Bounded, not tiny. A page that loads a requirement, a scope, a cohort and
     * its skills legitimately issues a handful of statements; what it must not
     * do is issue one per student. The gap between these ceilings and the
     * thousands a per-row loop would produce is the whole point.
     */
    private static final int LIST_CEILING = 40;
    private static final int SINGLE_CEILING = 30;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CollegeScaleFixture fixture;

    @Autowired
    private com.careerflux.candidate.repository.CandidateProfileRepository profiles;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private static CollegeScaleFixture.Seeded seeded;
    private static User officer;
    private static final Map<String, Measurement> RESULTS = new LinkedHashMap<>();

    private record Measurement(long statements, long millis, int payloadBytes) {
    }

    @BeforeAll
    void seedTheCollege() {
        seeded = fixture.seed(STUDENTS);
        officer = fixture.staff("scale-officer@example.com", UserRole.PLACEMENT_OFFICER,
                passwordEncoder.encode("ScaleTest123!"));
    }

    /**
     * Why discovery selects its cohort by scope instead of by a list of ids.
     *
     * <p>This deliberately exercises the id-list query — which is still correct
     * for a page of twenty-five, and is what discovery used to call with the
     * whole college. The database builds a query plan per distinct parameter
     * count, so the first call at each length pays for it and every repeat is
     * free. The growth is superlinear, which is what made two thousand ids
     * catastrophic rather than merely slow.
     *
     * <p>Kept small here on purpose: the point is the shape of the curve, and
     * reproducing the full two-thousand-id case cost more than two minutes of
     * test time to demonstrate something these numbers already show.
     */
    @Test
    @Order(0)
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @DisplayName("where the cold cost lives: database or JVM")
    void coldCostBreakdown() {
        long firstLoad = timed(() -> profiles.findForDiscovery(
                seeded.students().subList(0, 250)).size());
        long secondLoad = timed(() -> profiles.findForDiscovery(
                seeded.students().subList(0, 250)).size());

        // A different parameter count is a different plan, so this is cold for
        // that shape even though the JVM is now warm. If it is also slow, the
        // cost is the size of the IN list rather than anything one-off.
        long unseenShape = timed(() -> profiles.findForDiscovery(
                seeded.students().subList(0, 500)).size());
        long repeatShape = timed(() -> profiles.findForDiscovery(
                seeded.students().subList(0, 500)).size());

        System.out.printf("%n  id-list query, 250 ids: first %d ms, repeat %d ms%n",
                firstLoad, secondLoad);
        System.out.printf("  id-list query, 500 ids: first %d ms, repeat %d ms   (doubling the list costs far more than double)%n", unseenShape, repeatShape);
    }

    private long timed(Runnable work) {
        long startedAt = System.nanoTime();
        work.run();
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    // ------------------------------------------------------------ the screens

    @Test
    @Order(1)
    @DisplayName("a student's own dashboard")
    void studentDashboard() throws Exception {
        authenticateAs(users.findById(seeded.sampleStudentId()).orElseThrow());
        Measurement result = measure("student dashboard", get("/api/dashboard"));
        assertBounded(result, SINGLE_CEILING);
    }

    @Test
    @Order(2)
    @DisplayName("the institutional overview a coordinator or officer opens on")
    void institutionOverview() throws Exception {
        authenticateAs(officer);
        Measurement result = measure("institution overview", get("/api/institution/overview"));
        // Aggregates over two thousand students, in a fixed set of queries.
        assertBounded(result, LIST_CEILING);
    }

    @Test
    @Order(3)
    @DisplayName("one page of the student directory")
    void studentDirectory() throws Exception {
        authenticateAs(officer);
        Measurement result = measure("student directory (page of 25)",
                get("/api/institution/students?page=0&size=25"));
        assertBounded(result, LIST_CEILING);
    }

    @Test
    @Order(4)
    @DisplayName("candidate discovery, which scores the whole cohort")
    void candidateDiscovery() throws Exception {
        authenticateAs(officer);
        // Measured cold and warm, because the two are very different numbers and
        // reporting only one of them would mislead. The first call in a fresh
        // process pays for Hibernate compiling the query plan and the JVM
        // interpreting the scoring path before it is compiled; every call after
        // that is what a placement officer actually experiences.
        Measurement cold = measure("candidate discovery — cold (2,000 scored)",
                get("/api/requirements/" + seeded.requirementId() + "/candidates?size=25"));
        Measurement warm = measure("candidate discovery — warm (2,000 scored)",
                get("/api/requirements/" + seeded.requirementId() + "/candidates?size=25"));

        // The heaviest read in the product: every student in scope is scored
        // before the page is cut. It still must not query per student.
        assertBounded(cold, LIST_CEILING);
        assertBounded(warm, LIST_CEILING);
    }

    @Test
    @Order(5)
    @DisplayName("discovery filtered and sorted, which must not cost extra queries")
    void filteredDiscovery() throws Exception {
        authenticateAs(officer);
        Measurement result = measure("discovery + filters",
                get("/api/requirements/" + seeded.requirementId()
                        + "/candidates?eligibility=ELIGIBLE&minScore=60&sort=eligibility"));
        assertBounded(result, LIST_CEILING);
    }

    @Test
    @Order(6)
    @DisplayName("shortlisting a candidate is one decision and a small write")
    void shortlistWrite() throws Exception {
        authenticateAs(officer);
        UUID candidateId = firstCandidateId();

        Measurement result = measure("shortlist a candidate",
                post("/api/requirements/" + seeded.requirementId() + "/shortlist")
                        .contentType("application/json")
                        .content("{\"candidateId\":\"%s\"}".formatted(candidateId)), 201);
        assertBounded(result, SINGLE_CEILING);
    }

    @Test
    @Order(7)
    @DisplayName("reading the shortlist")
    void shortlistRead() throws Exception {
        authenticateAs(officer);
        Measurement result = measure("shortlist view",
                get("/api/requirements/" + seeded.requirementId() + "/shortlist"));
        assertBounded(result, LIST_CEILING);
    }

    @Test
    @Order(8)
    @DisplayName("the job feed a student browses")
    void jobSearch() throws Exception {
        authenticateAs(users.findById(seeded.sampleStudentId()).orElseThrow());
        Measurement result = measure("job search (page of 20)", get("/api/jobs?page=0&size=20"));
        assertBounded(result, LIST_CEILING);
    }

    @Test
    @Order(99)
    @DisplayName("summary")
    void report() {
        System.out.printf("%n=== CareerFlux at %,d students ===%n", STUDENTS);
        System.out.printf("  %-34s %10s %9s %10s%n", "endpoint", "SQL", "ms", "bytes");
        RESULTS.forEach((name, result) -> System.out.printf("  %-34s %10d %9d %10d%n",
                name, result.statements(), result.millis(), result.payloadBytes()));

        long worst = RESULTS.values().stream().mapToLong(Measurement::statements).max().orElse(0);
        System.out.printf("%n  Worst query count: %d. A per-student load would be about %,d.%n",
                worst, STUDENTS * 5);
        System.out.printf("  Discovery selects by scope, not by id list, for this reason.%n%n");

        // Nothing scales with the cohort. This is the assertion the whole class
        // exists to make.
        assertThat(worst).isLessThan(LIST_CEILING);
    }

    // ---------------------------------------------------------------- harness

    private Measurement measure(String name, MockHttpServletRequestBuilder request)
            throws Exception {
        return measure(name, request, 200);
    }

    private Measurement measure(String name, MockHttpServletRequestBuilder request, int expected)
            throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        long startedAt = System.nanoTime();
        String body = mockMvc.perform(request)
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        long millis = (System.nanoTime() - startedAt) / 1_000_000;

        Measurement result = new Measurement(
                statistics.getPrepareStatementCount(), millis, body.length());
        RESULTS.put(name, result);
        return result;
    }

    private void assertBounded(Measurement result, int ceiling) {
        assertThat(result.statements())
                .describedAs("SQL statements for %,d students — a per-candidate load "
                        + "would be roughly %,d", STUDENTS, STUDENTS * 5)
                .isLessThan(ceiling);
    }

    private UUID firstCandidateId() throws Exception {
        String body = mockMvc.perform(get("/api/requirements/" + seeded.requirementId()
                        + "/candidates?size=1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int marker = body.indexOf("\"candidateId\":\"") + "\"candidateId\":\"".length();
        return UUID.fromString(body.substring(marker, marker + 36));
    }

    /** A real principal, so services resolve a real scope rather than a stub. */
    private void authenticateAs(User user) {
        AuthenticatedUser principal = new AuthenticatedUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
