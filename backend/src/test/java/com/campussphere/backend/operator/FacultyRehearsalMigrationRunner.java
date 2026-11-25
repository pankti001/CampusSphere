package com.campussphere.backend.operator;

import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.stream.IntStream;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.logging.LogFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Operator-only entry point for an already-created, empty Faculty rehearsal database. */
public final class FacultyRehearsalMigrationRunner {
    public static final String DATABASE = "campus_sphere_faculty_e2e";
    public static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
    public static final String IDENTITY = """
            SELECT current_database() = 'campus_sphere_faculty_e2e'
               AND inet_server_addr() = '127.0.0.1'::inet AND inet_server_port() = 5432
               AND current_setting('server_version_num') = '190000'
               AND current_user = 'postgres' AND session_user = 'postgres'
               AND current_schema() = 'public'
               AND EXISTS (SELECT FROM pg_catalog.pg_database WHERE datname = current_database()
                           AND NOT datistemplate AND pg_catalog.pg_get_userbyid(datdba) = 'postgres')
            """;
    private static final String V14 = "V14__scope_faculty_access_requests_to_colleges.sql";
    private static final String V14_SHA256 = "61e6adf043a75c237588b1743400791d9f08571ba85df859b5a3abee3e4b7a77";

    private FacultyRehearsalMigrationRunner() {}

    public static void main(String[] args) {
        Mode mode = mode(args);
        if (mode == Mode.REFUSE) {
            System.err.println("FACULTY_REHEARSAL_REFUSED: expected verify-source or migrate " + DATABASE);
            System.exit(2);
        }
        String stage = "source";
        char[] password = null;
        int exitCode = 0;
        try {
            var sources = reviewedSources();
            if (mode == Mode.VERIFY) {
                System.out.println("FACULTY_REHEARSAL_SOURCE_VERIFIED: " + sources.size() + " migrations");
                return;
            }
            stage = "masked-password";
            Console console = System.console();
            require(console != null, "Interactive console required");
            password = console.readPassword("Local PostgreSQL password: ");
            require(password != null && password.length > 0, "Password input required");

            stage = "target-and-empty-schema";
            var dataSource = new CheckedDataSource(password);
            try (var control = dataSource.getConnection()) {
                require(scalar(control, "SELECT pg_try_advisory_lock(923730, 14)"), "Rehearsal lock unavailable");
                require(scalar(control, """
                        SELECT NOT EXISTS (
                            SELECT FROM pg_catalog.pg_class c
                            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                            WHERE n.nspname = 'public'
                        ) AND NOT EXISTS (
                            SELECT FROM pg_catalog.pg_proc p
                            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                            WHERE n.nspname = 'public'
                        ) AND NOT EXISTS (
                            SELECT FROM pg_catalog.pg_type t
                            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
                            WHERE n.nspname = 'public'
                        ) AND NOT EXISTS (
                            SELECT FROM pg_catalog.pg_namespace
                            WHERE nspname NOT IN ('public', 'pg_catalog', 'information_schema', 'pg_toast')
                              AND nspname NOT LIKE 'pg_temp_%' AND nspname NOT LIKE 'pg_toast_temp_%'
                        )
                        """), "Expected an empty rehearsal schema");
                System.out.println("FACULTY_REHEARSAL_TARGET_VERIFIED: " + DATABASE);
                stage = "migration";
                migrate(dataSource, sources);
            }
            System.out.println("FACULTY_REHEARSAL_MIGRATION_VERIFIED: V1–V14");
        } catch (Exception | LinkageError failure) {
            System.err.println("FACULTY_REHEARSAL_FAILED: " + stage + " | " + failure.getClass().getSimpleName());
            if (failure instanceof Refusal) System.err.println(failure.getMessage());
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getSQLState() != null
                        && sql.getSQLState().matches("[0-9A-Z]{5}")) {
                    System.err.println("SQLSTATE=" + sql.getSQLState());
                }
            }
            if (stage.equals("migration")) {
                System.err.println("Inspect migration history read-only before taking further action.");
            }
            exitCode = 1;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    private static void migrate(CheckedDataSource dataSource, List<Source> sources) throws Exception {
        Path directory = Files.createTempDirectory("campussphere-faculty-migrations-");
        try {
            for (var source : sources) Files.write(directory.resolve(source.name()), source.bytes());
            LogFactory.setLogCreator(new StudentE2eMigrationRunner.QuietLog());
            var flyway = Flyway.configure().dataSource(dataSource)
                    .locations("filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/'))
                    .schemas("public").defaultSchema("public").target("14")
                    .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false)
                    .outOfOrder(false).validateOnMigrate(true).lockRetryCount(0)
                    .connectRetries(0).failOnMissingLocations(true)
                    .loggers(StudentE2eMigrationRunner.QuietLog.class.getName()).load();
            var expected = IntStream.rangeClosed(1, sources.size()).mapToObj(Integer::toString).toList();
            var pending = Arrays.stream(flyway.info().pending()).map(row -> row.getVersion().toString()).toList();
            require(expected.equals(pending), "Unexpected pending migration set");
            require(flyway.migrate().migrationsExecuted == sources.size(), "Unexpected number of applied migrations");
            flyway.validate();
            try (var verification = dataSource.getConnection()) {
                verification.setReadOnly(true);
                verification.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                verification.setAutoCommit(false);
                try {
                    require(scalar(verification, "SELECT current_setting('transaction_read_only') = 'on'"),
                            "Post-migration verification is not read-only");
                    require(scalar(verification, """
                            SELECT count(*) = 14 AND bool_and(success)
                                AND array_agg(version::integer ORDER BY installed_rank) = ARRAY(
                                    SELECT generate_series(1, 14))
                            FROM public.flyway_schema_history
                            """), "Migration history is incomplete");
                    require(scalar(verification, """
                            SELECT count(*) = 3 AND bool_and(convalidated)
                            FROM pg_catalog.pg_constraint
                            WHERE conrelid = 'public.faculty_access_requests'::regclass
                              AND conname IN ('faculty_access_requests_requested_college_id_fkey',
                                              'fk_faculty_request_college_reviewer', 'ck_faculty_request_college_binding')
                            """), "V14 constraints are missing or unvalidated");
                    require(scalar(verification, """
                            SELECT count(*) = 2 AND bool_and(i.indisvalid)
                            FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid
                            WHERE i.indrelid = 'public.faculty_access_requests'::regclass
                              AND c.relname IN ('ix_faculty_request_target_status', 'ix_faculty_request_target_reviewer')
                            """), "V14 indexes are missing or invalid");
                    require(scalar(verification, """
                            SELECT (SELECT string_agg(name, ',' ORDER BY name) FROM public.roles)
                                     = 'ADMIN,COLLEGE_ADMIN,FACULTY,STUDENT,SUPER_ADMIN'
                               AND NOT EXISTS (SELECT FROM public.users)
                               AND NOT EXISTS (SELECT FROM public.user_roles)
                               AND NOT EXISTS (SELECT FROM public.colleges)
                               AND NOT EXISTS (SELECT FROM public.faculty_access_requests)
                               AND NOT EXISTS (SELECT FROM public.privileged_audit_events)
                            """), "Unexpected roles or fixture data after migrations");
                } finally {
                    verification.rollback();
                }
            }
        } finally {
            for (var source : sources) Files.deleteIfExists(directory.resolve(source.name()));
            Files.deleteIfExists(directory);
        }
    }

