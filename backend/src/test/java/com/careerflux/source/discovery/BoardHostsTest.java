package com.careerflux.source.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.careerflux.source.adapter.AshbyAdapter;
import com.careerflux.source.adapter.GreenhouseAdapter;
import com.careerflux.source.adapter.LeverAdapter;
import com.careerflux.source.discovery.BoardHosts.Recognized;
import com.careerflux.source.domain.AtsProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The board-host registry: what CareerFlux recognises, and — kept apart — what
 * it can actually read.
 */
class BoardHostsTest {

    private static Recognized recognize(String url) {
        return BoardHosts.recognize(url).orElseThrow(() -> new AssertionError("not recognised: " + url));
    }

    @Nested
    @DisplayName("boards CareerFlux can read")
    class Ingestible {

        @Test
        @DisplayName("a Greenhouse board, however it is linked, is read through its public API")
        void greenhouse() {
            for (String url : List.of("https://boards.greenhouse.io/acme", "https://job-boards.greenhouse.io/acme/jobs/123",
                    "https://boards.greenhouse.io/embed/job_board?for=acme",
                    "https://boards-api.greenhouse.io/v1/boards/acme/jobs")) {
                Recognized board = recognize(url);
                assertThat(board.provider()).describedAs(url).isEqualTo(AtsProvider.GREENHOUSE);
                assertThat(board.token()).describedAs(url).isEqualTo("acme");
                assertThat(board.adapterKey()).isEqualTo(GreenhouseAdapter.KEY);
                assertThat(board.sourceUrl()).isEqualTo("https://boards-api.greenhouse.io/v1/boards/acme/jobs");
            }
        }

        @Test
        @DisplayName("a Lever board is read through its public postings API")
        void lever() {
            Recognized board = recognize("https://jobs.lever.co/acme/1234-abcd");
            assertThat(board.provider()).isEqualTo(AtsProvider.LEVER);
            assertThat(board.adapterKey()).isEqualTo(LeverAdapter.KEY);
            assertThat(board.sourceUrl()).isEqualTo("https://api.lever.co/v0/postings/acme");
        }

        @Test
        @DisplayName("an Ashby board is read through its public posting API")
        void ashby() {
            Recognized board = recognize("https://jobs.ashbyhq.com/acme");
            assertThat(board.provider()).isEqualTo(AtsProvider.ASHBY);
            assertThat(board.adapterKey()).isEqualTo(AshbyAdapter.KEY);
            assertThat(board.sourceUrl()).isEqualTo("https://api.ashbyhq.com/posting-api/job-board/acme");
        }
    }

    @Nested
    @DisplayName("boards CareerFlux recognises but cannot read")
    class RecognisedOnly {

        @Test
        @DisplayName("a JazzHR board on applytojob.com has no adapter and is registered at its public board")
        void jazzhr() {
            Recognized board = recognize("https://autorabit.applytojob.com/apply/pCgw7Isara/Software-Engineer");
            assertThat(board.provider()).isEqualTo(AtsProvider.JAZZHR);
            assertThat(board.token()).isEqualTo("autorabit");
            assertThat(board.ingestible()).isFalse();
            assertThat(board.adapterKey()).isNull();
            assertThat(board.sourceUrl()).isEqualTo("https://autorabit.applytojob.com/apply");
        }

        @Test
        @DisplayName("a Workday career site keeps its tenant and site, and has no adapter")
        void workday() {
            Recognized board = recognize("https://acme.wd5.myworkdayjobs.com/en-US/External_Careers/job/Pune/123");
            assertThat(board.provider()).isEqualTo(AtsProvider.WORKDAY);
            assertThat(board.token()).isEqualTo("acme.wd5/External_Careers");
            assertThat(board.adapterKey()).isNull();
            assertThat(board.sourceUrl()).isEqualTo("https://acme.wd5.myworkdayjobs.com/External_Careers");
        }

        @Test
        @DisplayName("a Workday link that stops at a locale names no site and is not a board")
        void workdayLocaleIsNotASite() {
            assertThat(BoardHosts.recognize("https://acme.wd5.myworkdayjobs.com/en-US")).isEmpty();
        }

        @Test
        @DisplayName("an iCIMS portal needs authorization CareerFlux does not have, so it is recorded only")
        void icims() {
            Recognized board = recognize("https://careers-acme.icims.com/jobs/search");
            assertThat(board.provider()).isEqualTo(AtsProvider.ICIMS);
            assertThat(board.token()).isEqualTo("careers-acme");
            assertThat(board.family().ingestion()).isEqualTo(BoardHosts.Ingestion.AUTHORIZATION_REQUIRED);
            assertThat(board.adapterKey()).isNull();
        }

        @Test
        @DisplayName("only the families with a public API are ever probed by token")
        void onlyPublicApisAreProbed() {
            assertThat(BoardHosts.ingestibleFamilies()).extracting(BoardHosts.Family::provider)
                    .containsExactly(AtsProvider.GREENHOUSE, AtsProvider.LEVER, AtsProvider.ASHBY);
        }
    }

    @Nested
    @DisplayName("what is not a board")
    class NotABoard {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://autorabit.applytojob.com.attacker.example/apply",
                "https://notapplytojob.com/apply",
                "https://autorabit-applytojob.com/apply",
                "https://boards.greenhouse.io.attacker.example/acme",
                "https://jobs.lever.co.attacker.example/acme",
                "https://careers-acme.icims.com.attacker.example/",
                "https://www.applytojob.com/",
                "https://www.icims.com/products",
                "https://developer-community.icims.com/applications",
                "https://jobs.unknown-ats.example/acme",
                "https://www.linkedin.com/company/autorabit",
                "https://www.autorabit.com/careers/"})
        @DisplayName("a look-alike, a vendor's own site, or an unknown host is not recognised")
        void notRecognised(String url) {
            assertThat(BoardHosts.recognize(url)).isEmpty();
        }
    }

    @Nested
    @DisplayName("reading a page")
    class ReadingAPage {

        @Test
        @DisplayName("every recognised board on a page, in page order, each once; nothing else")
        void findsBoardsInOrder() {
            String html = """
                    <a href="https://www.linkedin.com/company/acme">LinkedIn</a>
                    <a href="https://acme.applytojob.com/apply/abc/Engineer">Engineer</a>
                    <a href="https://acme.applytojob.com/apply/def/Analyst">Analyst</a>
                    <a href="https://jobs.unknown-ats.example/acme">Elsewhere</a>
                    <a href="https://boards.greenhouse.io/acme">Board</a>
                    """;
            assertThat(BoardHosts.findIn(html)).extracting(Recognized::provider)
                    .containsExactly(AtsProvider.JAZZHR, AtsProvider.GREENHOUSE);
        }
    }
}
