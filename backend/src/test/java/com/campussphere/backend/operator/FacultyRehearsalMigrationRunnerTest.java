package com.campussphere.backend.operator;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static com.campussphere.backend.operator.FacultyRehearsalMigrationRunner.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

class FacultyRehearsalMigrationRunnerTest {
    @Test void offlineSourceCheckPinsAllFourteenFilesInOrder() throws Exception {
        var sources = FacultyRehearsalMigrationRunner.reviewedSources();
        assertEquals(IntStream.rangeClosed(1, 14).mapToObj(i -> "V" + i).toList(),
                sources.stream().map(source -> source.name().split("__", 2)[0]).toList());
        assertTrue(sources.stream().allMatch(source -> source.bytes().length > 0));
        assertEquals(VERIFY, FacultyRehearsalMigrationRunner.mode(new String[]{"verify-source"}));
    }

    @Test void refusesAnyTargetExceptTheExactFacultyRehearsalDatabase() {
        assertEquals(MIGRATE, FacultyRehearsalMigrationRunner.mode(
                new String[]{"migrate", "campus_sphere_faculty_e2e"}));
        for (String target : List.of("campus_sphere_dev", "campus_sphere_test", "campus_sphere",
                "campus_sphere_student_e2e", "campus_sphere_phase9_rehearsal",
                "jdbc:postgresql://127.0.0.1:5432/campus_sphere_faculty_e2e",
                "jdbc:postgresql://remote.invalid:5432/campus_sphere_faculty_e2e",
                "jdbc:postgresql://127.0.0.1:5433/campus_sphere_faculty_e2e")) {
            assertEquals(REFUSE, FacultyRehearsalMigrationRunner.mode(new String[]{"migrate", target}));
        }
        assertEquals(REFUSE, FacultyRehearsalMigrationRunner.mode(new String[]{"migrate"}));
        assertEquals(REFUSE, FacultyRehearsalMigrationRunner.mode(
                new String[]{"migrate", FacultyRehearsalMigrationRunner.DATABASE, "extra"}));
    }
}
