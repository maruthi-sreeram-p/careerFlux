package com.careerflux.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import com.careerflux.discovery.dto.DiscoveryDtos.CandidateView;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The order of a discovery page (Phase 3, D-5).
 *
 * <p>No Spring context and no database, which is the point. The list is sorted
 * whole and then cut into pages, so what matters is that the comparator alone
 * settles every tie. A test that relied on a database returning rows in some
 * order would pass or fail on the storage engine's mood; this one hands the
 * comparator the worst order it could get and every order in between.
 */
class DiscoveryOrderingTest {

    private static final List<String> SORTS = java.util.Arrays.asList(null, "", "match", "experience", "eligibility");

    private static CandidateView candidate(String id, Integer compatibility, BigDecimal years, String eligibility) {
        return new CandidateView(UUID.fromString(id), UUID.randomUUID(), "Student", null, null, null,
                compatibility, "MEDIUM", 60, eligibility, List.of(), List.of(), List.of(), List.of(),
                List.of(), years, null, List.of(), List.of(), List.of(), false, null);
    }

    /** Ids chosen so their natural order is visibly not the order they are listed in. */
    private static final String C = "00000000-0000-0000-0000-00000000000c";
    private static final String A = "00000000-0000-0000-0000-00000000000a";
    private static final String D = "00000000-0000-0000-0000-00000000000d";
    private static final String B = "00000000-0000-0000-0000-00000000000b";

    private static List<CandidateView> tied() {
        // Identical on every ranking key: same score, same years, same verdict.
        return List.of(
                candidate(C, 80, new BigDecimal("2.0"), "ELIGIBLE"),
                candidate(A, 80, new BigDecimal("2.0"), "ELIGIBLE"),
                candidate(D, 80, new BigDecimal("2.0"), "ELIGIBLE"),
                candidate(B, 80, new BigDecimal("2.0"), "ELIGIBLE"));
    }

    private static List<UUID> ids(List<CandidateView> views) {
        return views.stream().map(CandidateView::candidateId).toList();
    }

    private static List<CandidateView> sorted(List<CandidateView> input, String sort) {
        List<CandidateView> copy = new ArrayList<>(input);
        copy.sort(CandidateDiscoveryService.comparator(sort));
        return copy;
    }

    @Test
    @DisplayName("candidates who tie on every ranking key are ordered by their own id, whatever order they arrive in")
    void tiesAreSettledByTheCandidateId() {
        List<UUID> expected = List.of(UUID.fromString(A), UUID.fromString(B),
                UUID.fromString(C), UUID.fromString(D));

        for (String sort : SORTS) {
            assertThat(ids(sorted(tied(), sort))).describedAs("sort=%s", sort).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("every arrival order produces the same page: the cohort query's row order does not matter")
    void arrivalOrderDoesNotMatter() {
        Random random = new Random(20260920L);
        for (String sort : SORTS) {
            List<UUID> reference = ids(sorted(tied(), sort));
            for (int run = 0; run < 200; run++) {
                List<CandidateView> shuffled = new ArrayList<>(tied());
                Collections.shuffle(shuffled, random);
                assertThat(ids(sorted(shuffled, sort))).describedAs("sort=%s run=%d", sort, run)
                        .isEqualTo(reference);
            }
        }
    }

    @Test
    @DisplayName("pages cut from the sorted list never repeat a candidate or skip one")
    void pagesPartitionTheList() {
        List<CandidateView> everyone = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            // Five score bands, so there are ties everywhere and the tie-break has real work.
            everyone.add(candidate(new UUID(0L, 1000L - i * 7L).toString(), 50 + (i % 5) * 10,
                    new BigDecimal("3.0"), "UNKNOWN"));
        }
        Random random = new Random(7L);

        for (String sort : SORTS) {
            List<UUID> whole = ids(sorted(everyone, sort));
            for (int run = 0; run < 25; run++) {
                List<CandidateView> shuffled = new ArrayList<>(everyone);
                Collections.shuffle(shuffled, random);
                List<CandidateView> ordered = sorted(shuffled, sort);

                List<UUID> stitched = new ArrayList<>();
                for (int from = 0; from < ordered.size(); from += 4) {
                    stitched.addAll(ids(ordered.subList(from, Math.min(from + 4, ordered.size()))));
                }
                assertThat(stitched).describedAs("sort=%s run=%d", sort, run)
                        .containsExactlyElementsOf(whole).doesNotHaveDuplicates().hasSize(25);
            }
        }
    }

    @Test
    @DisplayName("the id only settles ties: candidates who rank differently keep the order they always had")
    void differentRankingsKeepTheirOrder() {
        // The higher id ranks first on the score, and must stay first.
        CandidateView strong = candidate(D, 90, new BigDecimal("1.0"), "ELIGIBLE");
        CandidateView middling = candidate(B, 70, new BigDecimal("6.0"), "UNKNOWN");
        CandidateView weak = candidate(A, 40, new BigDecimal("9.0"), "NOT_ELIGIBLE");
        CandidateView unscored = candidate(C, null, null, "UNKNOWN");
        List<CandidateView> input = List.of(unscored, weak, middling, strong);

        // By match: highest score first, and no score last. The ids ascend the
        // wrong way round, so a comparator that let the id lead would fail here.
        assertThat(ids(sorted(input, "match"))).containsExactly(
                strong.candidateId(), middling.candidateId(), weak.candidateId(), unscored.candidateId());
        // By experience: most years first, unstated last.
        assertThat(ids(sorted(input, "experience"))).containsExactly(
                weak.candidateId(), middling.candidateId(), strong.candidateId(), unscored.candidateId());
        // By eligibility: eligible, then unknown, then not eligible; the score
        // orders within a verdict, and an unscored candidate sorts after a scored one.
        assertThat(ids(sorted(input, "eligibility"))).containsExactly(
                strong.candidateId(), middling.candidateId(), unscored.candidateId(), weak.candidateId());
    }

    @Test
    @DisplayName("the comparator is a consistent total order, so the sort can never throw or loop")
    void isATotalOrder() {
        List<CandidateView> mixed = List.of(
                candidate(A, 80, new BigDecimal("2.0"), "ELIGIBLE"),
                candidate(B, 80, new BigDecimal("2.0"), "UNKNOWN"),
                candidate(C, null, null, "NOT_ELIGIBLE"),
                candidate(D, 60, new BigDecimal("2.0"), "ELIGIBLE"));

        for (String sort : SORTS) {
            Comparator<CandidateView> order = CandidateDiscoveryService.comparator(sort);
            for (CandidateView left : mixed) {
                assertThat(order.compare(left, left)).isZero();
                for (CandidateView right : mixed) {
                    assertThat(Integer.signum(order.compare(left, right)))
                            .describedAs("antisymmetry, sort=%s", sort)
                            .isEqualTo(-Integer.signum(order.compare(right, left)));
                    if (left != right) {
                        assertThat(order.compare(left, right)).describedAs("distinct candidates never tie")
                                .isNotZero();
                    }
                }
            }
        }
    }
}
