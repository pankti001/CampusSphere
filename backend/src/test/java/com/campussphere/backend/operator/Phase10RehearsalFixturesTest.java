package com.campussphere.backend.operator;

import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Phase10RehearsalFixturesTest {
    final Connection connection = mock(Connection.class);
    final Phase10RehearsalFixtures.SeedSteps steps = mock(Phase10RehearsalFixtures.SeedSteps.class);

    @Test void acceptsOnlyLiteralRehearsalDatabase() {
        assertTrue(Phase10RehearsalFixtures.acceptsTarget("campus_sphere_phase9_rehearsal"));
        for (String invalid : new String[]{"campus_sphere_dev", "campus_sphere_test", "campus_sphere",
                "jdbc:postgresql://127.0.0.1:5432/campus_sphere_phase9_rehearsal"}) {
            assertFalse(Phase10RehearsalFixtures.acceptsTarget(invalid));
        }
        assertFalse(Phase10RehearsalFixtures.acceptsTarget("campus_sphere_phase9_rehearsal", "extra"));
    }

    @Test void verifiesBeforeAnyInsertAndCommitsOnce() throws Exception {
        Phase10RehearsalFixtures.transact(connection, steps, "fixture-hash");
        var order = inOrder(connection, steps);
        order.verify(connection).setAutoCommit(false);
        order.verify(steps).verify();
        order.verify(steps).insert("fixture-hash");
        order.verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test void failedSafetyGatePreventsInserts() throws Exception {
        doThrow(new IllegalStateException("nonempty")).when(steps).verify();
        assertThrows(IllegalStateException.class, () -> Phase10RehearsalFixtures.transact(connection, steps, "fixture-hash"));
        verify(steps, never()).insert(any());
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test void partialInsertFailureRollsBackWithoutRetry() throws Exception {
        var error = new SQLException("fixture error", "23505");
        doThrow(error).when(steps).insert("fixture-hash");
        assertSame(error, assertThrows(SQLException.class,
                () -> Phase10RehearsalFixtures.transact(connection, steps, "fixture-hash")));
        verify(steps).insert("fixture-hash");
        verify(connection).rollback();
        verify(connection, never()).commit();
    }
    @Test void recordsCommitFailureAsUncertainEvenWhenRollbackReturns() throws Exception {
        var diagnostic = new Phase10RehearsalFixtures.Diagnostic();
        var failure = new SQLException("secret-bearing database detail", "08006", 701,
                new IllegalArgumentException("secret-bearing cause"));
        failure.setNextException(new SQLException("secret-bearing linked error", "55P03", 702));
        doThrow(failure).when(connection).commit();

        assertSame(failure, assertThrows(SQLException.class,
                () -> Phase10RehearsalFixtures.transact(connection, steps, "fixture-hash", diagnostic)));
        String report = diagnostic.render(failure);
        assertTrue(report.contains("stage=COMMIT"));
        assertTrue(report.contains("transactionOutcome=UNCERTAIN"));
        assertTrue(report.contains("rollbackReturned=true"));
        assertTrue(report.contains("SQLState=08006 vendorCode=701"));
        assertTrue(report.contains("cause=java.lang.IllegalArgumentException"));
        assertTrue(report.contains("nextSQLException=java.sql.SQLException SQLState=55P03 vendorCode=702"));
        assertFalse(report.contains("secret-bearing"));
    }

    @Test void recordsRollbackAfterInsertFailure() throws Exception {
        var diagnostic = new Phase10RehearsalFixtures.Diagnostic();
        doThrow(new SQLException("secret-bearing details", "23505")).when(steps).insert("fixture-hash");
        var failure = assertThrows(SQLException.class,
                () -> Phase10RehearsalFixtures.transact(connection, steps, "fixture-hash", diagnostic));
        String report = diagnostic.render(failure);
        assertTrue(report.contains("stage=TRANSACTION_INSERTS"));
        assertTrue(report.contains("transactionOutcome=ROLLED_BACK"));
        assertTrue(report.contains("PostgreSQL rejected a duplicate key"));
        assertFalse(report.contains("secret-bearing"));
    }

    @Test void recordsSuccessfulCommit() throws Exception {
        var diagnostic = new Phase10RehearsalFixtures.Diagnostic();
        Phase10RehearsalFixtures.transact(connection, steps, "fixture-hash", diagnostic);
        assertTrue(diagnostic.render(null).contains("transactionOutcome=COMMITTED"));
        assertTrue(diagnostic.render(null).contains("rollbackAttempted=false"));
    }

}
