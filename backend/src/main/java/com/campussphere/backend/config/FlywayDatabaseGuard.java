package com.campussphere.backend.config;

import java.sql.SQLException;

import org.flywaydb.core.api.MigrationVersion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
public class FlywayDatabaseGuard {
    @Bean
    FlywayMigrationStrategy guardedFlywayMigration(Environment environment,
            @Value("${app.flyway.allow-v4-development:false}") boolean allowV4Development) {
        boolean test = environment.acceptsProfiles(Profiles.of("test"));
        String expectedDatabase = test ? "campus_sphere_test" : "campus_sphere_dev";

        return flyway -> {
            try (var connection = flyway.getConfiguration().getDataSource().getConnection();
                 var statement = connection.prepareStatement("SELECT current_database()");
                 var result = statement.executeQuery()) {
                if (!result.next() || !expectedDatabase.equals(result.getString(1))) {
                    throw new IllegalStateException(
                            "Flyway migration refused: expected database " + expectedDatabase);
                }
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "Could not verify the Flyway database before migration", e);
            }

            if (!test && !allowV4Development) {
                var version = MigrationVersion.fromVersion("4");
                for (var migration : flyway.info().pending()) {
                    if (migration.getVersion() != null && migration.getVersion().compareTo(version) >= 0) {
                        throw new IllegalStateException(
                                "Flyway migration refused: V4 development rollout has not been approved");
                    }
                }
            }
            flyway.migrate();
        };
    }
}
