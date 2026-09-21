package com.careerflux.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.careerflux.shortlist.domain.PlacementStage;
import com.careerflux.shortlist.domain.PlacementStage.ActorKind;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * V25: the placement stage vocabulary, enforced by the database (Phase 3, D-7).
 *
 * <p>Everything here goes through raw JDBC on purpose. The application cannot
 * write a bad stage, because every write path holds the enum; what V25 adds is
 * that nothing else can either. So the tests hand the database the strings the
 * enum would never produce and expect the CHECK, not Java, to refuse them.
 *
 * <p>By default each test migrates a fresh in-memory H2 database. Setting
 * {@code -Dphase2b.pgUrl=jdbc:postgresql://host:port/db} (with
 * {@code phase2b.pgUser} and {@code phase2b.pgPassword}) runs the same
 * assertions against PostgreSQL, each test in a schema of its own.
 */
class PlacementStageConstraintMigrationTest {

    private static final String PG_URL = System.getProperty("phase2b.pgUrl");

    /** Written out rather than derived from the enum, so the enum can be checked against it. */
    private static final List<String> CANONICAL_STAGES = List.of(
            "SHORTLISTED", "INVITED", "INTERESTED", "SELECTED", "NOT_PROCEEDING", "DECLINED");
    private static final List<String> CANONICAL_ACTOR_KINDS = List.of("STAFF", "STUDENT");

    /** Not a stage, the right word in the wrong case, and nothing at all. */
    private static final List<String> INVALID = List.of("PROMOTED", "shortlisted", "");

    private record Db(String url, String user, String password, String schema) {
    }

    private static Db fresh() {
        if (PG_URL == null || PG_URL.isBlank()) {
            return new Db("jdbc:h2:mem:d7-" + UUID.randomUUID()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                    "sa", "", null);
        }
        String schema = "d7_" + UUID.randomUUID().toString().replace("-", "");
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
        var result = configuration.load().migrate();
        assertThat(result.success).isTrue();
        assertThat(result.targetSchemaVersion).isEqualTo(target);
    }

    private static Connection open(Db db) throws SQLException {
        return DriverManager.getConnection(db.url(), db.user(), db.password());
    }

