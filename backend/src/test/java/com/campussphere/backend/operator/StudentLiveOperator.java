package com.campussphere.backend.operator;

import com.campussphere.backend.CampusSphereBackendApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

public final class StudentLiveOperator {
    private static final String URL = StudentE2eMigrationRunner.URL;
    private static final String IDENTITY = """
            SELECT current_database(), host(inet_server_addr()), inet_server_port(),
                   current_setting('server_version_num')::integer, session_user, current_user, current_schema(),
                   pg_get_userbyid(datdba)
            FROM pg_catalog.pg_database WHERE datname=current_database() AND NOT datistemplate
            """;
    private static final String HISTORY = """
            SELECT string_agg(version||':'||checksum, ',' ORDER BY installed_rank)
            FROM public.flyway_schema_history HAVING count(*)=13 AND bool_and(success)
            """;
    private static final String EXPECTED_HISTORY = "1:57123013,2:-2074273504,3:278880907,4:1437205848,5:1629970249,"
            + "6:1981309137,7:-1832066092,8:-1533777827,9:-1368130674,10:742880800,11:-1302388076,12:1809664787,13:1545295299";
    private static final List<String> FIXTURE_TABLES = List.of("colleges", "departments", "academic_programs",
            "academic_years", "academic_semesters", "academic_divisions", "academic_subjects", "faculty_profiles",
            "faculty_subject_assignments", "student_college_requests", "student_profiles", "student_enrollments",
            "faculty_access_requests", "college_admin_invitations", "college_registration_requests");

    private StudentLiveOperator() {}

