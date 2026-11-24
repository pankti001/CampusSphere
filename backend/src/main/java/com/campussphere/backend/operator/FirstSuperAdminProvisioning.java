package com.campussphere.backend.operator;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.stream.IntStream;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class FirstSuperAdminProvisioning {
    private static final String DATABASE = "campus_sphere_dev";
    private static final String DATABASE_ROLE = "postgres";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
    private static final int[] EXPECTED_HISTORY = IntStream.rangeClosed(1, 7).toArray();

    private FirstSuperAdminProvisioning() {}

    public static void main(String[] args) {
        var console = System.console();
        if (console == null || !Arrays.equals(args, new String[] {DATABASE})) {
            printDiagnostic(Diagnostic.INVALID_INVOCATION);
            System.exit(Diagnostic.INVALID_INVOCATION.exitCode);
            return;
        }

        char[] dbPassword = null;
        char[] password = null;
        char[] confirmation = null;
        int exit = 0;
        try {
            dbPassword = console.readPassword("Local PostgreSQL password (masked): ");
            String name = console.readLine("Super Admin full name: ");
            String email = console.readLine("Super Admin institutional email: ");
            password = console.readPassword("New Super Admin password: ");
            confirmation = console.readPassword("Confirm new Super Admin password: ");
            String passwordHash = encodePassword(password, confirmation);
            console.writer().println(Diagnostic.PASSWORD_VALID.name());
            runProvisioning(DriverManager::getConnection, dbPassword, name, email, passwordHash);
            console.writer().println("First Super Admin provisioned.");
        } catch (SQLException | RuntimeException error) {
            var diagnostic = diagnosticFor(error, false);
            printDiagnostic(diagnostic);
            exit = diagnostic.exitCode;
        } finally {
            clear(dbPassword);
            clear(password);
            clear(confirmation);
        }
        if (exit != 0) System.exit(exit);
    }

    @FunctionalInterface
    interface ConnectionOpener {
        Connection open(String url, String role, String password) throws SQLException;
    }

    static void runProvisioning(ConnectionOpener opener, char[] dbPassword, String name, String email,
                                String passwordHash) throws SQLException {
        Connection connection;
        try {
            if (dbPassword == null || dbPassword.length == 0) {
                throw new Refused(Diagnostic.DATABASE_PASSWORD_MISSING);
            }
            connection = opener.open(URL, DATABASE_ROLE, new String(dbPassword));
        } catch (SQLException error) {
            throw new Refused(diagnosticFor(error, true));
        } finally {
            clear(dbPassword);
        }

        try (connection) {
            provision(connection, name, email, passwordHash);
        }
    }

    static void provision(Connection connection, String name, String email, String passwordHash) throws SQLException {
        if (name == null || name.isBlank() || name.strip().length() > 100 || email == null) {
            throw new Refused(Diagnostic.INVALID_IDENTITY_INPUT);
        }
        String normalizedEmail = ProvisioningEmail.normalize(email);
        if (!ProvisioningEmail.valid(normalizedEmail)) {
            throw new Refused(Diagnostic.INVALID_IDENTITY_INPUT);
        }

        connection.setAutoCommit(false);
        try {
            verifySafety(connection);
            if (exists(connection, """
                    SELECT 1 FROM public.privileged_audit_events WHERE action = 'SUPER_ADMIN_PROVISIONED'
                    UNION ALL SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
                    WHERE r.name = 'SUPER_ADMIN'
                    """)) {
                throw new Refused(Diagnostic.ALREADY_PROVISIONED);
            }
            if (exists(connection, "SELECT 1 FROM public.users WHERE lower(email) = ?", normalizedEmail)) {
                throw new Refused(Diagnostic.DUPLICATE_EMAIL);
            }
            long userId;
            try (var statement = connection.prepareStatement("""
                    INSERT INTO public.users (full_name, email, password, is_active, is_verified, created_at, updated_at)
                    VALUES (?, ?, ?, TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    RETURNING id
                    """)) {
                statement.setString(1, name.strip());
                statement.setString(2, normalizedEmail);
                statement.setString(3, passwordHash);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("Account insert returned no id");
                    userId = result.getLong(1);
                }
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO public.user_roles (user_id, role_id)
                    SELECT ?, id FROM public.roles WHERE name = 'SUPER_ADMIN'
                    """)) {
                statement.setLong(1, userId);
                if (statement.executeUpdate() != 1) throw new Refused(Diagnostic.DATABASE_SAFETY);
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO public.privileged_audit_events (actor_id, action, target_id)
                    VALUES (?, 'SUPER_ADMIN_PROVISIONED', ?)
                    """)) {
                statement.setLong(1, userId);
                statement.setLong(2, userId);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        }
    }

    private static void verifySafety(Connection connection) {
        try {
            verifyTarget(connection);
            try (var statement = connection.createStatement()) {
                // Serialize operator executions and hold the V7 baseline stable until commit.
                statement.execute("SELECT pg_advisory_xact_lock(5432, 573)");
                statement.execute("LOCK TABLE public.flyway_schema_history IN SHARE MODE");
            }
            verifyHistory(connection);
        } catch (SQLException error) {
            throw new Refused(Diagnostic.DATABASE_SAFETY);
        }
    }

    private static void verifyTarget(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT current_database() = 'campus_sphere_dev'
                        AND current_user = 'postgres' AND session_user = 'postgres'
                        AND inet_server_addr() = inet '127.0.0.1'
                        AND inet_server_port() = 5432
                        AND current_setting('server_version_num')::int / 10000 = 19
                     """)) {
            if (!result.next() || !result.getBoolean(1)) {
                throw new Refused(Diagnostic.DATABASE_SAFETY);
            }
        }
    }

    private static void verifyHistory(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT array_agg(version::int ORDER BY installed_rank)
                         FILTER (WHERE version ~ '^[1-7]$' AND success),
                       count(*) FROM public.flyway_schema_history
                     """)) {
            if (!result.next() || result.getLong(2) != EXPECTED_HISTORY.length) {
                throw new Refused(Diagnostic.DATABASE_SAFETY);
            }
            var versions = result.getArray(1);
            Integer[] expected = IntStream.of(EXPECTED_HISTORY).boxed().toArray(Integer[]::new);
            if (versions == null || !Arrays.equals((Object[]) versions.getArray(), expected)) {
                throw new Refused(Diagnostic.DATABASE_SAFETY);
            }
        }
    }

    private static boolean exists(Connection connection, String sql, String... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    static String encodePassword(char[] password, char[] confirmation) {
        try {
            String value = password == null || confirmation == null ? null : new String(password);
            var diagnostic = passwordDiagnostic(value, Arrays.equals(password, confirmation));
            if (diagnostic != Diagnostic.PASSWORD_VALID) {
                throw new Refused(diagnostic);
            }
            return new BCryptPasswordEncoder().encode(value);
        } finally {
            clear(password);
            clear(confirmation);
        }
    }

    static Diagnostic passwordDiagnostic(String value, boolean confirmed) {
        if (value == null) return Diagnostic.PASSWORD_MISSING;
        if (value.isBlank()) return Diagnostic.PASSWORD_EMPTY;
        if (value.length() < 10) return Diagnostic.PASSWORD_TOO_SHORT;
        if (!confirmed) return Diagnostic.PASSWORD_CONFIRMATION_MISMATCH;
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        try {
            return utf8.length > 72 ? Diagnostic.PASSWORD_TOO_LONG : Diagnostic.PASSWORD_VALID;
        } finally {
            Arrays.fill(utf8, (byte) 0);
        }
    }

    private static void clear(char[] secret) {
        if (secret != null) Arrays.fill(secret, '\0');
    }

    static Diagnostic diagnosticFor(Exception error, boolean connecting) {
        if (error instanceof Refused refused) return refused.diagnostic;
        if (error instanceof SQLException sql) {
            if (!connecting) return Diagnostic.TRANSACTION_FAILURE;
            String state = sql.getSQLState();
            if (state == null) return Diagnostic.DATABASE_CONNECTION;
            if (state.startsWith("28")) return Diagnostic.DATABASE_AUTHENTICATION;
            if (state.equals("3D000") || state.equals("08004")) return Diagnostic.DATABASE_CONNECTION_REJECTED;
            if (state.startsWith("08")) return Diagnostic.DATABASE_TRANSPORT;
            return Diagnostic.DATABASE_CONNECTION;
        }
        return Diagnostic.INTERNAL_FAILURE;
    }

    private static void printDiagnostic(Diagnostic diagnostic) {
        String code = diagnostic.name();
        String prefix = code.startsWith("PASSWORD_") ? "" : "PROVISION_";
        System.err.println(prefix + code + " (exit " + diagnostic.exitCode + "): "
                + diagnostic.explanation);
    }

    enum Diagnostic {
        INVALID_INVOCATION(2, "Use an interactive console and explicitly select campus_sphere_dev."),
        PASSWORD_VALID(0, "The password passed validation."),
        PASSWORD_MISSING(10, "The password or confirmation prompt did not receive input."),
        PASSWORD_CONFIRMATION_MISMATCH(11, "The password and confirmation did not match."),
        PASSWORD_EMPTY(19, "Enter a nonblank password."),
        PASSWORD_TOO_SHORT(20, "The password does not meet the minimum length."),
        PASSWORD_TOO_LONG(21, "The password exceeds the BCrypt UTF-8 byte limit."),
        DATABASE_AUTHENTICATION(22, "PostgreSQL rejected authentication for the approved database role."),
        DATABASE_CONNECTION_REJECTED(23, "PostgreSQL refused the connection or the target database is unavailable."),
        DATABASE_TRANSPORT(24, "JDBC reported a connection or transport error."),
        DATABASE_PASSWORD_MISSING(25, "No database password was supplied."),
        DATABASE_CONNECTION(12, "A connection to the approved local database could not be opened."),
        DATABASE_SAFETY(13, "The database identity, migration history, or required role could not be verified."),
        ALREADY_PROVISIONED(14, "An existing Super Admin grant or provisioning event blocks this operation."),
        DUPLICATE_EMAIL(15, "The email belongs to an existing account."),
        TRANSACTION_FAILURE(16, "The database transaction or connection cleanup reported a failure. Verify the outcome before retrying."),
        INTERNAL_FAILURE(17, "An unexpected error occurred. Verify the outcome before retrying."),
        INVALID_IDENTITY_INPUT(18, "A valid name and institutional email are required.");

        final int exitCode;
        final String explanation;

        Diagnostic(int exitCode, String explanation) {
            this.exitCode = exitCode;
            this.explanation = explanation;
        }
    }

    static final class Refused extends RuntimeException {
        final Diagnostic diagnostic;

        Refused(Diagnostic diagnostic) {
            super(diagnostic.explanation);
            this.diagnostic = diagnostic;
        }
    }
}
