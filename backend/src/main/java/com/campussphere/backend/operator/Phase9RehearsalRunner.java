package com.campussphere.backend.operator;

import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogCreator;
import org.flywaydb.core.api.logging.LogFactory;

import static com.campussphere.backend.operator.Phase9RehearsalDiagnostic.Checkpoint.*;
import org.springframework.jdbc.datasource.AbstractDataSource;

public final class Phase9RehearsalRunner {
    static final String DATABASE = "campus_sphere_phase9_rehearsal";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
    private static final Path SOURCE = Path.of("src/main/resources/db/migration");
    private static final String[] FILES = {
        "V1__create_identity_schema.sql", "V2__seed_identity_roles.sql",
        "V3__create_faculty_access_requests.sql", "V4__add_college_foundation.sql",
        "V5__create_departments.sql", "V6__extend_college_management.sql",
        "V7__add_college_admin_invitations_and_audit.sql", "V8__add_college_registration_requests.sql"
    };
    static final List<Integer> CHECKSUMS = List.of(
            57123013, -2074273504, 278880907, 1437205848, 1629970249, 1981309137, -1832066092, -1533777827);
    private static final String V8_SHA = "e4891609a2d8f2807b613f9b2d247c1e3691059c9936cc760d7f99fe3c42ab05";
    private static final String ROLE = "postgres";

    private Phase9RehearsalRunner() {}

    enum Stage {
        BASELINE(7), V8(8);
        final int target;
        Stage(int target) { this.target = target; }
    }