    private static void run(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                StringBuilder row = new StringBuilder();
                for (int column = 1; column <= columns; column++) {
                    row.append(column == 1 ? "" : "|").append(result.getString(column));
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    private static String single(Connection connection, String sql) throws SQLException {
        List<String> rows = rows(connection, sql);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    /** A college with one drive in it. Returns the requirement id. */
    private static UUID requirement(Connection connection) throws SQLException {
        UUID institution = UUID.randomUUID();
        run(connection, "insert into institutions (id, name, slug, status, created_at, updated_at) values ('"
                + institution + "', 'Migration College', 'mig-" + institution + "', 'ACTIVE', current_timestamp, "
                + "current_timestamp)");
        UUID requirement = UUID.randomUUID();
        run(connection, "insert into company_requirements (id, institution_id, company_name, role_title, work_mode, "
                + "status, created_at, updated_at) values ('" + requirement + "', '" + institution + "', "
                + "'Example Systems', 'Graduate Engineer', 'ONSITE', 'OPEN', current_timestamp, current_timestamp)");
        return requirement;
    }

    /** A student in the requirement's college, with a profile. Returns the profile id. */
    private static UUID candidate(Connection connection, UUID requirement) throws SQLException {
        String institution = single(connection,
                "select institution_id from company_requirements where id = '" + requirement + "'");
        UUID user = UUID.randomUUID();
        run(connection, "insert into users (id, email, password_hash, role, status, institution_id, email_verified, "
                + "created_at, updated_at) values ('" + user + "', 's-" + user + "@migration.test', 'x', 'STUDENT', "
                + "'ACTIVE', '" + institution + "', true, current_timestamp, current_timestamp)");
        UUID profile = UUID.randomUUID();
        run(connection, "insert into candidate_profiles (id, user_id, institution_id, onboarding_stage, "
                + "profile_completeness, cgpa_scale, created_at, updated_at) values ('" + profile + "', '" + user
                + "', '" + institution + "', 'COMPLETE', 0, 10.00, current_timestamp, current_timestamp)");
        return profile;
    }

    /** Inserts a shortlist row with the stage exactly as given, quotes and all. */
    private static UUID shortlist(Connection connection, UUID requirement, String stage) throws SQLException {
        UUID id = UUID.randomUUID();
        run(connection, "insert into company_requirement_shortlists (id, requirement_id, candidate_id, created_at, "
                + "stage) values ('" + id + "', '" + requirement + "', '" + candidate(connection, requirement)
                + "', current_timestamp, " + literal(stage) + ")");
        return id;
    }

    private static void change(Connection connection, UUID shortlist, String from, String to, String actorKind)
            throws SQLException {
        run(connection, "insert into placement_stage_changes (id, shortlist_id, from_stage, to_stage, actor_kind, "
                + "occurred_at) values ('" + UUID.randomUUID() + "', '" + shortlist + "', " + literal(from) + ", "
                + literal(to) + ", " + literal(actorKind) + ", current_timestamp)");
    }

    private static String literal(String value) {
        return value == null ? "null" : "'" + value.replace("'", "''") + "'";
    }

    private static List<String> enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    // ---------------------------------------------------------------- upgrade

    @Nested
    @DisplayName("upgrading a database that already has a drive in progress")
    class Upgrade {

        @Test
        @DisplayName("V25 applies over rows in every stage and leaves every one of them exactly as it was")
        void existingRowsSurvive() throws SQLException {
            Db db = fresh();
            migrate(db, "24");
            String shortlistsQuery = "select id, stage, stage_changed_at, row_version from "
                    + "company_requirement_shortlists order by id";
            String changesQuery = "select id, shortlist_id, from_stage, to_stage, actor_kind, occurred_at from "
                    + "placement_stage_changes order by id";
            List<String> shortlistsBefore;
            List<String> changesBefore;
            try (Connection connection = open(db)) {
                UUID requirement = requirement(connection);
                UUID first = null;
                for (String stage : CANONICAL_STAGES) {
                    UUID row = shortlist(connection, requirement, stage);
                    first = first == null ? row : first;
                }
                // Every stage as a destination, and every stage as an origin,
                // written by both kinds of actor. The pairs need not be legal
                // moves: V25 checks words, not transitions.
                change(connection, first, null, "SHORTLISTED", "STAFF");
                for (String stage : CANONICAL_STAGES) {
                    change(connection, first, stage, stage, "STAFF");
                    change(connection, first, "INVITED", stage, "STUDENT");
                }
                shortlistsBefore = rows(connection, shortlistsQuery);
                changesBefore = rows(connection, changesQuery);
            }
            assertThat(shortlistsBefore).hasSize(6);
            assertThat(changesBefore).hasSize(13);

            migrate(db, "25");

            try (Connection connection = open(db)) {
                assertThat(rows(connection, shortlistsQuery)).isEqualTo(shortlistsBefore);
                assertThat(rows(connection, changesQuery)).isEqualTo(changesBefore);
                assertThat(rows(connection, "select stage from company_requirement_shortlists order by stage"))
                        .containsExactlyInAnyOrderElementsOf(CANONICAL_STAGES);
            }
        }

        @Test
        @DisplayName("V24 alone accepts a stage that is not one, and V25 is what refuses it")
        void v25IsWhatRefuses() throws SQLException {
            Db db = fresh();
            migrate(db, "24");
            try (Connection connection = open(db)) {
                UUID requirement = requirement(connection);
                UUID accepted = shortlist(connection, requirement, "PROMOTED");
                change(connection, accepted, "PROMOTED", "PROMOTED", "ROBOT");
                // Removed again, or the upgrade below would rightly fail on it.
                run(connection, "delete from company_requirement_shortlists where id = '" + accepted + "'");
            }

            migrate(db, "25");

            try (Connection connection = open(db)) {
                UUID requirement = requirement(connection);
                assertThatThrownBy(() -> shortlist(connection, requirement, "PROMOTED"))
                        .isInstanceOf(SQLException.class);
            }
        }

        @Test
        @DisplayName("an upgrade meeting a stage that is not one fails, rather than quietly keeping it")
        void badExistingRowStopsTheUpgrade() throws SQLException {
            Db db = fresh();
            migrate(db, "24");
            try (Connection connection = open(db)) {
                shortlist(connection, requirement(connection), "PROMOTED");
            }

            FluentConfiguration configuration = Flyway.configure()
                    .dataSource(db.url(), db.user(), db.password())
                    .locations("classpath:db/migration")
                    .target("25");
            if (db.schema() != null) {
                configuration = configuration.schemas(db.schema());
            }
            Flyway flyway = configuration.load();
            assertThatThrownBy(flyway::migrate).isInstanceOf(Exception.class);
        }
    }

    // ---------------------------------------------------------- what is refused

    @Nested
    @DisplayName("a raw write the enum would never make")
    class Refused {

        @Test
        @DisplayName("the current stage refuses a word that is not a stage, on insert and on update")
        void shortlistStage() throws SQLException {
            Db db = fresh();
            migrate(db, "25");
            try (Connection connection = open(db)) {
                UUID requirement = requirement(connection);
                UUID existing = shortlist(connection, requirement, "INVITED");
                for (String bad : INVALID) {
                    assertThatThrownBy(() -> shortlist(connection, requirement, bad))
                            .describedAs("insert stage=%s", literal(bad))
                            .isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> run(connection, "update company_requirement_shortlists set stage = "
                            + literal(bad) + " where id = '" + existing + "'"))
                            .describedAs("update stage=%s", literal(bad))
                            .isInstanceOf(SQLException.class);
                }
                assertThat(single(connection, "select stage from company_requirement_shortlists where id = '"
                        + existing + "'")).isEqualTo("INVITED");
            }
        }

        @Test
        @DisplayName("the history refuses it as a destination, as an origin, and as a kind of actor")
        void history() throws SQLException {
            Db db = fresh();
            migrate(db, "25");
            try (Connection connection = open(db)) {
                UUID shortlist = shortlist(connection, requirement(connection), "SHORTLISTED");
                change(connection, shortlist, null, "SHORTLISTED", "STAFF");
                String existing = single(connection, "select id from placement_stage_changes");

                for (String bad : INVALID) {
                    assertThatThrownBy(() -> change(connection, shortlist, "SHORTLISTED", bad, "STAFF"))
                            .describedAs("to_stage=%s", literal(bad)).isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> change(connection, shortlist, bad, "INVITED", "STAFF"))
                            .describedAs("from_stage=%s", literal(bad)).isInstanceOf(SQLException.class);
                    for (String column : List.of("to_stage", "from_stage")) {
                        assertThatThrownBy(() -> run(connection, "update placement_stage_changes set " + column
                                + " = " + literal(bad) + " where id = '" + existing + "'"))
                                .describedAs("update %s=%s", column, literal(bad))
                                .isInstanceOf(SQLException.class);
                    }
                }
                for (String bad : List.of("ADMIN", "staff", "")) {
                    assertThatThrownBy(() -> change(connection, shortlist, "SHORTLISTED", "INVITED", bad))
                            .describedAs("actor_kind=%s", literal(bad)).isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> run(connection, "update placement_stage_changes set actor_kind = "
                            + literal(bad) + " where id = '" + existing + "'"))
                            .describedAs("update actor_kind=%s", literal(bad)).isInstanceOf(SQLException.class);
                }
                assertThat(single(connection, "select from_stage, to_stage, actor_kind from placement_stage_changes"))
                        .isEqualTo("null|SHORTLISTED|STAFF");
            }
        }

        @Test
        @DisplayName("the NULL rules are the ones V13 set: only the first move has no origin")
        void nullRulesUnchanged() throws SQLException {
            Db db = fresh();
            migrate(db, "25");
            try (Connection connection = open(db)) {
                UUID requirement = requirement(connection);
                UUID shortlist = shortlist(connection, requirement, "SHORTLISTED");

                change(connection, shortlist, null, "SHORTLISTED", "STAFF");
                assertThatThrownBy(() -> shortlist(connection, requirement, null)).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> change(connection, shortlist, "SHORTLISTED", null, "STAFF"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> change(connection, shortlist, null, "SHORTLISTED", null))
                        .isInstanceOf(SQLException.class);

                // The default still applies, and still satisfies the constraint.
                UUID defaulted = UUID.randomUUID();
                run(connection, "insert into company_requirement_shortlists (id, requirement_id, candidate_id, "
                        + "created_at) values ('" + defaulted + "', '" + requirement + "', '"
                        + candidate(connection, requirement) + "', current_timestamp)");
                assertThat(single(connection, "select stage from company_requirement_shortlists where id = '"
                        + defaulted + "'")).isEqualTo("SHORTLISTED");
            }
        }
    }

