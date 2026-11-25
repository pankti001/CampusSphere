package com.campussphere.backend.operator;

import java.io.Console;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogCreator;
import org.flywaydb.core.api.logging.LogFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;

public final class StudentE2eMigrationRunner {
    static final String DATABASE = "campus_sphere_student_e2e";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
    private static final Path ACTIVE = Path.of("src/main/resources/db/migration");
    private static final Path PROPOSALS = Path.of("proposed-migrations");
    private static final String ROLE = "postgres";
    private static final List<Migration> MIGRATIONS = List.of(
        new Migration("V1__create_identity_schema.sql", 57123013, "47cedbb8c28deedccfb73c7fd340cee53ae1fcd7543f9adff16343acf44e1c04"),
        new Migration("V2__seed_identity_roles.sql", -2074273504, "132332c87049532e2cb361437bf02cdbe4f87d4dfc78411729168d9534ebdbc9"),
        new Migration("V3__create_faculty_access_requests.sql", 278880907, "ff97619e9081a2f32461385cedec5072ef7753942ac5bd672cdf2350d704a013"),
        new Migration("V4__add_college_foundation.sql", 1437205848, "1791511f5e228c72fa2014847e540e407a06a80fd3463acc9fa3c48989e69664"),
        new Migration("V5__create_departments.sql", 1629970249, "2b4ce63df3f2d97d0435a7c30c3bf6d3c83e61eef3b7e03995ef5ac1a0813986"),
        new Migration("V6__extend_college_management.sql", 1981309137, "0997f04d4736989f25d430d435c11f4bd0016af31df41749b047b36762d4fb78"),
        new Migration("V7__add_college_admin_invitations_and_audit.sql", -1832066092, "5af9b95478118c485caff9b0595b42bae8f10e5d7f4fbe6232ae2f5b7387b0d8"),
        new Migration("V8__add_college_registration_requests.sql", -1533777827, "e4891609a2d8f2807b613f9b2d247c1e3691059c9936cc760d7f99fe3c42ab05"),
        new Migration("V9__create_academic_programs.sql", -1368130674, "a1be3f8d3b93ac53baac4337528a0c036755ddaed9f180dd18796fcbb0439381"),
        new Migration("V10__create_academic_years_and_semesters.sql", 742880800, "67bb3166801be0491cf1a05d15e76bd91726a11d29e9ca171b23e8b5934620f0"),
        new Migration("V11__create_divisions_and_subjects.sql", -1302388076, "d003d33ddb0c9e5ab9f09d07b6f2be06115ed2377f73a4220b6f66a7ed2dd0f5"),
        new Migration("V12__create_faculty_assignments.sql", 1809664787, "36303ef5c85d5a496c8a9fbd0711f219ca2d283ea7d464385fa8c600a59d962a"),
        new Migration("V13__create_student_affiliations_and_enrollments.sql", 1545295299, "816f0aca3cc81508c13070f3d489399132c9931c768ecc88890ee3e555b2c5f8")
    );
    private static final List<String> BASE_TABLES = List.of(
        "college_admin_invitations", "colleges", "departments", "faculty_access_requests",
        "flyway_schema_history", "privileged_audit_events", "roles", "user_roles", "users"
    );
    private static final List<String> NEW_TABLES = List.of(
        "college_registration_requests", "academic_programs", "academic_years", "academic_semesters",
        "academic_divisions", "academic_subjects", "faculty_profiles", "faculty_subject_assignments",
        "student_college_requests", "student_profiles", "student_enrollments"
    );
    private static final String IDENTITY = """
            SELECT current_database(), host(inet_server_addr()), inet_server_port(),
                   current_setting('server_version_num')::integer, session_user, current_user, current_schema(),
                   pg_catalog.pg_get_userbyid(datdba)
            FROM pg_catalog.pg_database WHERE datname = current_database() AND NOT datistemplate
            """;

    private StudentE2eMigrationRunner() {}

    private static PrintWriter report;

