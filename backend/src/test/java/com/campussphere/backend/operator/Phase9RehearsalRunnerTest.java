package com.campussphere.backend.operator;

import static com.campussphere.backend.operator.Phase9RehearsalRunner.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Phase9RehearsalRunnerTest {
    @Test void acceptsExactTargetAndBothExplicitStages() {
        verifyIdentity(DATABASE, "127.0.0.1", 5432, 190000, "public");
        assertEquals(Stage.BASELINE, invocation("baseline", DATABASE));
        assertEquals(Stage.V8, invocation("v8", DATABASE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"campus_sphere_dev", "campus_sphere_test", "campus_sphere", "other",
            "jdbc:postgresql://127.0.0.1:5432/campus_sphere_phase9_rehearsal"})
    void refusesOtherTargetsOrUrlArguments(String target) {
        assertThrows(Refusal.class, () -> invocation("v8", target));
        assertThrows(Refusal.class, () -> verifyIdentity(target, "127.0.0.1", 5432, 190000, "public"));
    }

    @Test void refusesExtraArgumentsAndUnknownStage() {
        assertThrows(Refusal.class, () -> invocation("v8", DATABASE, URL));
        assertThrows(Refusal.class, () -> invocation("migrate", DATABASE));
    }

    @Test void rejectsRemoteHost() {
        assertThrows(Refusal.class, () -> verifyIdentity(DATABASE, "192.0.2.5", 5432, 190000, "public"));
    }

    @Test void rejectsWrongPort() {
        assertThrows(Refusal.class, () -> verifyIdentity(DATABASE, "127.0.0.1", 5433, 190000, "public"));
    }

    @Test void rejectsWrongServerVersionAndSchema() {
        assertThrows(Refusal.class, () -> verifyIdentity(DATABASE, "127.0.0.1", 5432, 180000, "public"));
        assertThrows(Refusal.class, () -> verifyIdentity(DATABASE, "127.0.0.1", 5432, 190000, "other"));
    }

    @Test void verifiesActualMigrationSourcesAndRejectsChangedV8() throws Exception {
        try (Stream<Path> paths = Files.list(Path.of("src/main/resources/db/migration"))) {
            var files = paths.sorted().toList();
            assertEquals(8, files.size());
            for (int i = 0; i < files.size(); i++) verifySource(i + 1, Files.readAllBytes(files.get(i)));
            var altered = Files.readAllBytes(files.getLast());
            altered[altered.length - 1] ^= 1;
            assertThrows(Refusal.class, () -> verifySource(8, altered));
        }
    }

    @Test void rejectsIncompleteWrongOrFailedHistory() {
        assertThrows(Refusal.class, () -> verifyHistory(List.of(), 7));
        var correct = List.of(
            new History("1", 57123013, true, "V1__create_identity_schema.sql"),
            new History("2", -2074273504, true, "V2__seed_identity_roles.sql"),
            new History("3", 278880907, true, "V3__create_faculty_access_requests.sql"),
            new History("4", 1437205848, true, "V4__add_college_foundation.sql"),
            new History("5", 1629970249, true, "V5__create_departments.sql"),
            new History("6", 1981309137, true, "V6__extend_college_management.sql"),
            new History("7", -1832066092, true, "V7__add_college_admin_invitations_and_audit.sql"));
        verifyHistory(correct, 7);
        for (History bad : List.of(new History("7", 0, true, correct.getLast().script()),
                new History("7", -1832066092, false, correct.getLast().script()),
                new History("8", -1832066092, true, correct.getLast().script()))) {
            var changed = new java.util.ArrayList<>(correct);
            changed.set(6, bad);
            assertThrows(Refusal.class, () -> verifyHistory(changed, 7));
        }
    }

    @Test void rejectsNonemptySchemaBeforeMigration() throws Exception {
        var checks = mock(Checks.class);
        doAnswer(call -> { verifyEmpty(1); return null; }).when(checks).empty();
        assertThrows(Refusal.class, () -> run(Stage.BASELINE, checks));
        verify(checks).empty();
        verifyNoMoreInteractions(checks);
    }

    @Test void rejectsV8BeforeBaselineVerification() throws Exception {
        var checks = mock(Checks.class);
        doThrow(new Refusal("Missing verified baseline")).when(checks).baseline();
        assertThrows(Refusal.class, () -> run(Stage.V8, checks));
        verify(checks).baseline();
        verifyNoMoreInteractions(checks);
    }

    @Test void rejectsUnexpectedOrMissingPendingMigrations() {
        for (var pending : List.of(List.of("8", "9"), List.of("7", "8"), List.<String>of())) {
            var checks = mock(Checks.class);
            when(checks.pending()).thenReturn(pending);
            assertThrows(Refusal.class, () -> run(Stage.V8, checks));
            verify(checks, never()).migrate();
        }
    }

    @Test void baselineOnlyRunsSevenAndVerifiesBeforeReturning() throws Exception {
        var checks = mock(Checks.class);
        when(checks.pending()).thenReturn(List.of("1","2","3","4","5","6","7"), List.of());
        run(Stage.BASELINE, checks);
        var order = inOrder(checks);
        order.verify(checks).empty();
        order.verify(checks).pending();
        order.verify(checks).migrate();
        order.verify(checks).validate();
        order.verify(checks).pending();
        order.verify(checks).baseline();
        order.verifyNoMoreInteractions();
    }

    @Test void v8RequiresBaselineAndRunsPostMigrationChecks() throws Exception {
        var checks = mock(Checks.class);
        when(checks.pending()).thenReturn(List.of("8"), List.of());
        run(Stage.V8, checks);
        var order = inOrder(checks);
        order.verify(checks).baseline();
        order.verify(checks).pending();
        order.verify(checks).migrate();
        order.verify(checks).validate();
        order.verify(checks).pending();
        order.verify(checks).v8();
        order.verifyNoMoreInteractions();
    }

    @Test void failureStopsWithoutRetryOrFurtherOperations() throws Exception {
        var checks = mock(Checks.class);
        when(checks.pending()).thenReturn(List.of("8"));
        doThrow(new IllegalStateException("simulated failure")).when(checks).migrate();
        assertThrows(IllegalStateException.class, () -> run(Stage.V8, checks));
        verify(checks).baseline();
        verify(checks).pending();
        verify(checks).migrate();
        verifyNoMoreInteractions(checks);
    }
    @Test void preMigrationDiagnosticRetainsSafeRefusalWithoutUncertainty() throws Exception {
        var checks = mock(Checks.class);
        var diagnostic = new Phase9RehearsalDiagnostic();
        doThrow(new Refusal("Unexpected migration history length")).when(checks).baseline();
        var failure = assertThrows(Refusal.class, () -> run(Stage.V8, checks, diagnostic));
        var text = diagnostic.render(failure);
        assertTrue(text.contains("stage=V8"));
        assertTrue(text.contains("checkpoint=BASELINE_VERIFICATION"));
        assertTrue(text.contains("message=Unexpected migration history length"));
        assertTrue(text.contains("migrateInvoked=false"));
        assertTrue(text.contains("readOnlyInspectionRequired=false"));
        verify(checks, never()).migrate();
    }

    @Test void migrationDiagnosticRedactsExternalMessagesAndRetainsSqlMetadata() throws Exception {
        var checks = mock(Checks.class);
        when(checks.pending()).thenReturn(List.of("8"));
        var diagnostic = new Phase9RehearsalDiagnostic();
        var sql = new java.sql.SQLException("sensitive-fixture", "23505", 9);
        sql.setNextException(new java.sql.SQLException("another-sensitive-fixture", "08006", 42));
        var failure = new IllegalStateException("password=sensitive-fixture", sql);
        failure.addSuppressed(new java.io.IOException("sensitive-fixture"));
        doAnswer(call -> { diagnostic.scriptReached = true; throw failure; }).when(checks).migrate();
        var text = diagnostic.render(assertThrows(IllegalStateException.class, () -> run(Stage.V8, checks, diagnostic)));
        assertTrue(text.contains("checkpoint=MIGRATE"));
        assertTrue(text.contains("migrationScriptReached=true"));
        assertTrue(text.contains("migrateReturned=false"));
        assertTrue(text.contains("readOnlyInspectionRequired=true"));
        assertTrue(text.contains("cause=java.sql.SQLException SQLState=23505 vendorCode=9"));
        assertTrue(text.contains("nextSQLException=java.sql.SQLException SQLState=08006 vendorCode=42"));
        assertTrue(text.contains("suppressed=java.io.IOException"));
        assertFalse(text.contains("sensitive-fixture"));
        verify(checks, times(1)).migrate();
        verify(checks, never()).validate();
    }

    @Test void postMigrationFailureRecordsReturnedMigrationAndNeverRetries() throws Exception {
        var checks = mock(Checks.class);
        when(checks.pending()).thenReturn(List.of("8"), List.of());
        doThrow(new Refusal("Required V8 indexes missing or invalid")).when(checks).v8();
        var diagnostic = new Phase9RehearsalDiagnostic();
        var text = diagnostic.render(assertThrows(Refusal.class, () -> run(Stage.V8, checks, diagnostic)));
        assertTrue(text.contains("checkpoint=POST_MIGRATION_SCHEMA"));
        assertTrue(text.contains("migrateInvoked=true"));
        assertTrue(text.contains("migrateReturned=true"));
        assertTrue(text.contains("jdbcRollbackAttempted=false"));
        assertTrue(text.contains("databaseStateUncertain=true"));
        verify(checks, times(1)).migrate();
        verify(checks, times(1)).validate();
        verify(checks, times(1)).v8();
    }

    @Test void reportsObservedJdbcRollbackAndSeparatelyRetainsRollbackFailure() throws Exception {
        var connection = mock(java.sql.Connection.class);
        var diagnostic = new Phase9RehearsalDiagnostic();
        var observed = diagnostic.observeRollback(connection);
        var rollbackError = new java.sql.SQLException("sensitive-fixture", "08006", 3);
        doThrow(rollbackError).when(connection).rollback();
        assertSame(rollbackError, assertThrows(java.sql.SQLException.class, observed::rollback));
        var text = diagnostic.render(new IllegalStateException("driver failure"));
        assertTrue(text.contains("jdbcRollbackAttempted=true"));
        assertTrue(text.contains("rollbackFailure=java.sql.SQLException SQLState=08006 vendorCode=3"));
        assertFalse(text.contains("sensitive-fixture"));
        verify(connection).rollback();
    }

    @Test void writesSanitizedReportBeforeConsoleCloses(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        var report = temp.resolve("failure.log");
        var diagnostic = new Phase9RehearsalDiagnostic();
        diagnostic.publish(report, new java.io.IOException("secret-fixture"));
        assertTrue(Files.readString(report).contains("checkpoint=INVOCATION"));
        assertFalse(Files.readString(report).contains("secret-fixture"));
    }

    @ParameterizedTest
    @ValueSource(longs = {19, 20, 21})
    void verifiesRegistrationCheckCountWithoutCountingAuditConstraint(long count) throws Exception {
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.Statement.class);
        var row = mock(java.sql.ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = 'public.college_registration_requests'::regclass
                  AND contype = 'c' AND convalidated
                """)).thenReturn(row);
        when(row.next()).thenReturn(true);
        when(row.getObject(1, Long.class)).thenReturn(count);

        if (count == 20) {
            assertDoesNotThrow(() -> verifyRegistrationCheckCount(connection));
        } else {
            var refusal = assertThrows(Refusal.class, () -> verifyRegistrationCheckCount(connection));
            assertEquals("Expected 20 validated V8 registration CHECK constraints, found " + count,
                    refusal.getMessage());
        }
    }
}
