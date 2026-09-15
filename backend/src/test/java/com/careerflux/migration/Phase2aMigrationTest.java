package com.careerflux.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * V18 to V21 against databases that already hold the rows they change.
 *
 * <p>The rest of the suite proves these migrations apply to an empty schema.
 * What an operator needs to know is what they do to a live college's data:
 * every audit row learns its college without losing its history, every CGPA
 * lands in exactly one of its two new homes, and the C-family skill row moves
 * only when it can do so without colliding.
 *
 * <p>Each test builds its own database, migrates it to V17 (or the version just
 * before the one under test), writes real rows, and only then applies the
 * migration. By default that is a fresh in-memory H2 per test. Setting
 * {@code -Dphase2a.pgUrl=jdbc:postgresql://host:port/db} (with
 * {@code phase2a.pgUser} and {@code phase2a.pgPassword}) runs the same
 * assertions against PostgreSQL instead, each test in a schema of its own.
 */
class Phase2aMigrationTest {

    private static final String PG_URL = System.getProperty("phase2a.pgUrl");

    private record Db(String url, String user, String password, String schema) {
    }

    private static Db fresh() {
        if (PG_URL == null || PG_URL.isBlank()) {
            return new Db("jdbc:h2:mem:p2a-" + UUID.randomUUID()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                    "sa", "", null);
        }
        String schema = "p2a_" + UUID.randomUUID().toString().replace("-", "");
        String separator = PG_URL.contains("?") ? "&" : "?";
        return new Db(PG_URL + separator + "currentSchema=" + schema,
                System.getProperty("phase2a.pgUser", "postgres"),
                System.getProperty("phase2a.pgPassword", ""), schema);
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

    /** One row's columns, as strings, in the order asked for. */
    private static List<String> row(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).describedAs("a row for: " + sql).isTrue();
            List<String> values = new ArrayList<>();
            for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                Object value = result.getObject(column);
                values.add(value == null ? null : value.toString());
            }
            return values;
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getLong(1) : -1;
        }
    }

    // ---------------------------------------------------------------- seeding

    private static UUID institution(Connection connection) throws SQLException {
        UUID id = UUID.randomUUID();
        run(connection, "insert into institutions (id, name, slug, status, created_at, updated_at) values ('"
                + id + "', 'Migration College', 'migration-" + id + "', 'ACTIVE', current_timestamp, current_timestamp)");
        return id;
    }

    private static UUID user(Connection connection, String email, String role, UUID institution)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into users (id, email, password_hash, role, status, institution_id, email_verified, "
                        + "created_at, updated_at) values (?, ?, 'not-a-real-hash', ?, 'ACTIVE', ?, true, "
                        + "current_timestamp, current_timestamp)")) {
            insert.setObject(1, id);
            insert.setString(2, email);
            insert.setString(3, role);
            insert.setObject(4, institution);
            insert.executeUpdate();
        }
        return id;
    }

    private static UUID profile(Connection connection, UUID user, UUID institution) throws SQLException {
        UUID id = UUID.randomUUID();
        run(connection, "insert into candidate_profiles (id, user_id, institution_id, onboarding_stage, "
                + "profile_completeness, cgpa_scale, created_at, updated_at) values ('" + id + "', '" + user
                + "', '" + institution + "', 'COMPLETE', 0, 10.00, current_timestamp, current_timestamp)");
        return id;
    }

    private static UUID audit(Connection connection, String actor, UUID actorUserId, String action,
                              String entityType, String entityId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into audit_events (id, actor, actor_user_id, action, entity_type, entity_id, detail, "
                        + "occurred_at) values (?, ?, ?, ?, ?, ?, 'detail', current_timestamp)")) {
            insert.setObject(1, id);
            insert.setString(2, actor);
            insert.setObject(3, actorUserId);
            insert.setString(4, action);
            insert.setString(5, entityType);
            insert.setString(6, entityId);
            insert.executeUpdate();
        }
        return id;
    }

    private static UUID skill(Connection connection, String canonical, String slug) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into skills (id, canonical_name, slug, category, created_at) "
                        + "values (?, ?, ?, 'LANGUAGE', current_timestamp)")) {
            insert.setObject(1, id);
            insert.setString(2, canonical);
            insert.setString(3, slug);
            insert.executeUpdate();
        }
        return id;
    }

    private static void alias(Connection connection, UUID skill, String alias) throws SQLException {
        run(connection, "insert into skill_aliases (id, skill_id, alias) values ('" + UUID.randomUUID()
                + "', '" + skill + "', '" + alias + "')");
    }

    private static String slugOf(Connection connection, UUID skill) throws SQLException {
        return row(connection, "select slug from skills where id = '" + skill + "'").get(0);
    }

    // -------------------------------------------------------------------- V18

    @Nested
    @DisplayName("V18: audit rows learn their college and the actor's role")
    class AuditScope {

        @Test
        @DisplayName("each row is attributed from data already in it, and nothing is rewritten or lost")
        void everyRowIsAttributed() throws SQLException {
            Db db = fresh();
            migrate(db, "17");
            UUID college;
            UUID coordinator;
            UUID student;
            UUID admin;
            UUID staffAction;
            UUID registered;
            UUID resetAsked;
            UUID scheduled;
            UUID sourceReviewed;
            try (Connection connection = open(db)) {
                college = institution(connection);
                coordinator = user(connection, "pc@migration.test", "PLACEMENT_COORDINATOR", college);
                student = user(connection, "student@migration.test", "STUDENT", college);
                admin = user(connection, "operator@migration.test", "PORTAL_ADMIN", null);

                staffAction = audit(connection, "pc@migration.test", coordinator, "DEPARTMENT_CREATED",
                        "Department", UUID.randomUUID().toString());
                registered = audit(connection, "student@migration.test", null, "USER_REGISTERED",
                        "User", student.toString());
                resetAsked = audit(connection, "student@migration.test", null, "PASSWORD_RESET_REQUESTED",
                        "User", student.toString());
                scheduled = audit(connection, "scheduler", null, "SOURCE_STATE_CHANGED",
                        "JobSource", UUID.randomUUID().toString());
                sourceReviewed = audit(connection, "operator@migration.test", null, "SOURCE_REGISTERED",
                        "JobSource", UUID.randomUUID().toString());
            }

            migrate(db, "18");

            try (Connection connection = open(db)) {
                String columns = "select actor_user_id, actor_role, institution_id, actor from audit_events where id = '";
                // A person with a session: their college and role.
                assertThat(row(connection, columns + staffAction + "'"))
                        .containsExactly(coordinator.toString(), "PLACEMENT_COORDINATOR", college.toString(),
                                "pc@migration.test");
                // Registered without a session: linked by the address, which is the account's.
                assertThat(row(connection, columns + registered + "'"))
                        .containsExactly(student.toString(), "STUDENT", college.toString(), "student@migration.test");
                // A reset request: the account's college, but nobody named as having asked.
                assertThat(row(connection, columns + resetAsked + "'"))
                        .containsExactly(null, null, college.toString(), "student@migration.test");
                // Scheduled work: the platform's, and nobody's.
                assertThat(row(connection, columns + scheduled + "'"))
                        .containsExactly(null, null, null, "scheduler");
                // The Portal Admin's own work: theirs, and no college's.
                assertThat(row(connection, columns + sourceReviewed + "'"))
                        .containsExactly(admin.toString(), "PORTAL_ADMIN", null, "operator@migration.test");

                assertThat(count(connection, "select count(*) from audit_events")).isEqualTo(5);
            }
        }
    }

    // -------------------------------------------------------------------- V19

    @Nested
    @DisplayName("V19: a student's own CGPA moves out of the college's slot")
    class ReportedCgpa {

        private UUID college;
        private UUID recorder;
        private UUID studentOwn;
        private UUID collegeRecorded;
        private UUID untouched;
        private String studentOwnRecordedAt;

        private Db seededAt18() throws SQLException {
            Db db = fresh();
            migrate(db, "18");
            try (Connection connection = open(db)) {
                college = institution(connection);
                recorder = user(connection, "recorder@migration.test", "PLACEMENT_COORDINATOR", college);
                studentOwn = profile(connection, user(connection, "a@migration.test", "STUDENT", college), college);
                collegeRecorded = profile(connection, user(connection, "b@migration.test", "STUDENT", college), college);
                untouched = profile(connection, user(connection, "c@migration.test", "STUDENT", college), college);

                run(connection, "update candidate_profiles set cgpa = 9.10, cgpa_source = 'STUDENT', "
                        + "cgpa_recorded_at = current_timestamp where id = '" + studentOwn + "'");
                run(connection, "update candidate_profiles set cgpa = 8.20, cgpa_source = 'INSTITUTION', "
                        + "cgpa_recorded_by = '" + recorder + "', cgpa_recorded_at = current_timestamp "
                        + "where id = '" + collegeRecorded + "'");
                studentOwnRecordedAt = row(connection, "select cgpa_recorded_at from candidate_profiles where id = '"
                        + studentOwn + "'").get(0);
            }
            return db;
        }

        @Test
        @DisplayName("a student's figure moves, value and time together, and the college's is not touched")
        void eachFigureLandsInItsOwnHome() throws SQLException {
            Db db = seededAt18();

            migrate(db, "19");

            try (Connection connection = open(db)) {
                String columns = "select cgpa, cgpa_source, cgpa_recorded_by, cgpa_recorded_at, reported_cgpa, "
                        + "reported_cgpa_recorded_at from candidate_profiles where id = '";
                List<String> own = row(connection, columns + studentOwn + "'");
                assertThat(own.subList(0, 4)).containsOnlyNulls();
                assertThat(new java.math.BigDecimal(own.get(4))).isEqualByComparingTo("9.10");
                assertThat(own.get(5)).isEqualTo(studentOwnRecordedAt);

                List<String> college = row(connection, columns + collegeRecorded + "'");
                assertThat(new java.math.BigDecimal(college.get(0))).isEqualByComparingTo("8.20");
                assertThat(college.get(1)).isEqualTo("INSTITUTION");
                assertThat(college.get(2)).isEqualTo(recorder.toString());
                assertThat(college.get(3)).isNotNull();
                assertThat(college.subList(4, 6)).containsOnlyNulls();

                assertThat(row(connection, columns + untouched + "'")).containsOnlyNulls();
            }
        }

        @Test
        @DisplayName("afterwards the college's slot cannot hold a student's figure, and the student's has bounds")
        void theNewShapeIsEnforced() throws SQLException {
            Db db = seededAt18();
            migrate(db, "19");

            try (Connection connection = open(db)) {
                assertThatThrownBy(() -> run(connection, "update candidate_profiles set cgpa = 5.00, "
                        + "cgpa_source = 'STUDENT' where id = '" + untouched + "'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> run(connection, "update candidate_profiles set reported_cgpa = 11.00 "
                        + "where id = '" + untouched + "'"))
                        .isInstanceOf(SQLException.class);
                run(connection, "update candidate_profiles set reported_cgpa = 7.50, "
                        + "reported_cgpa_recorded_at = current_timestamp where id = '" + untouched + "'");
            }
        }
    }

    // -------------------------------------------------------------------- V20

    @Nested
    @DisplayName("V20: a student's private skills")
    class PrivateSkills {

        @Test
        @DisplayName("are kept once per spelling, per student")
        void oneEntryPerSpelling() throws SQLException {
            Db db = fresh();
            migrate(db, "20");
            try (Connection connection = open(db)) {
                UUID college = institution(connection);
                UUID candidate = profile(connection, user(connection, "s@migration.test", "STUDENT", college), college);
                String insert = "insert into candidate_custom_skills (id, candidate_id, name, name_key, origin, "
                        + "created_at) values ('%s', '" + candidate + "', '%s', 'power-bi', 'MANUAL', current_timestamp)";

                run(connection, insert.formatted(UUID.randomUUID(), "Power BI"));
                assertThatThrownBy(() -> run(connection, insert.formatted(UUID.randomUUID(), "power bi")))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    // -------------------------------------------------------------------- V21

    @Nested
    @DisplayName("V21: C# and C++ each get their own slug")
    class CFamily {

        @Test
        @DisplayName("where C# took `c`, it moves to `c-sharp` and keeps its aliases and every link")
        void cSharpMoves() throws SQLException {
            Db db = fresh();
            migrate(db, "20");
            UUID csharp;
            try (Connection connection = open(db)) {
                csharp = skill(connection, "C#", "c");
                alias(connection, csharp, "csharp");
                alias(connection, csharp, "c-sharp");
                UUID college = institution(connection);
                UUID candidate = profile(connection, user(connection, "s@migration.test", "STUDENT", college), college);
                run(connection, "insert into candidate_skills (id, candidate_id, skill_id, origin, created_at) values ('"
                        + UUID.randomUUID() + "', '" + candidate + "', '" + csharp + "', 'MANUAL', current_timestamp)");
            }

            migrate(db, "21");

            try (Connection connection = open(db)) {
                assertThat(slugOf(connection, csharp)).isEqualTo("c-sharp");
                assertThat(count(connection, "select count(*) from skill_aliases where skill_id = '" + csharp + "'"))
                        .isEqualTo(2);
                assertThat(count(connection, "select count(*) from candidate_skills where skill_id = '" + csharp + "'"))
                        .isEqualTo(1);
                assertThat(count(connection, "select count(*) from skills where slug = 'c'")).isZero();
            }
        }

        @Test
        @DisplayName("where C++ took `c`, it moves to `c-plus-plus`")
        void cPlusPlusMoves() throws SQLException {
            Db db = fresh();
            migrate(db, "20");
            UUID cpp;
            try (Connection connection = open(db)) {
                cpp = skill(connection, "C++", "c");
                alias(connection, cpp, "cpp");
            }

            migrate(db, "21");

            try (Connection connection = open(db)) {
                assertThat(slugOf(connection, cpp)).isEqualTo("c-plus-plus");
            }
        }

        @Test
        @DisplayName("when the slug it needs is already taken, nothing moves and nothing fails")
        void aTakenSlugIsLeftAlone() throws SQLException {
            Db db = fresh();
            migrate(db, "20");
            UUID csharp;
            UUID typed;
            try (Connection connection = open(db)) {
                csharp = skill(connection, "C#", "c");
                typed = skill(connection, "C Sharp", "c-sharp");
            }

            migrate(db, "21");

            try (Connection connection = open(db)) {
                assertThat(slugOf(connection, csharp)).isEqualTo("c");
                assertThat(slugOf(connection, typed)).isEqualTo("c-sharp");
            }
        }

        @Test
        @DisplayName("a row with any other name keeps `c`")
        void otherNamesAreNotMoved() throws SQLException {
            Db db = fresh();
            migrate(db, "20");
            UUID other;
            try (Connection connection = open(db)) {
                other = skill(connection, "C", "c");
            }

            migrate(db, "21");

            try (Connection connection = open(db)) {
                assertThat(slugOf(connection, other)).isEqualTo("c");
            }
        }
    }
}