    public static void main(String[] args) {
        char[] password = null;
        int exit = 0;
        Path report = null;
        var diagnostic = new Phase9RehearsalDiagnostic();
        try {
            report = Files.createTempFile("campussphere-phase9-diagnostic-", ".log");
            System.err.println("Failure diagnostic file: " + report);
            Stage stage = invocation(args);
            diagnostic.stage = stage.name();
            diagnostic.checkpoint = CONSOLE;
            Console console = System.console();
            require(console != null, "Interactive console required");
            console.printf("Stage %s | 127.0.0.1:5432/%s | schema public%n", stage, DATABASE);
            diagnostic.checkpoint = CONFIRMATION;
            require(("APPLY " + stage + " " + DATABASE).equals(console.readLine(
                    "Type APPLY %s %s to confirm: ", stage, DATABASE)), "Confirmation refused");
            diagnostic.checkpoint = PASSWORD_INPUT;
            password = console.readPassword("Local PostgreSQL password: ");
            require(password != null && password.length > 0, "Password input missing");
            execute(stage, password, diagnostic);
            console.printf("Stage %s completed and verified.%n", stage);
        } catch (SQLException | IOException | RuntimeException | LinkageError failure) {
            diagnostic.publish(report, failure);
            exit = 1;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
        if (exit != 0) System.exit(exit);
    }

    static Stage invocation(String... args) {
        require(args.length == 2 && DATABASE.equals(args[1]), "Supply a stage and the literal rehearsal database name");
        return switch (args[0]) {
            case "baseline" -> Stage.BASELINE;
            case "v8" -> Stage.V8;
            default -> throw new Refusal("Unknown stage");
        };
    }

    static void verifyIdentity(String database, String address, int port, int version, String schema) {
        require(DATABASE.equals(database) && "127.0.0.1".equals(address) && port == 5432
                && version == 190000 && "public".equals(schema), "Server identity mismatch");
    }

    private static Connection connect(char[] password, Phase9RehearsalDiagnostic diagnostic) throws SQLException {
        diagnostic.connectionStep = "JDBC_CONNECT";
        Connection connection = DriverManager.getConnection(URL, ROLE, new String(password));
        diagnostic.connectionStep = "VERIFY_SERVER_IDENTITY";
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("""
                 SELECT current_database(), host(inet_server_addr()), inet_server_port(),
                        current_setting('server_version_num')::integer, current_schema(), current_user, session_user
                 """)) {
            require(row.next(), "Server identity missing");
            verifyIdentity(row.getString(1), row.getString(2), row.getInt(3), row.getInt(4), row.getString(5));
            require(ROLE.equals(row.getString(6)) && ROLE.equals(row.getString(7)), "Unexpected server role");
            diagnostic.connectionStep = "none";
            return diagnostic.observeRollback(connection);
        } catch (RuntimeException | SQLException failure) {
            try {
                connection.close();
            } catch (SQLException | RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static void execute(Stage stage, char[] password, Phase9RehearsalDiagnostic diagnostic)
            throws SQLException, IOException {
        diagnostic.checkpoint = SOURCE_VERIFICATION;
        var snapshot = Files.createTempDirectory("campussphere-rehearsal-sql-");
        Throwable originalFailure = null;
        try {
            try (var entries = Files.list(SOURCE)) {
                require(Set.copyOf(entries.map(path -> path.getFileName().toString()).toList())
                        .equals(Set.of(FILES)), "Unexpected migration source inventory");
            }
            for (int i = 0; i < stage.target; i++) {
                byte[] bytes = Files.readAllBytes(SOURCE.resolve(FILES[i]));
                verifySource(i + 1, bytes);
                Files.write(snapshot.resolve(FILES[i]), bytes);
            }
            var dataSource = new AbstractDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    return connect(password, diagnostic);
                }

                @Override public Connection getConnection(String username, String suppliedPassword) throws SQLException {
                    throw new SQLException("Explicit datasource credential overrides are disabled");
                }
            };
            diagnostic.checkpoint = CONTROL_CONNECTION;
            try (var control = dataSource.getConnection()) {
                diagnostic.checkpoint = ADVISORY_LOCK;
                require(scalar(control, "SELECT pg_try_advisory_lock(923730, 9)", Boolean.class),
                        "Another rehearsal runner holds the lock");
                diagnostic.checkpoint = FLYWAY_CONFIGURATION;
                LogFactory.setLogCreator(new QuietFlywayLog());
                var flyway = Flyway.configure().dataSource(dataSource)
                        .locations("filesystem:" + snapshot.toAbsolutePath().toString().replace('\\', '/'))
                        .schemas("public").defaultSchema("public").createSchemas(false)
                        .target(Integer.toString(stage.target)).cleanDisabled(true)
                        .baselineOnMigrate(false).outOfOrder(false).validateOnMigrate(true)
                        .loggers(QuietFlywayLog.class.getName()).callbacks(new Callback() {
                            @Override public boolean supports(Event event, Context context) {
                                return event == Event.BEFORE_EACH_MIGRATE_STATEMENT;
                            }
                            @Override public boolean canHandleInTransaction(Event event, Context context) { return true; }
                            @Override public void handle(Event event, Context context) { diagnostic.scriptReached = true; }
                            @Override public String getCallbackName() { return "phase9-diagnostic"; }
                        })
                        .lockRetryCount(0).connectRetries(0).failOnMissingLocations(true).load();
                run(stage, new Checks() {
                    @Override public void empty() throws SQLException { verifyEmptySchema(control); }
                    @Override public void baseline() throws SQLException { verifyBaseline(control); }
                    @Override public List<String> pending() {
                        return Arrays.stream(flyway.info().pending()).map(MigrationInfo::getVersion)
                                .map(Object::toString).toList();
                    }
                    @Override public void migrate() { flyway.migrate(); }
                    @Override public void validate() { flyway.validate(); }
                    @Override public void v8() throws SQLException { verifyV8(control); }
                }, diagnostic);
                diagnostic.checkpoint = CLOSE_CONNECTION;
            }
        } catch (IOException | SQLException | RuntimeException | LinkageError failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            try {
                for (String file : FILES) Files.deleteIfExists(snapshot.resolve(file));
                Files.deleteIfExists(snapshot);
            } catch (IOException | RuntimeException cleanupFailure) {
                if (originalFailure != null) originalFailure.addSuppressed(cleanupFailure);
                else {
                    diagnostic.checkpoint = REMOVE_SNAPSHOT;
                    throw cleanupFailure;
                }
            }
        }
    }

    // Flyway can log SQL and driver messages outside the exception chain.
    // The standalone operator process reports only the sanitized diagnostic.
    public static final class QuietFlywayLog implements LogCreator, Log {
        @Override public Log createLogger(Class<?> type) { return this; }
        @Override public boolean isDebugEnabled() { return false; }
        @Override public void debug(String message) {}
        @Override public void info(String message) {}
        @Override public void warn(String message) {}
        @Override public void error(String message) {}
        @Override public void error(String message, Exception failure) {}
        @Override public void notice(String message) {}
    }

    interface Checks {
        void empty() throws SQLException;
        void baseline() throws SQLException;
        List<String> pending();
        void migrate();
        void validate();
        void v8() throws SQLException;
    }

    static void run(Stage stage, Checks checks) throws SQLException {
        run(stage, checks, new Phase9RehearsalDiagnostic());
    }

    static void run(Stage stage, Checks checks, Phase9RehearsalDiagnostic diagnostic) throws SQLException {
        diagnostic.stage = stage.name();
        if (stage == Stage.BASELINE) {
            diagnostic.checkpoint = EMPTY_SCHEMA;
            checks.empty();
            diagnostic.checkpoint = PENDING_MIGRATIONS;
            verifyPending(checks.pending(), List.of("1", "2", "3", "4", "5", "6", "7"));
        } else {
            diagnostic.checkpoint = BASELINE_VERIFICATION;
            checks.baseline();
            diagnostic.checkpoint = PENDING_MIGRATIONS;
            verifyPending(checks.pending(), List.of("8"));
        }
        diagnostic.checkpoint = MIGRATE;
        diagnostic.migrateInvoked = true;
        checks.migrate();
        diagnostic.migrateReturned = true;
        diagnostic.checkpoint = FLYWAY_VALIDATION;
        checks.validate();
        diagnostic.checkpoint = POST_MIGRATION_PENDING;
        require(checks.pending().isEmpty(), "Migrations remain pending");
        diagnostic.checkpoint = POST_MIGRATION_SCHEMA;
        if (stage == Stage.BASELINE) checks.baseline();
        else checks.v8();
    }

    static void verifyPending(List<String> actual, List<String> expected) {
        require(expected.equals(actual), "Unexpected pending migration set");
    }

    static void verifySource(int version, byte[] bytes) {
        if (version == 8) {
            try {
                require(V8_SHA.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))),
                        "V8 SHA-256 mismatch");
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException(failure);
            }
        }
        var checksum = new CRC32();
        var source = new String(bytes, StandardCharsets.UTF_8);
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        source.lines().forEach(line -> checksum.update(line.getBytes(StandardCharsets.UTF_8)));
        require((int) checksum.getValue() == CHECKSUMS.get(version - 1), "Migration source checksum mismatch");
    }

    static <T> T scalar(Connection connection, String sql, Class<T> type) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(sql)) {
            require(row.next(), "Required catalog result missing");
            return row.getObject(1, type);
        }
    }

    static void verifyEmptySchema(Connection connection) throws SQLException {
        verifyEmpty(scalar(connection, """
                SELECT
                  (SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace) +
                  (SELECT count(*) FROM pg_proc WHERE pronamespace = 'public'::regnamespace) +
                  (SELECT count(*) FROM pg_type WHERE typnamespace = 'public'::regnamespace) +
                  (SELECT count(*) FROM pg_extension WHERE extnamespace = 'public'::regnamespace) +
                  (SELECT count(*) FROM pg_operator WHERE oprnamespace = 'public'::regnamespace) +
                  (SELECT count(*) FROM pg_collation WHERE collnamespace = 'public'::regnamespace)
                """, Long.class));
    }

    static void verifyEmpty(long objects) {
        require(objects == 0, "Baseline public schema is not empty");
    }

    record History(String version, Integer checksum, boolean success, String script) {}

    static void verifyHistory(List<History> entries, int target) {
        require(entries.size() == target, "Unexpected migration history length");
        for (int i = 0; i < target; i++) {
            require(new History(Integer.toString(i + 1), CHECKSUMS.get(i), true, FILES[i]).equals(entries.get(i)),
                    "Unexpected migration history at V" + (i + 1));
        }
    }

    private static void verifyHistory(Connection connection, int target) throws SQLException {
        var entries = new ArrayList<History>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT version, checksum, success, script FROM public.flyway_schema_history ORDER BY installed_rank
                """)) {
            while (rows.next()) entries.add(new History(rows.getString(1), rows.getObject(2, Integer.class),
                    rows.getBoolean(3), rows.getString(4)));
        }
        verifyHistory(entries, target);
    }

    private static void verifyBaseline(Connection connection) throws SQLException {
        verifyHistory(connection, 7);
        verifySeedsAndEmptyTables(connection);
        require(!scalar(connection, "SELECT to_regclass('public.college_registration_requests') IS NOT NULL",
                Boolean.class), "V8 table exists before V8");
    }

    private static void verifySeedsAndEmptyTables(Connection connection) throws SQLException {
        require("ADMIN,COLLEGE_ADMIN,FACULTY,STUDENT,SUPER_ADMIN".equals(scalar(connection,
                "SELECT string_agg(name, ',' ORDER BY name COLLATE \"C\") FROM public.roles", String.class)),
                "Unexpected seeded roles");
        for (String table : List.of("users", "user_roles", "colleges", "departments", "faculty_access_requests",
                "college_admin_invitations", "privileged_audit_events")) {
            require(!scalar(connection, "SELECT EXISTS (SELECT 1 FROM public." + table + ")", Boolean.class),
                    "Expected empty table: " + table);
        }
    }

    private static void verifyV8(Connection connection) throws SQLException {
        verifyHistory(connection, 8);
        verifySeedsAndEmptyTables(connection);
        require(!scalar(connection, "SELECT EXISTS (SELECT 1 FROM public.college_registration_requests)",
                Boolean.class), "Unexpected registration records");
        require(scalar(connection, """
                SELECT count(*) = 5 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                WHERE c.relnamespace = 'public'::regnamespace AND i.indisvalid AND i.indisready
                  AND c.relname IN ('uq_users_normalized_email', 'uq_pending_registration_code',
                    'uq_pending_registration_email', 'ix_registration_queue', 'ix_registration_expiry')
                """, Boolean.class), "Required V8 indexes missing or invalid");
        verifyRegistrationCheckCount(connection);
        require(scalar(connection, """
                SELECT EXISTS (
                    SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'public.college_registration_requests'::regclass AND convalidated
                      AND confrelid = 'public.users'::regclass
                      AND pg_get_constraintdef(oid) = 'FOREIGN KEY (college_id, admin_id) REFERENCES users(college_id, id)'
                )
                """, Boolean.class), "V8 composite college administrator foreign key missing");
        require(scalar(connection, """
                SELECT EXISTS (
                    SELECT 1 FROM pg_constraint WHERE conrelid = 'public.privileged_audit_events'::regclass
                      AND conname = 'privileged_audit_events_action_check' AND convalidated
                      AND pg_get_constraintdef(oid) LIKE '%COLLEGE_REGISTRATION_APPROVED%'
                      AND pg_get_constraintdef(oid) LIKE '%COLLEGE_REGISTRATION_REJECTED%'
                )
                """, Boolean.class), "V8 audit action constraint missing or incomplete");
        require(scalar(connection, """
                SELECT EXISTS (
                    SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.privileged_audit_events'::regclass
                      AND tgname = 'privileged_audit_append_only' AND tgenabled IN ('O','A')
                      AND tgtype = 58
                      AND tgfoid = 'public.reject_privileged_audit_mutation()'::regprocedure
                )
                """, Boolean.class), "V7 append-only audit trigger missing or disabled");
    }

    static void verifyRegistrationCheckCount(Connection connection) throws SQLException {
        long count = scalar(connection, """
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = 'public.college_registration_requests'::regclass
                  AND contype = 'c' AND convalidated
                """, Long.class);
        require(count == 20, "Expected 20 validated V8 registration CHECK constraints, found " + count);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new Refusal(message);
    }

    static final class Refusal extends IllegalStateException {
        Refusal(String message) { super(message); }
    }
}
