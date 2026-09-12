package com.careerflux.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.careerflux.user.UserRole;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * V16 (the four actors) and V17 (the session watermark) against databases that
 * already hold every kind of account the old role model could store.
 *
 * <p>The rest of the suite proves the migrations apply to an empty schema. What
 * an operator needs to know is different: that running them against a college's
 * live database maps every existing account onto exactly one of the four roles,
 * never gives the department-scoped role the name the college's administrator is
 * about to take, loses nobody, and leaves every sign-in working.
 *
 * <p>Each test builds its own H2 instance, migrates it to V15, writes real rows,
 * and only then applies V16 and V17. No Spring context and no shared state.
 */
class FourActorRoleMigrationTest {

    private static final String BEFORE_ROLES = "15";
    private static final String LATEST = "17";
    private static final String PASSWORD = "Migrated123!";

    /** Cost 4 is the minimum: this checks that a hash survives, not how strong it is. */
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

    private static String freshDatabase() {
        return "jdbc:h2:mem:roles-" + UUID.randomUUID()
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

    private static String roleOf(Connection connection, UUID userId) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select role from users where id = '" + userId + "'")) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    /** Everything on a user row except the role, which is the one thing V16 may change. */
    private static Map<UUID, List<String>> everythingButRole(Connection connection) throws SQLException {
        Map<UUID, List<String>> rows = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select id, email, password_hash, status, "
                     + "institution_id, department_id, batch_id, email_verified from users order by email")) {
            while (result.next()) {
                rows.put(UUID.fromString(result.getString("id")), List.of(
                        String.valueOf(result.getString("email")),
                        String.valueOf(result.getString("password_hash")),
                        String.valueOf(result.getString("status")),
                        String.valueOf(result.getString("institution_id")),
                        String.valueOf(result.getString("department_id")),
                        String.valueOf(result.getString("batch_id")),
                        String.valueOf(result.getBoolean("email_verified"))));
            }
        }
        return rows;
    }

    private static void insertUser(Connection connection, UUID id, String email, String role,
                                   UUID institution, UUID department, String hash) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into users (id, email, password_hash, role, status, institution_id, department_id, "
                        + "email_verified, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', ?, ?, true, current_timestamp, current_timestamp)")) {
            insert.setObject(1, id);
            insert.setString(2, email);
            insert.setString(3, hash);
            insert.setString(4, role);
            insert.setObject(5, institution);
            insert.setObject(6, department);
            insert.executeUpdate();
        }
    }

    /** One account for every role the old model could store, plus the scope rows staff hold. */
    private record Seeded(UUID institution, UUID department, UUID student, UUID departmentScoped,
                          UUID officer, UUID collegeAdmin, UUID platformAdmin) {
    }

    private static Seeded seed(Connection connection) throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID department = UUID.randomUUID();
        run(connection, "insert into institutions (id, name, slug, status, created_at, updated_at) values ('"
                + institution + "', 'Migration College', 'migration-" + institution
                + "', 'ACTIVE', current_timestamp, current_timestamp)");
        run(connection, "insert into departments (id, institution_id, name, code, created_at, updated_at) "
                + "values ('" + department + "', '" + institution
                + "', 'Computer Science', 'CSE', current_timestamp, current_timestamp)");

        String hash = ENCODER.encode(PASSWORD);
        Seeded seeded = new Seeded(institution, department, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        insertUser(connection, seeded.student(), "student@migration.test", "STUDENT", institution, department, hash);
        // The old name for the department-scoped role. The name this row
        // carries is exactly the one the college's administrator is about to take.
        insertUser(connection, seeded.departmentScoped(), "coordinator@migration.test", "PLACEMENT_COORDINATOR",
                institution, department, hash);
        insertUser(connection, seeded.officer(), "officer@migration.test", "PLACEMENT_OFFICER",
                institution, null, hash);
        insertUser(connection, seeded.collegeAdmin(), "admin@migration.test", "COLLEGE_ADMIN",
                institution, null, hash);
        insertUser(connection, seeded.platformAdmin(), "operator@migration.test", "PLATFORM_ADMIN",
                null, null, hash);

        run(connection, "insert into staff_scopes (id, user_id, institution_id, scope_type, department_id, "
                + "created_at) values ('" + UUID.randomUUID() + "', '" + seeded.departmentScoped() + "', '"
                + institution + "', 'DEPARTMENT', '" + department + "', current_timestamp)");
        // Honoured by nothing after the migration, but still data: V16 must not
        // delete it.
        run(connection, "insert into staff_scopes (id, user_id, institution_id, scope_type, created_at) "
                + "values ('" + UUID.randomUUID() + "', '" + seeded.departmentScoped() + "', '"
                + institution + "', 'INSTITUTION', current_timestamp)");

        // A raw reset token, as stored before V17.
        run(connection, "update users set password_reset_token = 'raw-token-stored-before-v17', "
                + "password_reset_expires_at = current_timestamp where id = '" + seeded.student() + "'");
        return seeded;
    }

    private static String migratedWithSeed(Seeded[] out) throws SQLException {
        String url = freshDatabase();
        flywayFor(url, BEFORE_ROLES).migrate();
        try (Connection connection = open(url)) {
            out[0] = seed(connection);
        }
        var result = flywayFor(url, LATEST).migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted)
                .describedAs("V16 and V17 should have been the outstanding ones")
                .isEqualTo(2);
        return url;
    }

    // --------------------------------------------------------------- mapping

    @Nested
    @DisplayName("mapping the stored roles")
    class Mapping {

        @Test
        @DisplayName("every old role lands on exactly the role the four-actor model names")
        void everyOldRoleMapsOnce() throws SQLException {
            Seeded[] seeded = new Seeded[1];
            String url = migratedWithSeed(seeded);

            try (Connection connection = open(url)) {
                assertThat(roleOf(connection, seeded[0].student())).isEqualTo("STUDENT");
                assertThat(roleOf(connection, seeded[0].departmentScoped())).isEqualTo("DEPARTMENT_COORDINATOR");
                assertThat(roleOf(connection, seeded[0].officer())).isEqualTo("PLACEMENT_COORDINATOR");
                assertThat(roleOf(connection, seeded[0].collegeAdmin())).isEqualTo("PLACEMENT_COORDINATOR");
                assertThat(roleOf(connection, seeded[0].platformAdmin())).isEqualTo("PORTAL_ADMIN");
            }
        }

        @Test
        @DisplayName("the department-scoped account is renamed away before the name is reused, so it never "
                + "becomes the college's administrator")
        void noCollisionOnTheReusedName() throws SQLException {
            Seeded[] seeded = new Seeded[1];
            String url = migratedWithSeed(seeded);

            try (Connection connection = open(url)) {
                assertThat(count(connection, "select count(*) from users where role = 'PLACEMENT_COORDINATOR'"))
                        .describedAs("exactly the officer and the college administrator")
                        .isEqualTo(2);
                assertThat(count(connection, "select count(*) from users where role = 'PLACEMENT_COORDINATOR' "
                        + "and id = '" + seeded[0].departmentScoped() + "'"))
                        .describedAs("the old department-scoped account must not gain the institution-wide role")
                        .isZero();
            }
        }

        @Test
        @DisplayName("every migrated role is one the application can load")
        void everyStoredRoleIsAKnownEnum() throws SQLException {
            Seeded[] seeded = new Seeded[1];
            String url = migratedWithSeed(seeded);

            try (Connection connection = open(url);
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select role from users")) {
                int seen = 0;
                while (rows.next()) {
                    // An unknown value here would fail every sign-in for that
                    // account the moment Hibernate tried to map it.
                    UserRole.valueOf(rows.getString(1));
                    seen++;
                }
                assertThat(seen).isEqualTo(5);
            }
        }
    }

    // ------------------------------------------------------- nothing else moves

    @Nested
    @DisplayName("everything but the role")
    class NothingElseMoves {

        @Test
        @DisplayName("no account is added, lost, or changed anywhere except its role")
        void accountsSurviveIntact() throws SQLException {
            String url = freshDatabase();
            flywayFor(url, BEFORE_ROLES).migrate();
            Map<UUID, List<String>> before;
            try (Connection connection = open(url)) {
                seed(connection);
                before = everythingButRole(connection);
            }

            flywayFor(url, LATEST).migrate();

            try (Connection connection = open(url)) {
                assertThat(everythingButRole(connection)).isEqualTo(before);
            }
        }

        @Test
        @DisplayName("every account can still prove its password afterwards")
        void signInSurvives() throws SQLException {
            Seeded[] seeded = new Seeded[1];
            String url = migratedWithSeed(seeded);

            try (Connection connection = open(url);
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select password_hash from users")) {
                int checked = 0;
                while (rows.next()) {
                    assertThat(ENCODER.matches(PASSWORD, rows.getString(1))).isTrue();
                    checked++;
                }
                assertThat(checked).isEqualTo(5);
            }
        }

        @Test
        @DisplayName("staff scope rows are left exactly as they were, the unhonoured one included")
        void scopesAreLeftAlone() throws SQLException {
            Seeded[] seeded = new Seeded[1];
            String url = migratedWithSeed(seeded);

            try (Connection connection = open(url)) {
                assertThat(count(connection, "select count(*) from staff_scopes")).isEqualTo(2);
                assertThat(count(connection, "select count(*) from staff_scopes where scope_type = 'INSTITUTION' "
                        + "and user_id = '" + seeded[0].departmentScoped() + "'"))
                        .isEqualTo(1);
            }
        }
    }

    // ------------------------------------------------------------ constraints

    @Nested
    @DisplayName("the constraints V16 adds")
    class Constraints {

        @Test
        @DisplayName("a retired role name can no longer be stored")
        void retiredNamesAreRefused() throws SQLException {
            Seeded[] seeded = new Seeded[1];
            String url = migratedWithSeed(seeded);

            try (Connection connection = open(url)) {
                for (String retired : List.of("PLACEMENT_OFFICER", "COLLEGE_ADMIN", "PLATFORM_ADMIN",
                        "placement_coordinator", "HOD")) {
                    assertThatThrownBy(() -> insertUser(connection, UUID.randomUUID(),
                            retired.toLowerCase() + "@refused.test", retired, seeded[0].institution(), null, "x"))
                            .describedAs("%s must be refused", retired)
                            .isInstanceOf(SQLException.class);
                }
            }
        }

        @Test
        @DisplayName("only the portal administrator may belong to no college")
        void institutionRuleUsesTheNewName() throws SQLException {
            Seeded[] seeded = new Seeded[1];
            String url = migratedWithSeed(seeded);

            try (Connection connection = open(url)) {
                insertUser(connection, UUID.randomUUID(), "second-operator@migration.test", "PORTAL_ADMIN",
                        null, null, "x");
                for (String role : List.of("STUDENT", "DEPARTMENT_COORDINATOR", "PLACEMENT_COORDINATOR")) {
                    assertThatThrownBy(() -> insertUser(connection, UUID.randomUUID(),
                            role.toLowerCase() + "@nowhere.test", role, null, null, "x"))
                            .describedAs("%s without a college must be refused", role)
                            .isInstanceOf(SQLException.class);
                }
            }
        }

        @Test
        @DisplayName("a role the migration does not know stops it, rather than surviving as an account "
                + "nobody defined")
        void unknownRoleFailsLoudly() throws SQLException {
            String url = freshDatabase();
            flywayFor(url, BEFORE_ROLES).migrate();
            try (Connection connection = open(url)) {
                Seeded seeded = seed(connection);
                insertUser(connection, UUID.randomUUID(), "legacy@migration.test", "LEGACY_ROLE",
                        seeded.institution(), null, "x");
            }

            assertThatThrownBy(() -> flywayFor(url, LATEST).migrate())
                    .isInstanceOf(FlywayException.class);
        }
    }

    // -------------------------------------------------------------------- V17

    @Nested
    @DisplayName("V17")
    class SessionWatermark {

        @Test
        @DisplayName("clears reset tokens stored raw, which could never match a digest")
        void rawResetTokensAreCleared() throws SQLException {
            Seeded[] seeded = new Seeded[1];
            String url = migratedWithSeed(seeded);

            try (Connection connection = open(url)) {
                assertThat(count(connection, "select count(*) from users where password_reset_token is not null "
                        + "or password_reset_expires_at is not null")).isZero();
            }
        }

        @Test
        @DisplayName("adds the watermark empty, so every existing session stays valid")
        void watermarkStartsEmpty() throws SQLException {
            Seeded[] seeded = new Seeded[1];
            String url = migratedWithSeed(seeded);

            try (Connection connection = open(url)) {
                assertThat(count(connection, "select count(*) from users where sessions_valid_after is null"))
                        .isEqualTo(5);
            }
        }

        @Test
        @DisplayName("an empty database migrates straight to the latest version")
        void freshDatabaseMigrates() throws SQLException {
            String url = freshDatabase();
            var result = flywayFor(url, LATEST).migrate();

            assertThat(result.success).isTrue();
            assertThat(result.targetSchemaVersion).isEqualTo(LATEST);
            try (Connection connection = open(url)) {
                assertThat(count(connection, "select count(*) from users")).isZero();
            }
        }
    }
}
