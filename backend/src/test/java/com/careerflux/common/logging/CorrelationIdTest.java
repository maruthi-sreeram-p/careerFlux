package com.careerflux.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Correlation identifiers, and the ways they go wrong.
 *
 * <p>The failure worth testing for is not "the id is missing" — that is obvious
 * the moment anyone reads a log. It is the id being present and belonging to
 * somebody else, which happens when a pooled thread keeps the value left by the
 * previous piece of work. Log lines are then confidently attributed to the wrong
 * request, which is worse than having no identifier at all.
 */
class CorrelationIdTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Nested
    @DisplayName("scoping")
    class Scoping {

        @Test
        @DisplayName("the id is visible inside the scope and gone after it")
        void setAndCleared() {
            assertThat(CorrelationId.current()).isNull();
            CorrelationId.with("abc123", () -> {
                assertThat(CorrelationId.current()).isEqualTo("abc123");
            });
            assertThat(CorrelationId.current())
                    .describedAs("MDC must be clean once the scope ends")
                    .isNull();
        }

        @Test
        @DisplayName("MDC is cleaned even when the work throws")
        void cleanedOnException() {
            // Without the finally, one failing request would poison every later
            // piece of work on that thread.
            assertThatThrownBy(() -> CorrelationId.with("boom", () -> {
                throw new IllegalStateException("failed");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(CorrelationId.current()).isNull();
        }

        @Test
        @DisplayName("a nested scope restores the outer id rather than clearing it")
        void nestedScopeRestores() {
            // An ingestion run started inside a request has its own id; when it
            // finishes, the rest of the request's lines must still be traceable.
            CorrelationId.with("outer", () -> {
                assertThat(CorrelationId.current()).isEqualTo("outer");
                CorrelationId.with("inner", () ->
                        assertThat(CorrelationId.current()).isEqualTo("inner"));
                assertThat(CorrelationId.current())
                        .describedAs("the outer scope must survive the inner one")
                        .isEqualTo("outer");
            });
            assertThat(CorrelationId.current()).isNull();
        }

        @Test
        @DisplayName("a null or blank id leaves whatever was there alone")
        void blankIdIsIgnored() {
            CorrelationId.with("kept", () -> {
                CorrelationId.with(null, () ->
                        assertThat(CorrelationId.current()).isEqualTo("kept"));
                CorrelationId.with("  ", () ->
                        assertThat(CorrelationId.current()).isEqualTo("kept"));
            });
        }

        @Test
        @DisplayName("the value comes back from the supplier form")
        void returnsValue() {
            assertThat(CorrelationId.with("x", () -> 42)).isEqualTo(42);
        }

        @Test
        @DisplayName("generated ids are unique and short enough to read")
        void generated() {
            var ids = new java.util.HashSet<String>();
            for (int i = 0; i < 500; i++) {
                String id = CorrelationId.generate();
                assertThat(id).hasSize(16);
                ids.add(id);
            }
            assertThat(ids).hasSize(500);
        }
    }

    @Nested
    @DisplayName("no leakage between pieces of work")
    class Isolation {

        @Test
        @DisplayName("a reused thread never carries the previous id")
        void pooledThreadDoesNotLeak() throws Exception {
            // One thread, several sequential tasks — the shape of a servlet
            // container or a task executor. Each must see only its own id.
            ExecutorService oneThread = Executors.newSingleThreadExecutor();
            List<String> seenBefore = new ArrayList<>();
            try {
                for (int i = 0; i < 5; i++) {
                    String id = "task-" + i;
                    oneThread.submit(() -> {
                        seenBefore.add(String.valueOf(CorrelationId.current()));
                        CorrelationId.with(id, () ->
                                assertThat(CorrelationId.current()).isEqualTo(id));
                    }).get();
                }
            } finally {
                oneThread.shutdownNow();
            }
            assertThat(seenBefore)
                    .describedAs("every task must start with a clean slate")
                    .containsOnly("null");
        }

        @Test
        @DisplayName("concurrent work keeps its own id")
        void concurrentScopesAreIndependent() throws Exception {
            int threads = 16;
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(threads);
            var mismatches = new java.util.concurrent.ConcurrentLinkedQueue<String>();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (int i = 0; i < threads; i++) {
                    String id = "id-" + i;
                    pool.submit(() -> {
                        try {
                            start.await();
                            CorrelationId.with(id, () -> {
                                for (int spin = 0; spin < 200; spin++) {
                                    if (!id.equals(CorrelationId.current())) {
                                        mismatches.add(id + " saw " + CorrelationId.current());
                                        return;
                                    }
                                    Thread.yield();
                                }
                            });
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }
            assertThat(mismatches).isEmpty();
        }
    }

    @Nested
    @DisplayName("the HTTP filter")
    class Filter {

        private final CorrelationIdFilter filter = new CorrelationIdFilter();

        @Test
        @DisplayName("generates an id, exposes it during the request, and cleans up")
        void generatesAndCleans() throws Exception {
            var request = new MockHttpServletRequest("GET", "/api/jobs");
            var response = new MockHttpServletResponse();
            var seen = new String[1];
            FilterChain chain = (req, res) -> seen[0] = CorrelationId.current();

            filter.doFilter(request, response, chain);

            assertThat(seen[0]).isNotBlank();
            assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(seen[0]);
            assertThat(CorrelationId.current())
                    .describedAs("the request thread must be clean afterwards")
                    .isNull();
        }

        @Test
        @DisplayName("honours a caller's id so a trace crosses service boundaries")
        void honoursInboundHeader() throws Exception {
            var request = new MockHttpServletRequest("GET", "/api/jobs");
            request.addHeader(CorrelationIdFilter.HEADER, "upstream-123");
            var response = new MockHttpServletResponse();
            var seen = new String[1];

            filter.doFilter(request, response, (req, res) -> seen[0] = CorrelationId.current());

            assertThat(seen[0]).isEqualTo("upstream-123");
        }

        @Test
        @DisplayName("refuses a header that could forge log lines")
        void rejectsLogInjection() throws Exception {
            // The header lands directly in log output. A newline in it would let
            // a caller write whole fabricated log lines, so anything that is not
            // a plain token is replaced rather than trusted.
            for (String hostile : List.of(
                    "abc\nINFO  [] c.c.Fake : transfer approved",
                    "abc\r\nWARN fake",
                    "abc def",
                    "../../etc/passwd",
                    "<script>alert(1)</script>",
                    "x".repeat(200))) {
                var request = new MockHttpServletRequest("GET", "/api/jobs");
                request.addHeader(CorrelationIdFilter.HEADER, hostile);
                var response = new MockHttpServletResponse();
                var seen = new String[1];

                filter.doFilter(request, response, (req, res) -> seen[0] = CorrelationId.current());

                assertThat(seen[0])
                        .describedAs("hostile header %s must not be used verbatim", hostile)
                        .isNotEqualTo(hostile)
                        .doesNotContain("\n").doesNotContain("\r").doesNotContain(" ")
                        .hasSize(16);
            }
        }

        @Test
        @DisplayName("cleans up even when the request handler throws")
        void cleansUpOnFailure() {
            var request = new MockHttpServletRequest("GET", "/api/jobs");
            var response = new MockHttpServletResponse();
            FilterChain exploding = (req, res) -> {
                throw new IllegalStateException("handler failed");
            };

            assertThatThrownBy(() -> filter.doFilter(request, response, exploding))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("handler failed");
            assertThat(CorrelationId.current()).isNull();
        }

        @Test
        @DisplayName("a checked servlet failure keeps its own type")
        void preservesCheckedExceptions() {
            var request = new MockHttpServletRequest("GET", "/api/jobs");
            var response = new MockHttpServletResponse();
            FilterChain io = (req, res) -> {
                throw new java.io.IOException("socket closed");
            };

            assertThatThrownBy(() -> filter.doFilter(request, response, io))
                    .describedAs("wrapping must not change what callers catch")
                    .isInstanceOf(java.io.IOException.class);
            assertThat(CorrelationId.current()).isNull();
        }
    }
}
