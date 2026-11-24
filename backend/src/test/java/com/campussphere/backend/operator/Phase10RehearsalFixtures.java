package com.campussphere.backend.operator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.List;

import com.campussphere.backend.operator.Phase9RehearsalRunner.History;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Interactive, test-classpath-only seed for the exact Phase 10 manifest. */
public final class Phase10RehearsalFixtures {
    private static final String TARGET = Phase9RehearsalRunner.DATABASE;
    private static final String CONFIRMATION = "SEED PHASE10 " + TARGET;
    private static final List<String> EMPTY_TABLES = List.of("users", "user_roles", "colleges", "departments",
            "faculty_access_requests", "college_admin_invitations", "privileged_audit_events",
            "college_registration_requests");

    private Phase10RehearsalFixtures() {}

    public static void main(String[] args) {
        int status = 1;
        Path report = null;
        var diagnostic = new Diagnostic();
        char[] databasePassword = null;
        char[] reviewerPassword = null;
        try {
            report = Files.createTempFile("campussphere-phase10-fixture-", ".log");
            System.err.println("Fixture diagnostic file: " + report);
            var console = System.console();
            if (console == null || !acceptsTarget(args)) throw new Refusal("Interactive console and exact target required");
            if (!CONFIRMATION.equals(console.readLine("Type %s to confirm: ", CONFIRMATION)))
                throw new Refusal("Fixture confirmation refused");
            diagnostic.stage = "FIXTURE_PREPARATION";
            databasePassword = console.readPassword("Local PostgreSQL password: ");
            reviewerPassword = console.readPassword("New synthetic reviewer password: ");
            if (databasePassword == null || databasePassword.length == 0 || reviewerPassword == null
                    || reviewerPassword.length < 12 || reviewerPassword.length > 72)
                throw new Refusal("Missing or invalid fixture password input");
            String reviewerSecret = new String(reviewerPassword);
            if (reviewerSecret.isBlank() || reviewerSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
                throw new Refusal("Invalid fixture password input");
            String hash = new BCryptPasswordEncoder().encode(reviewerSecret);
            diagnostic.stage = "CONNECTION";
            try (Connection connection = DriverManager.getConnection(Phase9RehearsalRunner.URL,
                    "postgres", new String(databasePassword))) {
                seed(connection, hash, diagnostic);
                diagnostic.stage = "CONNECTION_CLOSE";
            }
            diagnostic.stage = "COMPLETE";
            Files.writeString(report, diagnostic.render(null));
            System.out.println("Phase 10 fixture rows committed: one reviewer, one role link, one Beta college and one Beta department.");
            status = 0;
        } catch (RuntimeException | SQLException | IOException failure) {
            String evidence = diagnostic.render(failure);
            System.err.print(evidence);
            if (report != null) {
                try {
                    Files.writeString(report, evidence);
                } catch (IOException | SecurityException ignored) {
                    System.err.println("Fixture diagnostic file could not be written.");
                }
            }
        } finally {
            if (databasePassword != null) Arrays.fill(databasePassword, '\0');
            if (reviewerPassword != null) Arrays.fill(reviewerPassword, '\0');
        }
        if (status != 0) System.exit(status);
    }

    static boolean acceptsTarget(String... args) {
        return args.length == 1 && TARGET.equals(args[0]);
    }

    interface SeedSteps {
        void verify() throws SQLException;
        void insert(String passwordHash) throws SQLException;
    }

    static void transact(Connection connection, SeedSteps steps, String passwordHash) throws SQLException {
        transact(connection, steps, passwordHash, new Diagnostic());
    }

    static void transact(Connection connection, SeedSteps steps, String passwordHash, Diagnostic diagnostic) throws SQLException {
        diagnostic.stage = "TRANSACTION_SETUP";
        connection.setAutoCommit(false);
        try {
            diagnostic.stage = "PRECONDITION";
            steps.verify();
            diagnostic.stage = "TRANSACTION_INSERTS";
            diagnostic.insertsEntered = true;
            steps.insert(passwordHash);
            diagnostic.stage = "COMMIT";
            diagnostic.commitAttempted = true;
            connection.commit();
            diagnostic.committed = true;
        } catch (SQLException | RuntimeException failure) {
            diagnostic.rollbackAttempted = true;
            try {
                connection.rollback();
                diagnostic.rollbackReturned = true;
            } catch (SQLException | RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private static void seed(Connection connection, String hash, Diagnostic diagnostic) throws SQLException {
        transact(connection, new SeedSteps() {
            @Override public void verify() throws SQLException {
                verifyDatabase(connection);
            }

            @Override public void insert(String passwordHash) throws SQLException {
                long reviewer = insertId(connection, """
                        INSERT INTO public.users (full_name,email,password,is_active,is_verified,created_at,updated_at)
                        VALUES ('Phase 10 Synthetic Reviewer','reviewer.p10@example.invalid',?,true,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        RETURNING id
                        """, passwordHash);
                try (var statement = connection.prepareStatement("""
                        INSERT INTO public.user_roles(user_id,role_id)
                        SELECT ?,id FROM public.roles WHERE name='SUPER_ADMIN'
                        """)) {
                    statement.setLong(1, reviewer);
                    require(statement.executeUpdate() == 1, "Expected one reviewer role grant");
                }
                long beta = insertId(connection, """
                        INSERT INTO public.colleges (name,code,is_active)
                        VALUES ('Phase 10 Beta Institute','P10-BETA',true) RETURNING id
                        """);
                insertId(connection, """
                        INSERT INTO public.departments (name,code,college_id)
                        VALUES ('Beta Rehearsal Department','P10-BETA-DEPT',?) RETURNING id
                        """, beta);
            }
        }, hash, diagnostic);
    }

    private static void verifyDatabase(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("""
                     SELECT current_database(),host(inet_server_addr()),inet_server_port(),
                            current_setting('server_version_num')::integer,current_schema(),current_user,session_user
                     """)) {
            require(row.next(), "Missing server identity");
            Phase9RehearsalRunner.verifyIdentity(row.getString(1), row.getString(2), row.getInt(3), row.getInt(4), row.getString(5));
            require("postgres".equals(row.getString(6)) && "postgres".equals(row.getString(7)), "Unexpected role");
        }

        // Hold write-excluding locks across baseline inspection and seeding.
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '5s'");
            statement.execute("LOCK TABLE public.flyway_schema_history, public.roles, public."
                    + String.join(", public.", EMPTY_TABLES) + " IN SHARE ROW EXCLUSIVE MODE");
        }
        var history = new java.util.ArrayList<History>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT version,checksum,success,script FROM public.flyway_schema_history ORDER BY installed_rank
                     """)) {
            while (rows.next()) history.add(new History(rows.getString(1), rows.getObject(2, Integer.class),
                    rows.getBoolean(3), rows.getString(4)));
        }
        Phase9RehearsalRunner.verifyHistory(history, 8);
        require("ADMIN,COLLEGE_ADMIN,FACULTY,STUDENT,SUPER_ADMIN".equals(Phase9RehearsalRunner.scalar(connection,
                "SELECT string_agg(name, ',' ORDER BY name COLLATE \"C\") FROM public.roles", String.class)),
                "Unexpected role seeds");
        for (String table : EMPTY_TABLES) {
            require(Boolean.FALSE.equals(Phase9RehearsalRunner.scalar(connection,
                    "SELECT EXISTS (SELECT 1 FROM public." + table + ")", Boolean.class)),
                    "Expected empty application table");
        }
    }

    private static long insertId(Connection connection, String sql, Object... bindings) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < bindings.length; index++) statement.setObject(index + 1, bindings[index]);
            try (var result = statement.executeQuery()) {
                require(result.next(), "Fixture insert did not return an ID");
                long id = result.getLong(1);
                require(id > 0 && !result.next(), "Unexpected fixture insert result");
                return id;
            }
        }
    }

    static final class Diagnostic {
        String stage = "PRECONDITION";
        boolean insertsEntered;
        boolean commitAttempted;
        boolean committed;
        boolean rollbackAttempted;
        boolean rollbackReturned;

        String render(Throwable failure) {
            String outcome;
            if (committed) outcome = "COMMITTED";
            else if (commitAttempted || (insertsEntered && !rollbackReturned)) outcome = "UNCERTAIN";
            else if (rollbackReturned) outcome = "ROLLED_BACK";
            else outcome = "NO_FIXTURE_WRITES_ATTEMPTED";
            var report = new StringBuilder("stage=").append(stage)
                    .append("\ntransactionOutcome=").append(outcome)
                    .append("\ninsertsEntered=").append(insertsEntered)
                    .append("\ncommitAttempted=").append(commitAttempted)
                    .append("\nrollbackAttempted=").append(rollbackAttempted)
                    .append("\nrollbackReturned=").append(rollbackReturned).append('\n');
            append(report, "exception", failure, Collections.newSetFromMap(new IdentityHashMap<>()));
            return report.toString();
        }

        private static void append(StringBuilder report, String relation, Throwable failure, Set<Throwable> seen) {
            if (failure == null || !seen.add(failure)) return;
            report.append(relation).append('=').append(failure.getClass().getName());
            String message = "[redacted external message]";
            if (failure instanceof Refusal || failure instanceof Phase9RehearsalRunner.Refusal) {
                message = failure.getMessage();
            } else if (failure instanceof SQLException sql) {
                String state = sql.getSQLState();
                report.append(" SQLState=").append(state != null && state.matches("[A-Z0-9]{5}") ? state : "unavailable")
                        .append(" vendorCode=").append(sql.getErrorCode());
                if ("28P01".equals(state)) message = "PostgreSQL refused password authentication";
                else if ("55P03".equals(state)) message = "PostgreSQL could not acquire the required lock";
                else if ("23505".equals(state)) message = "PostgreSQL rejected a duplicate key";
            }
            report.append(" message=").append(message).append('\n');
            append(report, "cause", failure.getCause(), seen);
            for (Throwable suppressed : failure.getSuppressed()) append(report, "suppressed", suppressed, seen);
            if (failure instanceof SQLException sql) append(report, "nextSQLException", sql.getNextException(), seen);
        }
    }

    private static final class Refusal extends IllegalStateException {
        Refusal(String message) { super(message); }
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new Refusal(message);
    }
}