    // ------------------------------------------------------------------ drift

    @Nested
    @DisplayName("the Java enums and the database agree")
    class Drift {

        /** The quoted words in a constraint's definition, however the database chooses to render it. */
        private Set<String> allowedBy(Connection connection, String constraint) throws SQLException {
            String clause = single(connection, "select check_clause from information_schema.check_constraints "
                    + "where lower(constraint_name) = '" + constraint + "'");
            Set<String> words = new TreeSet<>();
            Matcher quoted = Pattern.compile("'([^']*)'").matcher(clause);
            while (quoted.find()) {
                words.add(quoted.group(1));
            }
            return words;
        }

        @Test
        @DisplayName("the enums hold exactly the canonical words")
        void enumsAreCanonical() {
            assertThat(enumNames(PlacementStage.values())).containsExactlyElementsOf(CANONICAL_STAGES);
            assertThat(enumNames(ActorKind.values())).containsExactlyElementsOf(CANONICAL_ACTOR_KINDS);
        }

        @Test
        @DisplayName("each constraint names exactly the enum's values: none missing, none extra")
        void constraintsNameExactlyTheEnum() throws SQLException {
            Db db = fresh();
            migrate(db, "25");
            Set<String> stages = new TreeSet<>(enumNames(PlacementStage.values()));
            try (Connection connection = open(db)) {
                assertThat(allowedBy(connection, "ck_shortlists_stage")).isEqualTo(stages);
                assertThat(allowedBy(connection, "ck_stage_changes_to_stage")).isEqualTo(stages);
                assertThat(allowedBy(connection, "ck_stage_changes_from_stage")).isEqualTo(stages);
                assertThat(allowedBy(connection, "ck_stage_changes_actor_kind"))
                        .isEqualTo(new TreeSet<>(enumNames(ActorKind.values())));
            }
        }

        @Test
        @DisplayName("every stage the enum has is accepted in every stage column, by both kinds of actor")
        void everyEnumValueIsAccepted() throws SQLException {
            Db db = fresh();
            migrate(db, "25");
            try (Connection connection = open(db)) {
                UUID requirement = requirement(connection);
                for (PlacementStage stage : PlacementStage.values()) {
                    UUID shortlist = shortlist(connection, requirement, stage.name());
                    for (ActorKind actor : ActorKind.values()) {
                        change(connection, shortlist, stage.name(), stage.name(), actor.name());
                    }
                }
                assertThat(single(connection, "select count(*) from company_requirement_shortlists"))
                        .isEqualTo(String.valueOf(PlacementStage.values().length));
                assertThat(single(connection, "select count(*) from placement_stage_changes"))
                        .isEqualTo(String.valueOf(PlacementStage.values().length * ActorKind.values().length));
            }
        }
    }
}