    public static void main(String[] args) {
        if (args.length != 1 || !List.of("seed", "serve", "verify", "reset-alpha").contains(args[0])) {
            System.out.println("OPERATOR_REFUSED=MODE");
            System.exit(2);
        }
        try {
            var dataSource = new IsolatedDataSource(secret("STUDENT_E2E_DB_PASSWORD"));
            switch (args[0]) {
                case "seed" -> seed(dataSource);
                case "serve" -> serve(dataSource);
                case "verify" -> verify(dataSource);
                case "reset-alpha" -> resetAlpha(dataSource);
                default -> throw new IllegalStateException("Unexpected mode");
            }
        } catch (Exception | LinkageError failure) {
            System.out.println("OPERATOR_FAILED=" + args[0] + "|" + failure.getClass().getSimpleName());
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getSQLState() != null
                        && sql.getSQLState().matches("[A-Z0-9]{5}")) {
                    System.out.println("SQLSTATE=" + sql.getSQLState());
                }
            }
            System.exit(1);
        }
    }

    private static void serve(IsolatedDataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            assertBaseline(connection);
        }
        System.setProperty("app.jwt.secret", secret("STUDENT_E2E_JWT_SECRET"));
        var app = new SpringApplication(CampusSphereBackendApplication.class);
        app.addInitializers(context -> ((GenericApplicationContext) context)
                .registerBean("isolatedStudentDataSource", DataSource.class, () -> dataSource));
        app.run("--spring.config.location=classpath:/application.properties",
                "--server.address=127.0.0.1", "--server.port=8080",
                "--spring.datasource.url=" + URL, "--spring.datasource.username=postgres",
                "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.jpa.show-sql=false", "--spring.sql.init.mode=never",
                "--app.registration.expiry-enabled=false", "--app.auth.student-verification-required=true",
                "--spring.devtools.restart.enabled=false", "--spring.main.banner-mode=off");
        System.out.println("BACKEND_READY=campus_sphere_student_e2e|127.0.0.1:8080");
    }

    private static void seed(IsolatedDataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                assertBaseline(connection);
                require(number(connection, "SELECT count(*) FROM public.users") == 1);
                require(number(connection, "SELECT count(*) FROM public.user_roles") == 1);
                for (String table : FIXTURE_TABLES) require(number(connection, "SELECT count(*) FROM public." + table) == 0);
                long alpha = college(connection, "SME-ALPHA", "Student Rollout Alpha");
                long beta = college(connection, "SME-BETA", "Student Rollout Beta");
                user(connection, alpha, "Alpha College Admin", "sme-alpha-admin@example.invalid", "COLLEGE_ADMIN",
                        secret("STUDENT_E2E_ALPHA_ADMIN"));
                user(connection, beta, "Beta College Admin", "sme-beta-admin@example.invalid", "COLLEGE_ADMIN",
                        secret("STUDENT_E2E_BETA_ADMIN"));
                user(connection, alpha, "Alpha Faculty", "sme-alpha-faculty@example.invalid", "FACULTY",
                        secret("STUDENT_E2E_FACULTY"));
                connection.commit();
                System.out.println("SEED_COMMITTED=2_COLLEGES|2_COLLEGE_ADMINS|1_FACULTY|3_ROLE_GRANTS");
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void resetAlpha(IsolatedDataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                assertBaseline(connection);
                require(number(connection, "SELECT count(*) FROM public.student_enrollments WHERE ended_at IS NULL") == 1);
                require(number(connection, "SELECT count(*) FROM public.student_enrollments WHERE ended_at IS NOT NULL") == 0);
                require(number(connection, "SELECT count(*) FROM public.student_college_requests WHERE status='APPROVED'") == 2);
                String recordsBefore = studentRecords(connection);
                resetPassword(connection, "sme-alpha-admin@example.invalid", "COLLEGE_ADMIN", secret("STUDENT_E2E_ALPHA_ADMIN"));
                resetPassword(connection, "sme-alpha-student@example.invalid", "STUDENT", secret("STUDENT_E2E_ALPHA_STUDENT"));
                require(recordsBefore.equals(studentRecords(connection)));
                connection.commit();
                System.out.println("ALPHA_PASSWORDS_RESET=2|AFFILIATIONS_AND_ENROLLMENTS_UNCHANGED");
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void resetPassword(Connection connection, String email, String role, String password)
            throws SQLException {
        verifyIdentity(connection);
        try (var update = connection.prepareStatement("""
                UPDATE public.users u SET password=?
                WHERE u.email=? AND u.is_active IS TRUE AND u.is_verified IS TRUE
                  AND EXISTS (SELECT 1 FROM public.colleges c WHERE c.id=u.college_id AND c.code='SME-ALPHA')
                  AND (SELECT array_agg(r.name ORDER BY r.name) FROM public.user_roles ur
                       JOIN public.roles r ON r.id=ur.role_id WHERE ur.user_id=u.id) = ARRAY[?]::varchar[]
                """)) {
            update.setString(1, new BCryptPasswordEncoder().encode(password));
            update.setString(2, email);
            update.setString(3, role);
            require(update.executeUpdate() == 1);
        }
    }

    private static String studentRecords(Connection connection) throws SQLException {
        return scalar(connection, """
                SELECT md5(jsonb_build_array(
                  (SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id) FROM public.student_college_requests r),
                  (SELECT jsonb_agg(to_jsonb(e) ORDER BY e.id) FROM public.student_enrollments e),
                  (SELECT jsonb_agg(to_jsonb(a) ORDER BY a.id) FROM public.privileged_audit_events a))::text)
                """, String.class);
    }

    private static void verify(IsolatedDataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                assertBaseline(connection);
                require("on".equals(scalar(connection, "SHOW transaction_read_only", String.class)));
                for (String table : FIXTURE_TABLES) {
                    System.out.println("ROWS=" + table + "|" + number(connection, "SELECT count(*) FROM public." + table));
                }
                System.out.println("ROWS=users|" + number(connection, "SELECT count(*) FROM public.users"));
                System.out.println("ROWS=user_roles|" + number(connection, "SELECT count(*) FROM public.user_roles"));
                System.out.println("ROWS=privileged_audit_events|" + number(connection, "SELECT count(*) FROM public.privileged_audit_events"));
                System.out.println("APPROVED_AFFILIATIONS=" + number(connection,
                        "SELECT count(*) FROM public.student_college_requests WHERE status='APPROVED'"));
                System.out.println("SCOPED_APPROVAL_BINDINGS=" + number(connection, """
                        SELECT count(*) FROM public.student_college_requests r
                        JOIN public.users u ON u.id=r.student_id AND u.college_id=r.college_id
                        JOIN public.users reviewer ON reviewer.id=r.reviewed_by AND reviewer.college_id=r.college_id
                        WHERE r.status='APPROVED' AND u.is_active IS TRUE AND u.is_verified IS TRUE
                        """));
                System.out.println("CURRENT_ENROLLMENTS=" + number(connection,
                        "SELECT count(*) FROM public.student_enrollments WHERE ended_at IS NULL"));
                System.out.println("HISTORICAL_ENROLLMENTS=" + number(connection,
                        "SELECT count(*) FROM public.student_enrollments WHERE ended_at IS NOT NULL"));
                System.out.println("ALPHA_SCOPED_ENROLLMENTS=" + number(connection, """
                        SELECT count(*) FROM public.student_enrollments e JOIN public.users u ON u.id=e.student_id
                        JOIN public.colleges c ON c.id=e.college_id JOIN public.academic_programs p ON p.id=e.program_id
                        JOIN public.academic_semesters s ON s.id=e.semester_id JOIN public.academic_divisions v ON v.id=e.division_id
                        WHERE c.code='SME-ALPHA' AND u.email='sme-alpha-student@example.invalid'
                          AND u.college_id=c.id AND p.college_id=c.id AND s.college_id=c.id AND v.college_id=c.id
                          AND p.department_id=e.department_id AND s.program_id=p.id AND s.academic_year_id=e.academic_year_id
                          AND v.program_id=p.id AND v.academic_year_id=e.academic_year_id
                        """));
                System.out.println("BASELINE=V1–V13|SUPER_ADMIN_AND_AUDIT_PRESENT");
            } finally {
                connection.rollback();
            }
        }
    }

    private static long college(Connection connection, String code, String name) throws SQLException {
        verifyIdentity(connection);
        try (var insert = connection.prepareStatement("""
                INSERT INTO public.colleges(code,name,is_active) VALUES(?,?,true) RETURNING id
                """)) {
            insert.setString(1,code);
            insert.setString(2,name);
            try (var row = insert.executeQuery()) { require(row.next()); return row.getLong(1); }
        }
    }

    private static void user(Connection connection, long college, String name, String email, String role, String password)
            throws SQLException {
        verifyIdentity(connection);
        try (var insert = connection.prepareStatement("""
                WITH added AS (
                    INSERT INTO public.users(full_name,email,password,is_active,is_verified,college_id,created_at,updated_at)
                    VALUES(?,?,?,true,true,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id
                )
                INSERT INTO public.user_roles(user_id,role_id) SELECT added.id,r.id FROM added,public.roles r WHERE r.name=?
                """)) {
            insert.setString(1,name);
            insert.setString(2,email);
            insert.setString(3,new BCryptPasswordEncoder().encode(password));
            insert.setLong(4,college);
            insert.setString(5,role);
            require(insert.executeUpdate()==1);
        }
    }

    private static void assertBaseline(Connection connection) throws SQLException {
        require(EXPECTED_HISTORY.equals(scalar(connection, HISTORY, String.class)));
        require(number(connection, """
                SELECT count(*) FROM public.users u JOIN public.user_roles ur ON ur.user_id=u.id
                JOIN public.roles r ON r.id=ur.role_id
                JOIN public.privileged_audit_events a ON a.actor_id=u.id AND a.target_id=u.id
                WHERE r.name='SUPER_ADMIN' AND a.action='SUPER_ADMIN_PROVISIONED' AND u.college_id IS NULL
                AND u.is_active IS TRUE AND u.is_verified IS TRUE
                """)==1);
    }

    private static long number(Connection connection, String sql) throws SQLException {
        return scalar(connection, sql, Long.class);
    }

    private static <T> T scalar(Connection connection, String sql, Class<T> type) throws SQLException {
        try (var statement=connection.createStatement(); var row=statement.executeQuery(sql)) {
            require(row.next());
            return row.getObject(1,type);
        }
    }

    private static void verifyIdentity(Connection connection) throws SQLException {
        try (var statement=connection.createStatement(); var row=statement.executeQuery(IDENTITY)) {
            require(row.next());
            StudentE2eMigrationRunner.verifyIdentity(row.getString(1),row.getString(2),row.getInt(3),row.getInt(4),
                    row.getString(5),row.getString(6),row.getString(7),row.getString(8));
        }
    }

    private static String secret(String key) {
        String value=System.getenv(key);
        require(value!=null && !value.isBlank());
        return value;
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("Student E2E safety check refused");
    }

    private static final class IsolatedDataSource extends AbstractDataSource {
        private final String password;
        private IsolatedDataSource(String password) { this.password=password; }

        @Override public Connection getConnection() throws SQLException {
            var options=new Properties();
            options.setProperty("user","postgres");
            options.setProperty("password",password);
            options.setProperty("connectTimeout","5");
            options.setProperty("socketTimeout","25");
            options.setProperty("options","-c search_path=public,pg_catalog -c statement_timeout=15000 -c lock_timeout=5000");
            Connection connection;
            try { connection=DriverManager.getConnection(URL,options); }
            finally { options.clear(); }
            try {
                verifyIdentity(connection);
                return connection;
            } catch (SQLException | RuntimeException failure) {
                try { connection.close(); } catch (SQLException closing) { failure.addSuppressed(closing); }
                throw failure;
            }
        }

        @Override public Connection getConnection(String username,String password) throws SQLException {
            throw new SQLException("Connection overrides are disabled");
        }
    }
}
