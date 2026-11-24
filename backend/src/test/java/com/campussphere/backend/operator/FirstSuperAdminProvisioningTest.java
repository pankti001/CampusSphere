package com.campussphere.backend.operator;

import static com.campussphere.backend.operator.FirstSuperAdminProvisioning.Diagnostic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class FirstSuperAdminProvisioningTest {
    @Test
    void hashesAConfirmedPassword() {
        String synthetic = "Abcdef12!#";
        char[] secret = synthetic.toCharArray();
        char[] confirmation = secret.clone();
        String hash = FirstSuperAdminProvisioning.encodePassword(secret, confirmation);
        assertNotEquals(synthetic, hash);
        assertTrue(new BCryptPasswordEncoder().matches(synthetic, hash));
        assertArrayEquals(new char[secret.length], secret);
        assertArrayEquals(new char[confirmation.length], confirmation);
    }

    @Test
    void rejectsWeakMismatchedAndOversizedInput() {
        char[] secret = "synthetic-only-password".toCharArray();
        char[] oversized = "é".repeat(40).toCharArray();
        assertThrows(FirstSuperAdminProvisioning.Refused.class,
                () -> FirstSuperAdminProvisioning.encodePassword(secret, new char[16]));
        assertThrows(FirstSuperAdminProvisioning.Refused.class,
                () -> FirstSuperAdminProvisioning.encodePassword(new char[4], new char[4]));
        assertThrows(FirstSuperAdminProvisioning.Refused.class,
                () -> FirstSuperAdminProvisioning.encodePassword(oversized, oversized));
    }

    @Test
    void distinguishesMissingEmptyShortAndOversizedPasswords() {
        String valid = "a".repeat(10);
        assertPasswordDiagnostic(PASSWORD_MISSING, null, valid.toCharArray());
        assertPasswordDiagnostic(PASSWORD_MISSING, valid.toCharArray(), null);
        assertPasswordDiagnostic(PASSWORD_EMPTY, new char[0], new char[0]);
        char[] blank = " ".repeat(16).toCharArray();
        assertPasswordDiagnostic(PASSWORD_EMPTY, blank, blank);
        char[] shortPassword = "a".repeat(9).toCharArray();
        assertPasswordDiagnostic(PASSWORD_TOO_SHORT, shortPassword, shortPassword);
        char[] oversized = "é".repeat(37).toCharArray();
        assertPasswordDiagnostic(PASSWORD_TOO_LONG, oversized, oversized);
    }

    @Test
    void reportsValidAtCharacterAndUtf8ByteBoundaries() {
        assertEquals(PASSWORD_VALID, FirstSuperAdminProvisioning.passwordDiagnostic("a".repeat(10), true));
        assertEquals(PASSWORD_VALID, FirstSuperAdminProvisioning.passwordDiagnostic("a".repeat(16), true));
        assertEquals(PASSWORD_VALID, FirstSuperAdminProvisioning.passwordDiagnostic("é".repeat(36), true));
    }

    private void assertPasswordDiagnostic(FirstSuperAdminProvisioning.Diagnostic expected,
                                          char[] password, char[] confirmation) {
        var error = assertThrows(FirstSuperAdminProvisioning.Refused.class,
                () -> FirstSuperAdminProvisioning.encodePassword(password, confirmation));
        assertEquals(expected, error.diagnostic);
        if (password != null) assertArrayEquals(new char[password.length], password);
        if (confirmation != null) assertArrayEquals(new char[confirmation.length], confirmation);
    }

    @Test
    void reportsConfirmationMismatchSeparately() {
        assertPasswordDiagnostic(PASSWORD_CONFIRMATION_MISMATCH,
                "a".repeat(10).toCharArray(), "b".repeat(10).toCharArray());
    }

    @Test
    void distinguishesConnectionTransactionAndInternalFailuresWithoutRawDetails() {
        var error = new java.sql.SQLException("synthetic-private-details");
        assertEquals(DATABASE_CONNECTION, FirstSuperAdminProvisioning.diagnosticFor(error, true));
        assertEquals(TRANSACTION_FAILURE, FirstSuperAdminProvisioning.diagnosticFor(error, false));
        assertEquals(INTERNAL_FAILURE, FirstSuperAdminProvisioning.diagnosticFor(
                new IllegalStateException(error.getMessage()), false));
        for (var diagnostic : FirstSuperAdminProvisioning.Diagnostic.values()) {
            assertFalse(diagnostic.explanation.contains(error.getMessage()));
        }
    }

    @Test
    void diagnosticExitCodesAreDistinct() {
        var diagnostics = FirstSuperAdminProvisioning.Diagnostic.values();
        assertEquals(diagnostics.length, java.util.Arrays.stream(diagnostics)
                .mapToInt(diagnostic -> diagnostic.exitCode).distinct().count());
    }

    @Test
    void opensOnceWithPinnedRoleAndReusesConnectionForChecksAndWrites() throws Exception {
        var opener = mock(FirstSuperAdminProvisioning.ConnectionOpener.class);
        var connection = readyConnection();
        var audit = prepareWrites(connection);
        char[] dbPassword = "synthetic-db-password".toCharArray();
        when(opener.open(anyString(), anyString(), anyString())).thenReturn(connection);

        FirstSuperAdminProvisioning.runProvisioning(opener, dbPassword,
                "Synthetic Operator", "operator@example.invalid", "unused");

        verify(opener).open(eq("jdbc:postgresql://127.0.0.1:5432/campus_sphere_dev"), eq("postgres"), anyString());
        verifyNoMoreInteractions(opener);
        verify(connection).setAutoCommit(false);
        var statement = connection.createStatement();
        verify(statement).executeQuery(contains("current_database() = 'campus_sphere_dev'"));
        verify(statement).execute("SELECT pg_advisory_xact_lock(5432, 573)");
        verify(statement).executeQuery(contains("FROM public.flyway_schema_history"));
        verify(connection).prepareStatement(contains("lower(email)"));
        var order = inOrder(audit, connection);
        order.verify(audit).executeUpdate();
        order.verify(connection).commit();
        order.verify(connection).close();
        assertArrayEquals(new char[dbPassword.length], dbPassword);
    }

    @Test
    void rejectedAuthenticationHasFixedCodeAndClearsDatabasePassword() throws Exception {
        var opener = mock(FirstSuperAdminProvisioning.ConnectionOpener.class);
        char[] dbPassword = "synthetic-db-password".toCharArray();
        when(opener.open(anyString(), anyString(), anyString()))
                .thenThrow(new java.sql.SQLException("synthetic-private-details", "28P01"));

        var error = assertThrows(FirstSuperAdminProvisioning.Refused.class, () ->
                FirstSuperAdminProvisioning.runProvisioning(opener, dbPassword, "Synthetic Operator",
                        "operator@example.invalid", "unused"));

        assertEquals(DATABASE_AUTHENTICATION, error.diagnostic);
        assertFalse(error.getMessage().contains("synthetic-private-details"));
        assertArrayEquals(new char[dbPassword.length], dbPassword);
        verify(opener).open(anyString(), anyString(), anyString());
        verifyNoMoreInteractions(opener);
    }

    @Test
    void missingDatabasePasswordNeverOpensConnection() {
        var opener = mock(FirstSuperAdminProvisioning.ConnectionOpener.class);
        var error = assertThrows(FirstSuperAdminProvisioning.Refused.class, () ->
                FirstSuperAdminProvisioning.runProvisioning(opener, new char[0], "Synthetic Operator",
                        "operator@example.invalid", "unused"));
        assertEquals(DATABASE_PASSWORD_MISSING, error.diagnostic);
        verifyNoInteractions(opener);
    }

    @Test
    void mapsConnectionRejectionAndTransportWithoutChangingWriteErrorCategory() {
        assertEquals(DATABASE_CONNECTION_REJECTED, FirstSuperAdminProvisioning.diagnosticFor(
                new java.sql.SQLException("synthetic private details", "08004"), true));
        assertEquals(DATABASE_CONNECTION_REJECTED, FirstSuperAdminProvisioning.diagnosticFor(
                new java.sql.SQLException("synthetic private details", "3D000"), true));
        var transport = new java.sql.SQLException("synthetic private details", "08001");
        assertEquals(DATABASE_TRANSPORT, FirstSuperAdminProvisioning.diagnosticFor(transport, true));
        assertEquals(TRANSACTION_FAILURE, FirstSuperAdminProvisioning.diagnosticFor(transport, false));
    }

    @Test
    void wrongTargetRollsBackBeforePreparingAnyWrite() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(false);

        var error = assertThrows(FirstSuperAdminProvisioning.Refused.class, () -> FirstSuperAdminProvisioning.provision(
                connection, "Synthetic Operator", "operator@example.invalid", "unused"));
        assertEquals(DATABASE_SAFETY, error.diagnostic);
        verify(connection).rollback();
        verify(connection, never()).prepareStatement(anyString());
        verify(connection, never()).commit();
    }

    @Test
    void priorProvisioningRefusesWithoutInsertingAUser() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet target = mock(ResultSet.class);
        ResultSet history = mock(ResultSet.class);
        Array versions = mock(Array.class);
        PreparedStatement existing = mock(PreparedStatement.class);
        ResultSet grant = mock(ResultSet.class);

        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(target, history);
        when(target.next()).thenReturn(true);
        when(target.getBoolean(1)).thenReturn(true);
        when(history.next()).thenReturn(true);
        when(history.getLong(2)).thenReturn(7L);
        when(history.getArray(1)).thenReturn(versions);
        when(versions.getArray()).thenReturn(new Integer[] {1, 2, 3, 4, 5, 6, 7});
        when(connection.prepareStatement(contains("SUPER_ADMIN_PROVISIONED"))).thenReturn(existing);
        when(existing.executeQuery()).thenReturn(grant);
        when(grant.next()).thenReturn(true);

        var error = assertThrows(FirstSuperAdminProvisioning.Refused.class, () -> FirstSuperAdminProvisioning.provision(
                connection, "Synthetic Operator", "operator@example.invalid", "unused"));
        assertEquals(ALREADY_PROVISIONED, error.diagnostic);
        verify(connection).rollback();
        verify(connection, never()).prepareStatement(contains("INSERT"));
        verify(connection, never()).commit();
    }

    @Test
    void safetyQueryFailureReportsSafetyCodeAndRollsBack() throws Exception {
        var connection = mock(Connection.class);
        when(connection.createStatement()).thenThrow(new java.sql.SQLException("synthetic-private-details"));
        var error = assertThrows(FirstSuperAdminProvisioning.Refused.class, () -> provision(connection));
        assertEquals(DATABASE_SAFETY, error.diagnostic);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void invalidIdentityInputHasSeparateCodeAndDoesNotStartTransaction() {
        var connection = mock(Connection.class);
        var error = assertThrows(FirstSuperAdminProvisioning.Refused.class, () ->
                FirstSuperAdminProvisioning.provision(connection, "Synthetic Operator", "malformed", "unused"));
        assertEquals(INVALID_IDENTITY_INPUT, error.diagnostic);
        verifyNoInteractions(connection);
    }

    @Test
    void duplicateEmailRefusesBeforeInsert() throws Exception {
        var connection = readyConnection();
        var duplicate = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement(contains("lower(email)"))).thenReturn(duplicate);
        when(duplicate.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);

        var error = assertThrows(FirstSuperAdminProvisioning.Refused.class, () -> provision(connection));
        assertEquals(DUPLICATE_EMAIL, error.diagnostic);
        verify(connection).rollback();
        verify(connection, never()).prepareStatement(contains("INSERT"));
        verify(connection, never()).commit();
    }

    @Test
    void writesSinglePlatformGrantAndAuditBeforeCommit() throws Exception {
        var connection = readyConnection();
        var audit = prepareWrites(connection);
        provision(connection);
        var order = inOrder(audit, connection);
        order.verify(audit).executeUpdate();
        order.verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void auditFailureRollsBackAllProvisioningWrites() throws Exception {
        var connection = readyConnection();
        var audit = prepareWrites(connection);
        when(audit.executeUpdate()).thenThrow(new java.sql.SQLException("synthetic failure"));
        var error = assertThrows(java.sql.SQLException.class, () -> provision(connection));
        assertEquals(TRANSACTION_FAILURE, FirstSuperAdminProvisioning.diagnosticFor(error, false));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    private void provision(Connection connection) throws Exception {
        FirstSuperAdminProvisioning.provision(connection, "Synthetic Operator", "operator@example.invalid", "unused");
    }

    private PreparedStatement prepareWrites(Connection connection) throws Exception {
        var user = mock(PreparedStatement.class);
        var role = mock(PreparedStatement.class);
        var audit = mock(PreparedStatement.class);
        var id = mock(ResultSet.class);
        when(connection.prepareStatement(contains("INSERT INTO public.users"))).thenReturn(user);
        when(user.executeQuery()).thenReturn(id);
        when(id.next()).thenReturn(true);
        when(id.getLong(1)).thenReturn(41L);
        when(connection.prepareStatement(argThat(sql -> sql != null && sql.contains("INSERT INTO public.user_roles")
                && sql.contains("WHERE name = 'SUPER_ADMIN'")))).thenReturn(role);
        when(role.executeUpdate()).thenReturn(1);
        when(connection.prepareStatement(contains("INSERT INTO public.privileged_audit_events"))).thenReturn(audit);
        when(audit.executeUpdate()).thenReturn(1);
        return audit;
    }

    private Connection readyConnection() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var target = mock(ResultSet.class);
        var history = mock(ResultSet.class);
        var versions = mock(Array.class);
        var empty = mock(PreparedStatement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(target, history);
        when(target.next()).thenReturn(true);
        when(target.getBoolean(1)).thenReturn(true);
        when(history.next()).thenReturn(true);
        when(history.getLong(2)).thenReturn(7L);
        when(history.getArray(1)).thenReturn(versions);
        when(versions.getArray()).thenReturn(new Integer[] {1, 2, 3, 4, 5, 6, 7});
        when(connection.prepareStatement(contains("SELECT 1 FROM public."))).thenReturn(empty);
        when(empty.executeQuery()).thenReturn(mock(ResultSet.class));
        return connection;
    }

    @Test
    void invalidHistoryRefusesBeforePreparingAnyWrite() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet target = mock(ResultSet.class);
        ResultSet history = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(target, history);
        when(target.next()).thenReturn(true);
        when(target.getBoolean(1)).thenReturn(true);
        when(history.next()).thenReturn(true);
        when(history.getLong(2)).thenReturn(5L);

        var error = assertThrows(FirstSuperAdminProvisioning.Refused.class, () -> FirstSuperAdminProvisioning.provision(
                connection, "Synthetic Operator", "operator@example.invalid", "unused"));
        assertEquals(DATABASE_SAFETY, error.diagnostic);
        verify(connection).rollback();
        verify(connection, never()).prepareStatement(anyString());
        verify(connection, never()).commit();
    }
}
