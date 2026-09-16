package com.careerflux.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * V22 to V24 against databases that already hold data (Phase 2B).
 *
 * <p>By default each test migrates a fresh in-memory H2 database. Setting
 * {@code -Dphase2b.pgUrl=jdbc:postgresql://host:port/db} (with
 * {@code phase2b.pgUser} and {@code phase2b.pgPassword}) runs the same
 * assertions against PostgreSQL, each test in a schema of its own.
 */
class Phase2bMigrationTest {

    private static final String PG_URL = System.getProperty("phase2b.pgUrl");

    private record Db(String url, String user, String password, String schema) {
    }

    private static Db fresh() {
        if (PG_URL == null || PG_URL.isBlank()) {
            return new Db("jdbc:h2:mem:p2b-" + UUID.randomUUID()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                    "sa", "", null);
        }
        String schema = "p2b_" + UUID.randomUUID().toString().replace("-", "");
        String separator = PG_URL.contains("?") ? "&" : "?";
        return new Db(PG_URL + separator + "currentSchema=" + schema,
                System.getProperty("phase2b.pgUser", "postgres"),
                System.getProperty("phase2b.pgPassword", ""), schema);
    }

    private static void migrate(Db db, String target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(db.url(), db.user(), db.password())
                .locations("classpath:db/migration")
                .target(target);
        if (db.schema() != null) {
            configuration = configuration.schemas(db.schema()).createSchemas(true);
        }
        assertThat(configuration.load().migrate().success).isTrue();
    }

    private static Connection open(Db db) throws SQLException {
        return DriverManager.getConnection(db.url(), db.user(), db.password());
    }

    private static void run(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String single(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            Object value = result.getObject(1);
            return value == null ? null : value.toString();
        }
    }

    private static UUID student(Connection connection) throws SQLException {
        UUID institution = UUID.randomUUID();
        run(connection, "insert into institutions (id, name, slug, status, created_at, updated_at) values ('"
                + institution + "', 'Migration College', 'mig-" + institution + "', 'ACTIVE', current_timestamp, "
                + "current_timestamp)");
        UUID user = UUID.randomUUID();
        run(connection, "insert into users (id, email, password_hash, role, status, institution_id, email_verified, "
                + "created_at, updated_at) values ('" + user + "', 's-" + user + "@migration.test', 'x', 'STUDENT', "
                + "'ACTIVE', '" + institution + "', true, current_timestamp, current_timestamp)");
        return user;
    }

    private static UUID profile(Connection connection, UUID user) throws SQLException {
        UUID profile = UUID.randomUUID();
        String institution = single(connection, "select institution_id from users where id = '" + user + "'");
        run(connection, "insert into candidate_profiles (id, user_id, institution_id, onboarding_stage, "
                + "profile_completeness, cgpa_scale, created_at, updated_at) values ('" + profile + "', '" + user
                + "', '" + institution + "', 'COMPLETE', 0, 10.00, current_timestamp, current_timestamp)");
        return profile;
    }

    // -------------------------------------------------------------------- V22

    @Nested
    @DisplayName("V22: notices and the consent ledger")
    class ConsentLedger {

        private UUID notice(Connection connection) throws SQLException {
            UUID id = UUID.randomUUID();
            run(connection, "insert into notice_versions (id, kind, version, effective_from, checksum, body, "
                    + "placeholder, created_at) values ('" + id + "', 'AI_PROCESSING', 'v-" + id + "', "
                    + "current_timestamp, '" + "a".repeat(64) + "', 'text', true, current_timestamp)");
            return id;
        }

        @Test
        @DisplayName("each consent row is exactly one event: an acceptance or a withdrawal, never both or neither")
        void oneEventPerRow() throws SQLException {
            Db db = fresh();
            migrate(db, "22");
            try (Connection connection = open(db)) {
                UUID user = student(connection);
                UUID notice = notice(connection);
                String insert = "insert into consent_records (id, user_id, purpose, notice_version_id, accepted_at, "
                        + "withdrawn_at, source, created_at) values ('%s', '" + user + "', 'AI_PROCESSING', '"
                        + notice + "', %s, %s, 'SETTINGS', current_timestamp)";

                run(connection, insert.formatted(UUID.randomUUID(), "current_timestamp", "null"));
                run(connection, insert.formatted(UUID.randomUUID(), "null", "current_timestamp"));
                assertThatThrownBy(() -> run(connection,
                        insert.formatted(UUID.randomUUID(), "current_timestamp", "current_timestamp")))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> run(connection, insert.formatted(UUID.randomUUID(), "null", "null")))
                        .isInstanceOf(SQLException.class);
            }
        }

