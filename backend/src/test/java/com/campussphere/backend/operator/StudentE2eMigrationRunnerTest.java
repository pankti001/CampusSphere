package com.campussphere.backend.operator;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StudentE2eMigrationRunnerTest {
    @Test void verifiesReviewedSqlWithoutConnecting() throws Exception {
        assertEquals(13, StudentE2eMigrationRunner.verifySource().length);
    }

    @Test void acceptsOnlyTheExpectedLocalIdentity() {
        assertDoesNotThrow(() -> StudentE2eMigrationRunner.verifyIdentity("campus_sphere_student_e2e",
                "127.0.0.1", 5432, 190000, "postgres", "postgres", "public", "postgres"));
        for (String name : new String[]{"campus_sphere_dev", "campus_sphere_test", "campus_sphere_phase9_rehearsal", "campus_sphere"}) {
            assertThrows(IllegalStateException.class, () -> StudentE2eMigrationRunner.verifyIdentity(
                    name, "127.0.0.1", 5432, 190000, "postgres", "postgres", "public", "postgres"));
        }
    }

    @Test void rejectsWrongHostPortVersionRoleSchemaAndOwner() {
        assertThrows(IllegalStateException.class, () -> identity("192.0.2.1", 5432, 190000, "postgres", "public", "postgres"));
        assertThrows(IllegalStateException.class, () -> identity("127.0.0.1", 5433, 190000, "postgres", "public", "postgres"));
        assertThrows(IllegalStateException.class, () -> identity("127.0.0.1", 5432, 180000, "postgres", "public", "postgres"));
        assertThrows(IllegalStateException.class, () -> identity("127.0.0.1", 5432, 190000, "other", "public", "postgres"));
        assertThrows(IllegalStateException.class, () -> identity("127.0.0.1", 5432, 190000, "postgres", "other", "postgres"));
        assertThrows(IllegalStateException.class, () -> identity("127.0.0.1", 5432, 190000, "postgres", "public", "other"));
    }

    private void identity(String host, int port, int version, String role, String schema, String owner) {
        StudentE2eMigrationRunner.verifyIdentity("campus_sphere_student_e2e", host, port, version, "postgres", role, schema, owner);
    }
}
