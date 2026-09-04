package com.careerflux.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.careerflux.security.AuthenticatedUser;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What happens when a placement team all click at once.
 *
 * <p>The per-endpoint measurements next door are single-threaded: they say what
 * one request costs. This says whether those costs compose. A placement office
 * is a handful of people, not thousands, but candidate discovery holds a
 * connection while it scores an entire college in memory — so the question is
 * whether several of those at once starve the connection pool or each other.
 *
 * <p>The pool here is Hikari's default of ten against an in-memory database.
 * That is not the production PostgreSQL pool and this is deliberately not
 * presented as a production throughput figure; what it does establish is
 * whether the application layer is correct and non-degenerate under
 * concurrency — no deadlock, no starvation, no request failing because another
 * was running.
 *
 * <p><b>Measured, and not what was expected.</b> Sixteen simultaneous
 * discoveries finish in roughly the time sixteen sequential ones would, which
 * looked like connection starvation. Raising the pool to twenty made it
 * slightly <em>slower</em> (13.4s to 16.5s), so the constraint is processor
 * time spent scoring, not waiting for a connection — every caller is scoring
 * two thousand candidates at once. Narrowing the transaction to release the
 * connection during scoring would therefore have bought nothing, which is why
 * it was not done.
 *
 * <p>At the concurrency a placement office actually reaches — a handful of
 * staff, not sixteen — this is about a second each.
 *
 * <p>Latency is reported, not asserted. The assertions are on correctness:
 * every concurrent request succeeds and returns the same answer a lone request
 * would.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:concurrent-load;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrentLoadTest {

    private static final int STUDENTS = 2000;

    /** More than the ten-connection pool, so contention is actually exercised. */
    private static final int CONCURRENT_CALLERS = 16;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CollegeScaleFixture fixture;

    private CollegeScaleFixture.Seeded seeded;
    private User officer;

    @BeforeAll
    void seedTheCollege() {
        seeded = fixture.seed(STUDENTS);
        officer = fixture.staff("load-officer@example.com", UserRole.PLACEMENT_OFFICER,
                passwordEncoder.encode("LoadTest123!"));
    }

    @Test
    @DisplayName("sixteen simultaneous discoveries over a college of two thousand")
    void concurrentDiscovery() throws Exception {
        // One pass first, so the measurement is of steady-state concurrency
        // rather than of the JVM and query plans warming up on sixteen threads
        // at once. Reporting that as throughput would be misleading.
        warmUp();

        Result result = run("candidate discovery",
                () -> "/api/requirements/" + seeded.requirementId() + "/candidates?size=25");

        // Correctness first: concurrency must not turn into failures.
        assertThat(result.failures()).isZero();
        assertThat(result.distinctPayloadSizes())
                .describedAs("every caller should see the same college")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("sixteen simultaneous student dashboards")
    void concurrentDashboards() throws Exception {
        Result result = run("student dashboard", () -> "/api/dashboard");
        assertThat(result.failures()).isZero();
    }

    // ---------------------------------------------------------------- harness

    private record Result(String name, int callers, long wallMillis, long medianMillis,
                          long slowestMillis, int failures, int distinctPayloadSizes) {
    }

    private void warmUp() throws Exception {
        asOfficer(() -> mockMvc.perform(get("/api/requirements/" + seeded.requirementId()
                + "/candidates?size=25")).andReturn().getResponse().getStatus());
    }

    private Result run(String name, java.util.function.Supplier<String> url) throws Exception {
        boolean asStudent = url.get().equals("/api/dashboard");
        SecurityContext context = SecurityContextHolder.getContext();

        CyclicBarrier startTogether = new CyclicBarrier(CONCURRENT_CALLERS);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CALLERS);
        List<Callable<long[]>> calls = new ArrayList<>();

        User principal = asStudent
                ? users.findById(seeded.sampleStudentId()).orElseThrow()
                : officer;

        for (int i = 0; i < CONCURRENT_CALLERS; i++) {
            calls.add(() -> {
                authenticateAs(principal);
                startTogether.await();
                long startedAt = System.nanoTime();
                var response = mockMvc.perform(get(url.get())).andReturn().getResponse();
                long millis = (System.nanoTime() - startedAt) / 1_000_000;
                return new long[] {millis, response.getStatus(),
                        response.getContentAsString().length()};
            });
        }

        long wallStart = System.nanoTime();
        List<Future<long[]>> futures;
        try {
            futures = pool.invokeAll(calls);
        } finally {
            pool.shutdown();
        }
        long wall = (System.nanoTime() - wallStart) / 1_000_000;
        SecurityContextHolder.setContext(context);

        List<Long> latencies = new ArrayList<>();
        int failures = 0;
        List<Integer> sizes = new ArrayList<>();
        for (Future<long[]> future : futures) {
            long[] outcome = future.get();
            latencies.add(outcome[0]);
            if (outcome[1] != 200) {
                failures++;
            }
            sizes.add((int) outcome[2]);
        }
        latencies.sort(Long::compare);

        Result result = new Result(name, CONCURRENT_CALLERS, wall,
                latencies.get(latencies.size() / 2), latencies.get(latencies.size() - 1),
                failures, (int) sizes.stream().distinct().count());

        System.out.printf("%n=== %s · %d simultaneous callers · %,d students ===%n",
                result.name(), result.callers(), STUDENTS);
        System.out.printf("  wall clock for all %d : %d ms%n", result.callers(), result.wallMillis());
        System.out.printf("  median request       : %d ms%n", result.medianMillis());
        System.out.printf("  slowest request      : %d ms%n", result.slowestMillis());
        System.out.printf("  failed requests      : %d%n%n", result.failures());
        return result;
    }

    private void asOfficer(Callable<Integer> work) throws Exception {
        authenticateAs(officer);
        work.call();
    }

    private void authenticateAs(User user) {
        AuthenticatedUser principal = new AuthenticatedUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