        @Test
        @DisplayName("a notice version is published once, and cannot be removed while anyone agreed to it")
        void noticesAreFixed() throws SQLException {
            Db db = fresh();
            migrate(db, "22");
            try (Connection connection = open(db)) {
                UUID user = student(connection);
                UUID notice = notice(connection);
                String version = single(connection, "select version from notice_versions where id = '" + notice + "'");
                assertThatThrownBy(() -> run(connection, "insert into notice_versions (id, kind, version, "
                        + "effective_from, checksum, body, placeholder, created_at) values ('" + UUID.randomUUID()
                        + "', 'AI_PROCESSING', '" + version + "', current_timestamp, '" + "b".repeat(64)
                        + "', 'other text', true, current_timestamp)")).isInstanceOf(SQLException.class);

                run(connection, "insert into consent_records (id, user_id, purpose, notice_version_id, accepted_at, "
                        + "source, created_at) values ('" + UUID.randomUUID() + "', '" + user + "', 'AI_PROCESSING', '"
                        + notice + "', current_timestamp, 'SETTINGS', current_timestamp)");
                assertThatThrownBy(() -> run(connection, "delete from notice_versions where id = '" + notice + "'"))
                        .isInstanceOf(SQLException.class);
            }
        }

        @Test
        @DisplayName("only the three known purposes can be recorded")
        void purposesAreChecked() throws SQLException {
            Db db = fresh();
            migrate(db, "22");
            try (Connection connection = open(db)) {
                UUID user = student(connection);
                UUID notice = notice(connection);
                assertThatThrownBy(() -> run(connection, "insert into consent_records (id, user_id, purpose, "
                        + "notice_version_id, accepted_at, source, created_at) values ('" + UUID.randomUUID() + "', '"
                        + user + "', 'MARKETING', '" + notice + "', current_timestamp, 'SETTINGS', current_timestamp)"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    // -------------------------------------------------------------------- V23

    @Nested
    @DisplayName("V23: resume text retention")
    class ResumeText {

        @Test
        @DisplayName("existing resumes keep their text; the migration removes nothing")
        void nothingIsRemoved() throws SQLException {
            Db db = fresh();
            migrate(db, "22");
            UUID resume = UUID.randomUUID();
            try (Connection connection = open(db)) {
                UUID profile = profile(connection, student(connection));
                run(connection, "insert into resumes (id, candidate_id, original_filename, storage_path, "
                        + "extracted_text, parse_status, is_active, uploaded_at) values ('" + resume + "', '"
                        + profile + "', 'cv.pdf', 'x/y.pdf', 'the whole resume', 'PARSED', true, current_timestamp)");
            }

            migrate(db, "23");

            try (Connection connection = open(db)) {
                assertThat(single(connection, "select extracted_text from resumes where id = '" + resume + "'"))
                        .isEqualTo("the whole resume");
                assertThat(single(connection, "select text_dropped_at from resumes where id = '" + resume + "'"))
                        .isNull();
            }
        }
    }

    // -------------------------------------------------------------------- V24

    @Nested
    @DisplayName("V24: erasure requests")
    class Erasures {

        private static String insert(UUID subject, String status, String openSubject) {
            return "insert into account_erasures (id, subject_user_id, open_subject_user_id, requested_by_role, "
                    + "status, requested_at, grace_ends_at) values ('" + UUID.randomUUID() + "', '" + subject + "', "
                    + openSubject + ", 'STUDENT', '" + status + "', current_timestamp, current_timestamp)";
        }

        @Test
        @DisplayName("an account can have one open request, and any number of closed ones")
        void oneOpenRequestPerAccount() throws SQLException {
            Db db = fresh();
            migrate(db, "24");
            try (Connection connection = open(db)) {
                UUID subject = UUID.randomUUID();
                run(connection, insert(subject, "CANCELLED", "null"));
                run(connection, insert(subject, "COMPLETED", "null"));
                run(connection, insert(subject, "GRACE_PERIOD", "'" + subject + "'"));
                assertThatThrownBy(() -> run(connection, insert(subject, "FAILED", "'" + subject + "'")))
                        .isInstanceOf(SQLException.class);
            }
        }

        @Test
        @DisplayName("whether a request is open always agrees with its status")
        void openMatchesStatus() throws SQLException {
            Db db = fresh();
            migrate(db, "24");
            try (Connection connection = open(db)) {
                UUID subject = UUID.randomUUID();
                assertThatThrownBy(() -> run(connection, insert(subject, "COMPLETED", "'" + subject + "'")))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> run(connection, insert(subject, "GRACE_PERIOD", "null")))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> run(connection, insert(subject, "DELETED", "null")))
                        .isInstanceOf(SQLException.class);
            }
        }
    }
}