    static List<Source> reviewedSources() throws Exception {
        byte[][] bytes = StudentE2eMigrationRunner.verifySource();
        List<String> names = StudentE2eMigrationRunner.reviewedMigrationNames();
        List<Source> sources = new ArrayList<>(names.size() + 1);
        for (int i = 0; i < names.size(); i++) sources.add(new Source(names.get(i), bytes[i]));
        byte[] v14 = Files.readAllBytes(Path.of("proposed-migrations", V14));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v14));
        require(V14_SHA256.equals(digest), "V14 source hash differs from reviewed SQL");
        sources.add(new Source(V14, v14));
        return List.copyOf(sources);
    }

    static Mode mode(String[] args) {
        if (Arrays.equals(args, new String[]{"verify-source"})) return Mode.VERIFY;
        if (Arrays.equals(args, new String[]{"migrate", DATABASE})) return Mode.MIGRATE;
        return Mode.REFUSE;
    }

    private static boolean scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            return rows.next() && rows.getBoolean(1) && !rows.wasNull();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new Refusal(message);
    }

    private static final class CheckedDataSource extends AbstractDataSource {
        private final char[] password;

        CheckedDataSource(char[] password) { this.password = password; }

        @Override public Connection getConnection() throws SQLException {
            var properties = new Properties();
            properties.setProperty("user", "postgres");
            properties.setProperty("password", new String(password));
            properties.setProperty("connectTimeout", "10");
            properties.setProperty("socketTimeout", "65");
            properties.setProperty("options", "-c search_path=public,pg_catalog -c statement_timeout=60000 -c lock_timeout=5000");
            Connection connection;
            try {
                connection = DriverManager.getConnection(URL, properties);
            } finally {
                properties.clear();
            }
            try {
                require(scalar(connection, IDENTITY), "Unexpected PostgreSQL identity");
                return connection;
            } catch (SQLException | RuntimeException failure) {
                try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
                throw failure;
            }
        }

        @Override public Connection getConnection(String user, String suppliedPassword) throws SQLException {
            throw new SQLException("Datasource credential overrides are disabled");
        }
    }

    enum Mode { VERIFY, MIGRATE, REFUSE }

    record Source(String name, byte[] bytes) {}

    private static final class Refusal extends IllegalStateException {
        Refusal(String message) { super(message); }
    }
}