    public static void main(String[] args) {
        int exit = 1;
        String destination = System.getProperty("campussphere.student.report");
        try {
            if (destination != null) {
                report = new PrintWriter(Files.newBufferedWriter(Path.of(destination), StandardOpenOption.CREATE_NEW));
            } else if (Arrays.equals(args, new String[]{"migrate", DATABASE})) {
                status("FAILED=DIAGNOSTIC_DESTINATION_REQUIRED");
                System.exit(2);
            }
            exit = run(args);
        } catch (IOException | RuntimeException failure) {
            status("FAILED=LOCAL_DIAGNOSTIC_SETUP|" + failure.getClass().getSimpleName());
        } finally {
            status("RUNNER_EXIT=" + exit);
            if (report != null) report.close();
        }
        if (exit != 0) System.exit(exit);
    }

    private static void status(String message) {
        System.out.println(message);
        if (report != null) {
            report.println(message);
            report.flush();
        }
    }

    private static int run(String[] args) {
        boolean offline = Arrays.equals(args, new String[]{"verify-source"});
        if (!offline && !Arrays.equals(args, new String[]{"migrate", DATABASE})) {
            status("FAILED=EXPECTED_FIXED_TARGET");
            return 2;
        }
        String stage = "SOURCE";
        char[] password = null;
        try {
            byte[][] snapshot = verifySource();
            status("SOURCE_HASHES_VERIFIED=V1–V13");
            if (offline) return 0;
            stage = "MASKED_PASSWORD_PROMPT";
            Console console = System.console();
            require(console != null, "Interactive console is required");
            password = console.readPassword("Local PostgreSQL password: ");
            require(password != null && password.length != 0, "Password cancelled or empty");

            stage = "TARGET_AND_BASELINE";
            LogFactory.setLogCreator(new QuietLog());
            var dataSource = new CheckedDataSource(password);
            try (var control = dataSource.getConnection()) {
                require(scalar(control, "SELECT pg_try_advisory_lock(923730, 13)", Boolean.class),
                        "Another Student rehearsal runner holds the lock");
                verifyHistory(control, 7);
                verifyTables(control, BASE_TABLES);
                verifyAuditProtection(control);
                String original = verifyRecords(control, false);
                require(scalar(control, """
                        SELECT NOT EXISTS (SELECT lower(btrim(email)) FROM public.users
                            GROUP BY 1 HAVING count(*) > 1)
                        """, Boolean.class), "Normalized email collision");
                status("TARGET_VERIFIED=" + DATABASE + "|127.0.0.1:5432|19.0|postgres|public");
                status("BASELINE_VERIFIED=V1–V7");

                var location = Files.createTempDirectory("campussphere-student-flyway-");
                try {
                    for (int version = 1; version <= 7; version++) {
                        Files.write(location.resolve(MIGRATIONS.get(version - 1).file()), snapshot[version - 1]);
                    }
                    for (int version = 8; version <= 13; version++) {
                        stage = "V" + version + "_PRECHECK";
                        verifyIdentity(control);
                        verifyHistory(control, version - 1);
                        Files.write(location.resolve(MIGRATIONS.get(version - 1).file()), snapshot[version - 1]);
                        var flyway = Flyway.configure().dataSource(dataSource)
                                .locations("filesystem:" + location.toAbsolutePath().toString().replace('\\', '/'))
                                .schemas("public").defaultSchema("public").target(Integer.toString(version))
                                .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false)
                                .outOfOrder(false).validateOnMigrate(true).lockRetryCount(0)
                                .connectRetries(0).failOnMissingLocations(true).loggers(QuietLog.class.getName()).load();
                        var pending = Arrays.stream(flyway.info().pending()).map(i -> i.getVersion().toString()).toList();
                        require(pending.equals(List.of(Integer.toString(version))), "Unexpected pending set");
                        stage = "V" + version + "_MIGRATION";
                        status("MIGRATE_BEGIN=V" + version);
                        require(flyway.migrate().migrationsExecuted == 1, "Expected exactly one applied migration");
                        stage = "V" + version + "_HISTORY";
                        flyway.validate();
                        verifyHistory(control, version);
                        require(flyway.info().pending().length == 0, "Unexpected pending migration after apply");
                        status("MIGRATE_VERIFIED=V" + version + "|" + MIGRATIONS.get(version - 1).checksum());
                    }
                    stage = "POSTCHECK_READ_ONLY";
                    try (var verification = dataSource.getConnection()) {
                        verification.setReadOnly(true);
                        verification.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                        verification.setAutoCommit(false);
                        try {
                            require(scalar(verification, "SHOW transaction_read_only", String.class).equals("on"),
                                    "Verification must be read only");
                            verifyHistory(verification, 13);
                            verifySchema(verification);
                            require(original.equals(verifyRecords(verification, true)), "Original account or audit record changed");
                            verifyAuditProtection(verification);
                            printCatalog(verification);
                        } finally {
                            verification.rollback();
                        }
                    }
                    status("READ_ONLY_VERIFICATION=ROLLED_BACK");
                    status("ORIGINAL_SUPER_ADMIN_AND_AUDIT=PRESERVED");
                    status("VERIFIED_CONNECTIONS=" + dataSource.connections);
                } finally {
                    for (var migration : MIGRATIONS) Files.deleteIfExists(location.resolve(migration.file()));
                    Files.deleteIfExists(location);
                }
            }
            return 0;
        } catch (Exception | LinkageError failure) {
            status("FAILED_STAGE=" + stage + "|TYPE=" + failure.getClass().getSimpleName());
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof Refusal) status("REASON=" + cause.getMessage());
                if (cause instanceof SQLException sql && sql.getSQLState() != null
                        && sql.getSQLState().matches("[0-9A-Z]{5}")) {
                    status("SQLSTATE=" + sql.getSQLState() + "|VENDOR_CODE=" + sql.getErrorCode());
                }
            }
            return 1;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    static byte[][] verifySource() throws Exception {
        byte[][] snapshot = new byte[MIGRATIONS.size()][];
        for (int i = 0; i < snapshot.length; i++) {
            var migration = MIGRATIONS.get(i);
            snapshot[i] = Files.readAllBytes((i < 8 ? ACTIVE : PROPOSALS).resolve(migration.file()));
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot[i]));
            require(migration.sha256().equals(actual), "Source digest mismatch for " + migration.file());
        }
        return snapshot;
    }

    static List<String> reviewedMigrationNames() {
        return MIGRATIONS.stream().map(Migration::file).toList();
    }

    private static final class CheckedDataSource extends AbstractDataSource {
        private final char[] password;
        private int connections;
        CheckedDataSource(char[] password) { this.password = password; }

        @Override public Connection getConnection() throws SQLException {
            var properties = new Properties();
            properties.setProperty("user", ROLE);
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
                verifyIdentity(connection);
                connections++;
                return connection;
            } catch (SQLException | RuntimeException failure) {
                try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
                throw failure;
            }
        }
        @Override public Connection getConnection(String username, String suppliedPassword) throws SQLException {
            throw new SQLException("Datasource credential overrides are disabled");
        }
    }

    private static void verifyIdentity(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(IDENTITY)) {
            require(row.next(), "Database identity missing");
            verifyIdentity(row.getString(1), row.getString(2), row.getInt(3), row.getInt(4),
                    row.getString(5), row.getString(6), row.getString(7), row.getString(8));
        }
    }

    static void verifyIdentity(String database, String host, int port, int version,
                               String login, String role, String schema, String owner) {
        require(DATABASE.equals(database) && "127.0.0.1".equals(host) && port == 5432 && version == 190000
                && ROLE.equals(login) && ROLE.equals(role) && "public".equals(schema) && ROLE.equals(owner),
                "Unexpected database identity");
    }

    private static void verifyHistory(Connection connection, int expectedSize) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT installed_rank, version, checksum, script, success, type
                FROM public.flyway_schema_history ORDER BY installed_rank
                """)) {
            for (int version = 1; version <= expectedSize; version++) {
                var expected = MIGRATIONS.get(version - 1);
                require(rows.next() && rows.getInt(1) == version && Integer.toString(version).equals(rows.getString(2))
                        && Integer.valueOf(expected.checksum()).equals(rows.getObject(3, Integer.class))
                        && expected.file().equals(rows.getString(4)) && rows.getBoolean(5) && "SQL".equals(rows.getString(6)),
                        "Unexpected history row at V" + version);
            }
            require(!rows.next(), "Unexpected extra Flyway history rows");
        }
    }

    private static void verifyTables(Connection connection, List<String> expected) throws SQLException {
        String actual = scalar(connection, """
                SELECT string_agg(c.relname, ',' ORDER BY c.relname)
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind IN ('r','p')
                """, String.class);
        require(String.join(",", expected.stream().sorted().toList()).equals(actual), "Unexpected public tables");
    }

    private static String verifyRecords(Connection connection, boolean migrated) throws SQLException {
        require(scalar(connection, """
                SELECT string_agg(name, ',' ORDER BY name) FROM public.roles
                """, String.class).equals("ADMIN,COLLEGE_ADMIN,FACULTY,STUDENT,SUPER_ADMIN"), "Seeded roles differ");
        for (String table : BASE_TABLES) {
            if (table.equals("flyway_schema_history") || table.equals("roles")) continue;
            long expected = List.of("users", "user_roles", "privileged_audit_events").contains(table) ? 1 : 0;
            require(scalar(connection, "SELECT count(*) FROM public." + table, Long.class) == expected,
                    "Unexpected record count in " + table);
            status("ROWS=" + table + "|" + expected);
        }
        if (migrated) for (String table : NEW_TABLES) {
            require(scalar(connection, "SELECT count(*) FROM public." + table, Long.class) == 0,
                    "Unexpected records in " + table);
            status("ROWS=" + table + "|0");
        }
        return scalar(connection, """
                SELECT md5(to_jsonb(u)::text || to_jsonb(ur)::text || to_jsonb(a)::text)
                FROM public.users u JOIN public.user_roles ur ON ur.user_id = u.id
                JOIN public.roles r ON r.id = ur.role_id JOIN public.privileged_audit_events a
                    ON a.actor_id = u.id AND a.target_id = u.id
                WHERE r.name = 'SUPER_ADMIN' AND u.is_active IS TRUE AND u.is_verified IS TRUE
                    AND u.college_id IS NULL AND a.action = 'SUPER_ADMIN_PROVISIONED'
                """, String.class);
    }

    private static void verifyAuditProtection(Connection connection) throws SQLException {
        require(scalar(connection, """
                SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_trigger WHERE
                    tgrelid='public.privileged_audit_events'::regclass AND tgname='privileged_audit_append_only'
                    AND tgenabled IN ('O','A') AND tgtype=58 AND position('42501' in pg_get_functiondef(tgfoid))>0)
                """, Boolean.class), "Audit append-only protection missing");
    }

    private static void verifySchema(Connection connection) throws SQLException {
        verifyTables(connection, java.util.stream.Stream.concat(BASE_TABLES.stream(), NEW_TABLES.stream()).toList());
        for (String key : List.of("uq_academic_programs_college_id", "uq_semester_college_program_id",
                "uq_program_college_department_id", "uq_semester_college_program_year_id",
                "uq_division_college_program_year_id", "uq_subject_college_program_semester_id",
                "uq_users_normalized_email", "uq_pending_registration_code", "uq_pending_registration_email",
                "uq_faculty_assignment_active", "uq_student_college_request_pending", "uq_student_enrollment_current")) {
            try (var statement = connection.prepareStatement("""
                    SELECT i.indisvalid AND i.indisready AND i.indisunique FROM pg_catalog.pg_index i
                    JOIN pg_catalog.pg_class c ON c.oid=i.indexrelid JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='public' AND c.relname=?
                    """)) {
                statement.setString(1, key);
                try (var result = statement.executeQuery()) {
                    require(result.next() && result.getBoolean(1), "Missing or invalid unique index " + key);
                }
            }
        }
        require(scalar(connection, """
                WITH expected(name, fragment) AS (VALUES
                  ('fk_program_department_college','FOREIGN KEY (college_id, department_id) REFERENCES departments(college_id, id)'),
                  ('fk_semester_program_college','FOREIGN KEY (college_id, program_id) REFERENCES academic_programs(college_id, id)'),
                  ('fk_semester_year_college','FOREIGN KEY (college_id, academic_year_id) REFERENCES academic_years(college_id, id)'),
                  ('fk_division_program_college','FOREIGN KEY (college_id, program_id) REFERENCES academic_programs(college_id, id)'),
                  ('fk_division_year_college','FOREIGN KEY (college_id, academic_year_id) REFERENCES academic_years(college_id, id)'),
                  ('fk_subject_program_semester','FOREIGN KEY (college_id, program_id, semester_id) REFERENCES academic_semesters(college_id, program_id, id)'),
                  ('fk_student_college_request_reviewer','FOREIGN KEY (college_id, reviewed_by) REFERENCES users(college_id, id)'),
                  ('fk_student_profile_account','FOREIGN KEY (college_id, student_id) REFERENCES users(college_id, id)'),
                  ('fk_student_profile_department','FOREIGN KEY (college_id, department_id) REFERENCES departments(college_id, id)'),
                  ('fk_student_enrollment_profile','FOREIGN KEY (college_id, student_id) REFERENCES student_profiles(college_id, student_id)'),
                  ('fk_student_enrollment_program','FOREIGN KEY (college_id, department_id, program_id) REFERENCES academic_programs(college_id, department_id, id)'),
                  ('fk_student_enrollment_semester','FOREIGN KEY (college_id, program_id, academic_year_id, semester_id) REFERENCES academic_semesters(college_id, program_id, academic_year_id, id)'),
                  ('fk_student_enrollment_division','FOREIGN KEY (college_id, program_id, academic_year_id, division_id) REFERENCES academic_divisions(college_id, program_id, academic_year_id, id)')
                )
                SELECT NOT EXISTS (SELECT 1 FROM expected e WHERE NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_constraint c WHERE c.conname=e.name AND c.contype='f'
                  AND c.connamespace='public'::regnamespace AND c.convalidated AND pg_get_constraintdef(c.oid) LIKE e.fragment||'%'))
                """, Boolean.class), "Tenant foreign key mismatch");
        require(scalar(connection, """
                SELECT count(*) = 20 FROM pg_catalog.pg_constraint
                WHERE conrelid='public.college_registration_requests'::regclass AND contype='c' AND convalidated
                """, Boolean.class), "V8 state check count differs");
        require(scalar(connection, """
                SELECT count(*)=6 FROM pg_catalog.pg_constraint
                WHERE contype='f' AND convalidated AND conrelid IN ('public.faculty_profiles'::regclass,
                  'public.faculty_subject_assignments'::regclass, 'public.student_college_requests'::regclass)
                AND (pg_get_constraintdef(oid) LIKE 'FOREIGN KEY (college_id,%) REFERENCES %(college_id,%'
                     AND conrelid='public.faculty_subject_assignments'::regclass
                     OR conrelid='public.student_college_requests'::regclass AND conname='fk_student_college_request_reviewer')
                """, Boolean.class), "Faculty assignment or student reviewer composite foreign keys missing");
        require(scalar(connection, """
                SELECT NOT EXISTS (SELECT 1 FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class c ON c.oid=i.indexrelid
                    WHERE c.relnamespace='public'::regnamespace AND (NOT i.indisvalid OR NOT i.indisready))
                AND (SELECT count(*)=19 FROM pg_catalog.pg_indexes
                   WHERE schemaname='public' AND indexname IN (
                   'ix_registration_queue','ix_registration_expiry','ix_program_department_code','ix_semester_college_year',
                   'ix_division_college_year','ix_faculty_profiles_department','ix_faculty_assignments_profile',
                   'ix_faculty_assignments_program','ix_faculty_assignments_semester','ix_faculty_assignments_division',
                   'ix_faculty_assignments_subject','ix_student_college_request_history','ix_student_college_request_queue',
                   'ix_student_college_request_reviewer','ix_student_profile_department','ix_student_enrollment_history',
                   'ix_student_enrollment_program','ix_student_enrollment_semester','ix_student_enrollment_division'))
                """, Boolean.class), "Expected supporting indexes missing or invalid");
        require(scalar(connection, """
                SELECT NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint c
                WHERE c.connamespace='public'::regnamespace AND NOT c.convalidated)
                """, Boolean.class), "Unvalidated public constraints remain");
        require(scalar(connection, """
                SELECT count(*)=3 FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class c ON c.oid=i.indexrelid
                WHERE c.relnamespace='public'::regnamespace AND (
                   (c.relname IN ('uq_faculty_assignment_active','uq_student_enrollment_current')
                       AND pg_get_expr(i.indpred,i.indrelid)='(ended_at IS NULL)')
                   OR (c.relname='uq_student_college_request_pending'
                       AND pg_get_expr(i.indpred,i.indrelid) LIKE '%PENDING%'))
                """, Boolean.class), "Active or pending uniqueness predicate missing");
        require(scalar(connection, """
                SELECT EXISTS(SELECT 1 FROM pg_catalog.pg_constraint
                  WHERE conrelid='public.college_registration_requests'::regclass AND contype='f' AND convalidated
                    AND pg_get_constraintdef(oid) LIKE 'FOREIGN KEY (college_id, admin_id) REFERENCES users(college_id, id)%')
                AND EXISTS(SELECT 1 FROM pg_catalog.pg_constraint
                  WHERE conrelid='public.privileged_audit_events'::regclass AND conname='privileged_audit_events_action_check'
                    AND convalidated AND pg_get_constraintdef(oid) LIKE '%SUPER_ADMIN_PROVISIONED%'
                    AND pg_get_constraintdef(oid) LIKE '%COLLEGE_REGISTRATION_APPROVED%'
                    AND pg_get_constraintdef(oid) LIKE '%COLLEGE_REGISTRATION_REJECTED%')
                """, Boolean.class), "V8 registration foreign key or audit actions missing");
    }

    private static void printCatalog(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT 'CONSTRAINT', c.conrelid::regclass::text, c.conname, pg_get_constraintdef(c.oid)
                FROM pg_catalog.pg_constraint c WHERE c.connamespace='public'::regnamespace
                  AND c.contype IN ('f','u','c')
                UNION ALL SELECT 'INDEX', tablename, indexname, indexdef FROM pg_catalog.pg_indexes
                WHERE schemaname='public' ORDER BY 1,2,3
                """)) {
            while (rows.next()) status(rows.getString(1) + "|" + rows.getString(2)
                    + "|" + rows.getString(3) + "|" + rows.getString(4));
        }
    }

    private static <T> T scalar(Connection connection, String sql, Class<T> type) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(sql)) {
            require(row.next(), "Required verification result absent");
            T value = row.getObject(1, type);
            require(value != null && !row.next(), "Required verification result missing or ambiguous");
            return value;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new Refusal(message);
    }
    private static final class Refusal extends IllegalStateException {
        Refusal(String message) { super(message); }
    }
    private record Migration(String file, int checksum, String sha256) {}

    public static final class QuietLog implements LogCreator, Log {
        @Override public Log createLogger(Class<?> type) { return this; }
        @Override public boolean isDebugEnabled() { return false; }
        @Override public void debug(String message) {}
        @Override public void info(String message) {}
        @Override public void warn(String message) {}
        @Override public void error(String message) {}
        @Override public void error(String message, Exception failure) {}
        @Override public void notice(String message) {}
    }
}
