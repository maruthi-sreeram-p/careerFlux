package com.careerflux.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * V14 against a database that is not the one every other test shares.
 *
 * <p>The rest of the suite proves the migration works on an empty schema, since
 * that is what each Spring context starts from. It cannot prove the thing an
 * operator actually cares about — that running V14 against a college's live
 * database, with students and resumes already in it, adds a table and leaves
 * everything else exactly as it was.
 *
 * <p>So each test here builds its own H2 instance, migrates it to V13, puts real
 * rows in it, and only then applies V14. No Spring context, no shared state, and
 * nothing that could be explained by Flyway having already run somewhere else.
 */
class AiProfileProposalMigrationTest {

    private static final String LATEST = "15";
    /** The last version before the proposals table existed. */
    private static final String BEFORE_PROPOSALS = "13";
    /** The proposals table as V14 left it, before FAILED was allowed. */
    private static final String BEFORE_FAILED = "14";

    private static String freshDatabase() {
        return "jdbc:h2:mem:migration-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
    }

    private static Flyway flywayFor(String url, String target) {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static Connection open(String url) throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    private static void run(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : -1;
        }
    }

    private static List<String> columnsOf(Connection connection, String table) throws SQLException {
        List<String> found = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getColumns(null, null,
                table.toUpperCase(Locale.ROOT), null)) {
            while (rs.next()) {
                found.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        if (found.isEmpty()) {
            try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, null)) {
                while (rs.next()) {
                    found.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        return found;
    }

    /**
     * A student, their profile and a stored resume — the rows V14 has to leave
     * alone, and the rows its foreign keys point at.
     */
    private record Seeded(UUID institutionId, UUID userId, UUID profileId, UUID resumeId) {
    }

    private static Seeded seed(Connection connection) throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        UUID resume = UUID.randomUUID();

        // Only the columns the schema actually requires. Naming optional ones
        // would make this test fail the next time one of them is renamed, which
        // is not what it is here to catch.
        run(connection, "insert into institutions (id, name, slug, status, created_at, updated_at) "
                + "values ('" + institution + "', 'Migration College', 'migration-"
                + institution + "', 'ACTIVE', current_timestamp, current_timestamp)");
        run(connection, "insert into users (id, email, password_hash, role, status, "
                + "institution_id, created_at, updated_at) values ('" + user + "', 'migration-"
                + user + "@example.com', 'x', 'STUDENT', 'ACTIVE', '" + institution
                + "', current_timestamp, current_timestamp)");
        run(connection, "insert into candidate_profiles (id, user_id, onboarding_stage, "
                + "cgpa, cgpa_scale, cgpa_source, created_at, updated_at) values ('" + profile
                + "', '" + user + "', 'PROFILE_REVIEW', 8.50, 10.00, 'INSTITUTION', "
                + "current_timestamp, current_timestamp)");
        run(connection, "insert into resumes (id, candidate_id, original_filename, storage_path, "
                + "parse_status, uploaded_at) values ('" + resume + "', '" + profile
                + "', 'cv.pdf', 'candidates/" + profile + "/cv.pdf', 'PARSED', current_timestamp)");

        return new Seeded(institution, user, profile, resume);
    }

    private static void insertProposal(Connection connection, Seeded seeded, String status)
            throws SQLException {
        run(connection, "insert into ai_profile_proposals (id, candidate_id, resume_id, status, "
                + "payload, engine, ai_assisted, row_version, created_at, updated_at, reviewed_at) "
                + "values ('" + UUID.randomUUID() + "', '" + seeded.profileId() + "', '"
                + seeded.resumeId() + "', '" + status + "', '{\"schemaVersion\":1,\"items\":[]}', "
                + "'gemini-2.5-flash', true, 0, current_timestamp, current_timestamp, "
                + (List.of("PENDING", "SUPERSEDED", "FAILED").contains(status)
                        ? "null" : "current_timestamp")
                + ")");
    }

    // --------------------------------------------------------------- fresh

    @Nested
    @DisplayName("on a database with nothing in it")
    class FreshDatabase {

        @Test
        @DisplayName("every migration applies in order, ending at V14")
        void migratesFromNothing() throws SQLException {
            String url = freshDatabase();

            var result = flywayFor(url, LATEST).migrate();

            assertThat(result.success).isTrue();
            assertThat(result.targetSchemaVersion).isEqualTo("15");
            try (Connection connection = open(url)) {
                assertThat(count(connection, "select count(*) from ai_profile_proposals")).isZero();
            }
        }

        @Test
        @DisplayName("the table has the columns the entity maps, and no others")
        void columnsMatchTheEntity() throws SQLException {
            String url = freshDatabase();
            flywayFor(url, LATEST).migrate();

            try (Connection connection = open(url)) {
                assertThat(columnsOf(connection, "ai_profile_proposals"))
                        .containsExactlyInAnyOrder("id", "candidate_id", "resume_id", "status",
                                "payload", "engine", "ai_assisted", "correlation_id", "row_version",
                                "created_at", "updated_at", "reviewed_at", "reviewed_by");
            }
        }

        @Test
        @DisplayName("the indexes the review screen relies on exist")
        void indexesExist() throws SQLException {
            String url = freshDatabase();
            flywayFor(url, LATEST).migrate();

            try (Connection connection = open(url)) {
                assertThat(count(connection,
                        "select count(*) from information_schema.indexes "
                                + "where lower(index_name) in ('idx_ai_proposals_candidate', "
                                + "'idx_ai_proposals_resume')"))
                        .describedAs("both named indexes should be present")
                        .isGreaterThanOrEqualTo(2);
            }
        }
    }

    // ------------------------------------------------------------ existing

    @Nested
    @DisplayName("on a database that already has students in it")
    class ExistingDatabase {

        @Test
        @DisplayName("V14 adds its table and touches nothing that was already there")
        void existingDataSurvives() throws SQLException {
            String url = freshDatabase();
            flywayFor(url, BEFORE_PROPOSALS).migrate();

            Seeded seeded;
            try (Connection connection = open(url)) {
                seeded = seed(connection);
                assertThat(count(connection, "select count(*) from resumes")).isEqualTo(1);
            }

            var result = flywayFor(url, LATEST).migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted)
                    .describedAs("V14 and V15 should have been the outstanding ones")
                    .isEqualTo(2);

            try (Connection connection = open(url)) {
                assertThat(count(connection, "select count(*) from users where id = '"
                        + seeded.userId() + "'")).isEqualTo(1);
                assertThat(count(connection, "select count(*) from candidate_profiles where id = '"
                        + seeded.profileId() + "'")).isEqualTo(1);
                assertThat(count(connection, "select count(*) from resumes where id = '"
                        + seeded.resumeId() + "'")).isEqualTo(1);
                assertThat(count(connection, "select count(*) from candidate_profiles where id = '"
                        + seeded.profileId() + "' and cgpa = 8.50 and cgpa_source = 'INSTITUTION'"))
                        .describedAs("an existing CGPA is not disturbed by adding a proposals table")
                        .isEqualTo(1);
                assertThat(count(connection, "select count(*) from ai_profile_proposals")).isZero();
            }
        }

        @Test
        @DisplayName("a proposal can be written against rows that predate the migration")
        void proposalsAttachToExistingRows() throws SQLException {
            String url = freshDatabase();
            flywayFor(url, BEFORE_PROPOSALS).migrate();
            Seeded seeded;
            try (Connection connection = open(url)) {
                seeded = seed(connection);
            }
            flywayFor(url, LATEST).migrate();

            try (Connection connection = open(url)) {
                insertProposal(connection, seeded, "PENDING");
                assertThat(count(connection, "select count(*) from ai_profile_proposals")).isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("widening the status constraint in V15")
    class FailedStatusMigration {

        @Test
        @DisplayName("V14 alone refuses FAILED, and V15 is what allows it")
        void v15IsWhatAllowsFailed() throws SQLException {
            String url = freshDatabase();
            flywayFor(url, BEFORE_FAILED).migrate();

            Seeded seeded;
            try (Connection connection = open(url)) {
                seeded = seed(connection);
                assertThatThrownBy(() -> insertProposal(connection, seeded, "FAILED"))
                        .describedAs("V14's constraint is what V15 exists to widen")
                        .isInstanceOf(SQLException.class);
                insertProposal(connection, seeded, "PENDING");
            }

            var result = flywayFor(url, LATEST).migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isEqualTo(1);

            try (Connection connection = open(url)) {
                assertThat(count(connection,
                        "select count(*) from ai_profile_proposals where status = 'PENDING'"))
                        .describedAs("proposals written before the change are untouched")
                        .isEqualTo(1);
                insertProposal(connection, seeded, "FAILED");
                assertThat(count(connection,
                        "select count(*) from ai_profile_proposals where status = 'FAILED'"))
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a FAILED proposal needs no reviewer, like a superseded one")
        void failedNeedsNoReviewer() throws SQLException {
            String url = freshDatabase();
            flywayFor(url, LATEST).migrate();
            try (Connection connection = open(url)) {
                Seeded seeded = seed(connection);
                insertProposal(connection, seeded, "FAILED");

                assertThat(count(connection, "select count(*) from ai_profile_proposals "
                        + "where status = 'FAILED' and reviewed_at is null and reviewed_by is null"))
                        .isEqualTo(1);
            }
        }
    }

    // ---------------------------------------------------------- constraints

    @Nested
    @DisplayName("the constraints the workflow depends on are real")
    class Constraints {

        private String migrated() {
            String url = freshDatabase();
            flywayFor(url, LATEST).migrate();
            return url;
        }

        @Test
        @DisplayName("a proposal cannot point at a candidate that does not exist")
        void candidateForeignKeyHolds() throws SQLException {
            String url = migrated();
            try (Connection connection = open(url)) {
                Seeded orphan = new Seeded(null, null, UUID.randomUUID(), UUID.randomUUID());
                assertThatThrownBy(() -> insertProposal(connection, orphan, "PENDING"))
                        .isInstanceOf(SQLException.class);
            }
        }

        @Test
        @DisplayName("deleting a candidate takes their proposals with them")
        void cascadeOnCandidateDelete() throws SQLException {
            String url = migrated();
            try (Connection connection = open(url)) {
                Seeded seeded = seed(connection);
                insertProposal(connection, seeded, "PENDING");

                run(connection, "delete from candidate_profiles where id = '" + seeded.profileId() + "'");

                assertThat(count(connection, "select count(*) from ai_profile_proposals"))
                        .describedAs("a removed student leaves no readings of their resume behind")
                        .isZero();
            }
        }

        @Test
        @DisplayName("only the five real statuses are accepted")
        void statusCheckHolds() throws SQLException {
            String url = migrated();
            try (Connection connection = open(url)) {
                Seeded seeded = seed(connection);

                for (String valid : List.of("PENDING", "APPROVED", "REJECTED", "SUPERSEDED", "FAILED")) {
                    insertProposal(connection, seeded, valid);
                }
                assertThat(count(connection, "select count(*) from ai_profile_proposals")).isEqualTo(5);

                assertThatThrownBy(() -> insertProposal(connection, seeded, "APPLIED"))
                        .describedAs("the constraint is widened by V15, not removed")
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertProposal(connection, seeded, "pending"))
                        .describedAs("statuses are stored exactly as the enum names them")
                        .isInstanceOf(SQLException.class);
            }
        }

        @Test
        @DisplayName("a decision must carry the time it was made")
        void reviewedCheckHolds() throws SQLException {
            String url = migrated();
            try (Connection connection = open(url)) {
                Seeded seeded = seed(connection);

                assertThatThrownBy(() -> run(connection,
                        "insert into ai_profile_proposals (id, candidate_id, resume_id, status, "
                                + "payload, engine, ai_assisted, row_version, created_at, updated_at) "
                                + "values ('" + UUID.randomUUID() + "', '" + seeded.profileId()
                                + "', '" + seeded.resumeId() + "', 'APPROVED', '{}', 'e', true, 0, "
                                + "current_timestamp, current_timestamp)"))
                        .describedAs("an APPROVED row with no review time is not a record of a decision")
                        .isInstanceOf(SQLException.class);
            }
        }

        @Test
        @DisplayName("a proposal that has never been reviewed may leave the reviewer empty")
        void pendingNeedsNoReviewer() throws SQLException {
            String url = migrated();
            try (Connection connection = open(url)) {
                Seeded seeded = seed(connection);
                insertProposal(connection, seeded, "PENDING");
                insertProposal(connection, seeded, "SUPERSEDED");

                assertThat(count(connection,
                        "select count(*) from ai_profile_proposals where reviewed_at is null"))
                        .isEqualTo(2);
            }
        }
    }
}
