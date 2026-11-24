package com.campussphere.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

class FlywayDatabaseGuardTest {
    @Test
    void testProfileRejectsDevelopmentDatabaseBeforeMigration() throws Exception {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("test");
        var flyway = flywayConnectedTo("campus_sphere_dev");
        var strategy = new FlywayDatabaseGuard().guardedFlywayMigration(environment, false);

        var error = assertThrows(IllegalStateException.class, () -> strategy.migrate(flyway));

        assertEquals("Flyway migration refused: expected database campus_sphere_test", error.getMessage());
        verify(flyway, never()).migrate();
    }

    @Test
    void developmentRefusesPendingV4WithoutApproval() throws Exception {
        var flyway = flywayConnectedTo("campus_sphere_dev");
        var info = mock(MigrationInfoService.class);
        var pending = mock(MigrationInfo.class);
        when(pending.getVersion()).thenReturn(MigrationVersion.fromVersion("4"));
        when(info.pending()).thenReturn(new MigrationInfo[]{pending});
        when(flyway.info()).thenReturn(info);
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("dev");
        var strategy = new FlywayDatabaseGuard().guardedFlywayMigration(environment, false);

        var error = assertThrows(IllegalStateException.class, () -> strategy.migrate(flyway));

        assertEquals("Flyway migration refused: V4 development rollout has not been approved", error.getMessage());
        verify(flyway, never()).migrate();
    }

    private Flyway flywayConnectedTo(String name) throws Exception {
        var flyway = mock(Flyway.class);
        var configuration = mock(Configuration.class);
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var result = mock(ResultSet.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT current_database()")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString(1)).thenReturn(name);
        return flyway;
    }
}
