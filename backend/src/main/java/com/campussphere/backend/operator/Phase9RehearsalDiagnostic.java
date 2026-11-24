package com.campussphere.backend.operator;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

final class Phase9RehearsalDiagnostic {
    enum Checkpoint {
        INVOCATION, CONSOLE, CONFIRMATION, PASSWORD_INPUT, SOURCE_VERIFICATION,
        CONTROL_CONNECTION, ADVISORY_LOCK, FLYWAY_CONFIGURATION, EMPTY_SCHEMA,
        BASELINE_VERIFICATION, PENDING_MIGRATIONS, MIGRATE, FLYWAY_VALIDATION,
        POST_MIGRATION_PENDING, POST_MIGRATION_SCHEMA, CLOSE_CONNECTION, REMOVE_SNAPSHOT
    }

    String stage = "UNRESOLVED";
    Checkpoint checkpoint = Checkpoint.INVOCATION;
    String connectionStep = "none";
    boolean migrateInvoked;
    boolean scriptReached;
    boolean migrateReturned;
    boolean rollbackAttempted;
    SQLException rollbackFailure;

    Connection observeRollback(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    boolean rollback = method.getName().equals("rollback");
                    if (rollback) rollbackAttempted = true;
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException failure) {
                        Throwable cause = failure.getCause();
                        if (rollback && cause instanceof SQLException sql) rollbackFailure = sql;
                        throw cause;
                    }
                });
    }

    String render(Throwable failure) {
        var report = new StringBuilder("Rehearsal stopped")
                .append("\nstage=").append(stage)
                .append("\ncheckpoint=").append(checkpoint)
                .append("\nconnectionStep=").append(connectionStep)
                .append("\nmigrateInvoked=").append(migrateInvoked)
                .append("\nmigrationScriptReached=").append(scriptReached)
                .append("\nmigrateReturned=").append(migrateReturned)
                .append("\njdbcRollbackAttempted=").append(rollbackAttempted)
                .append("\ndatabaseStateUncertain=").append(migrateInvoked)
                .append("\nreadOnlyInspectionRequired=").append(migrateInvoked).append('\n');
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        append(report, "exception", failure, seen);
        append(report, "rollbackFailure", rollbackFailure, seen);
        return report.toString();
    }

    void publish(Path reportFile, Throwable failure) {
        String report = render(failure);
        System.err.print(report);
        if (reportFile != null) {
            try {
                Files.writeString(reportFile, report);
                System.err.println("Diagnostic file: " + reportFile);
            } catch (IOException | SecurityException ignored) {
                System.err.println("Diagnostic file unavailable. Preserve the console output.");
            }
        }
    }

    private static void append(StringBuilder report, String relation, Throwable failure, Set<Throwable> seen) {
        if (failure == null || !seen.add(failure)) return;
        report.append(relation).append('=').append(failure.getClass().getName());
        if (failure instanceof SQLException sql) {
            String state = sql.getSQLState();
            report.append(" SQLState=").append(state != null && state.matches("[A-Z0-9]{5}") ? state : "unavailable")
                    .append(" vendorCode=").append(sql.getErrorCode());
        }
        // Only runner-authored refusals may expose original text. Driver, SQL,
        // filesystem and framework messages can carry values or credentials.
        report.append(" message=").append(failure instanceof Phase9RehearsalRunner.Refusal
                ? failure.getMessage() : "[redacted external message]").append('\n');
        append(report, "cause", failure.getCause(), seen);
        for (Throwable suppressed : failure.getSuppressed()) append(report, "suppressed", suppressed, seen);
        if (failure instanceof SQLException sql) append(report, "nextSQLException", sql.getNextException(), seen);
    }
}
