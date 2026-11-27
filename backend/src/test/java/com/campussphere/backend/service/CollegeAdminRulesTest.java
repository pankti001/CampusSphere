package com.campussphere.backend.service;

import java.util.List;
import java.util.Set;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.CollegeAccounts.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollegeAdminRulesTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final CollegeAccounts accounts = new CollegeAccounts(db);
    private final CollegeOverview overview = new CollegeOverview(db);

    private static Account account(String role) {
        return new Account(7L, true, false, Set.of(role), new CollegeScope(3L, true));
    }

    private static void assertError(ApiError expected, Runnable action) {
        assertEquals(expected, assertThrows(ApiError.Failure.class, action::run).error());
    }

    private static Person person(boolean active) {
        return new Person(40, "Krishna Test", "krishna@campussphere-demo.test", null, active, true, "CS", null);
    }

    @Test void onlyCollegeAdminsCanUseTheHomeAndAccounts() {
        for (var role : List.of("FACULTY", "STUDENT", "SUPER_ADMIN", "ADMIN")) {
            var actor = account(role);
            assertError(ApiError.ACCESS_DENIED, () -> overview.counts(actor));
            assertError(ApiError.ACCESS_DENIED, () -> overview.profile(actor));
            assertError(ApiError.ACCESS_DENIED, () -> accounts.list(actor, Kind.FACULTY, null, null));
            assertError(ApiError.ACCESS_DENIED, () -> accounts.setActive(actor, Kind.STUDENT, 40, new Status(false)));
        }
        var noCollege = new Account(7L, true, false, Set.of("COLLEGE_ADMIN"), null);
        assertError(ApiError.ACCESS_DENIED, () -> overview.counts(noCollege));
        verifyNoInteractions(db);
    }

    @Test void everyCountIsLimitedToTheAdminsCollege() {
        when(db.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(null);
        overview.counts(account("COLLEGE_ADMIN"));
        int placeholders = CollegeOverview.COUNTS.split(":c", -1).length - 1;
        assertEquals(19, placeholders, "one college filter per count");
        Object[] expected = new Object[placeholders];
        java.util.Arrays.fill(expected, 3L);
        verify(db).queryForObject(argThat((String sql) -> !sql.contains(":c") && sql.contains("FROM public.departments WHERE college_id = ?")),
                any(RowMapper.class), aryEq(expected));
        // Counts follow the same rules as the lists: active, verified, not ended, published.
        assertTrue(CollegeOverview.COUNTS.contains("u.is_active IS TRUE"));
        assertTrue(CollegeOverview.COUNTS.contains("ended_at IS NULL"));
        assertTrue(CollegeOverview.COUNTS.contains("requested_college_id = :c AND status = 'PENDING'"));
        assertTrue(CollegeOverview.COUNTS.contains("status = 'PUBLISHED'"));
    }

    @Test void detailsAreValidated() {
        var clean = new Details("  Krishna   Test ", " 9876543210 ").clean();
        assertEquals("Krishna Test", clean.fullName());
        assertEquals("9876543210", clean.phone());
        assertNull(new Details("Krishna", " ").clean().phone());
        assertError(ApiError.INVALID_REQUEST, () -> new Details(" ", null).clean());
        assertError(ApiError.INVALID_REQUEST, () -> new Details("x".repeat(101), null).clean());
        assertError(ApiError.INVALID_REQUEST, () -> new Details("Krishna", "12345").clean());
        assertError(ApiError.INVALID_REQUEST, () -> new Details("Krishna", "98765abcde").clean());
    }

    @Test void accountQueriesOnlyTouchPeopleOfThisKindInThisCollege() {
        String sql = CollegeAccounts.people(Kind.STUDENT);
        assertTrue(sql.contains("WHERE u.college_id = ?"));
        assertTrue(sql.contains("r.name = 'STUDENT'"));
        assertTrue(sql.contains("r.name <> 'STUDENT'"), "anyone holding another role (such as an admin) is excluded");
        assertTrue(CollegeAccounts.people(Kind.FACULTY).contains("faculty_profiles"));
        assertEquals(Kind.FACULTY, Kind.of("faculty"));
        assertError(ApiError.INVALID_REQUEST, () -> Kind.of("admins"));
    }

    @Test void deactivatingUpdatesOnlyThatPersonInThisCollege() {
        when(db.query(contains("AND u.id = ?"), any(RowMapper.class), eq(3L), eq(40L)))
                .thenReturn(List.of(person(true))).thenReturn(List.of(person(false)));
        var result = accounts.setActive(account("COLLEGE_ADMIN"), Kind.FACULTY, 40, new Status(false));
        assertFalse(result.active());
        verify(db).update(contains("SET is_active = ?"), eq(false), eq(3L), eq(40L));
    }

    @Test void unknownPeopleSelfAndMissingStatusAreRefused() {
        when(db.query(contains("AND u.id = ?"), any(RowMapper.class), eq(3L), eq(99L))).thenReturn(List.of());
        assertError(ApiError.STUDENT_NOT_FOUND, () -> accounts.setActive(account("COLLEGE_ADMIN"), Kind.STUDENT, 99, new Status(false)));
        assertError(ApiError.FACULTY_NOT_FOUND, () -> accounts.get(account("COLLEGE_ADMIN"), Kind.FACULTY, 99));
        assertError(ApiError.ACCESS_DENIED, () -> accounts.setActive(account("COLLEGE_ADMIN"), Kind.FACULTY, 7, new Status(false)));
        assertError(ApiError.INVALID_REQUEST, () -> accounts.setActive(account("COLLEGE_ADMIN"), Kind.FACULTY, 40, new Status(null)));
        verify(db, never()).update(contains("SET is_active"), any(Object[].class));
    }

    @Test void aPhoneNumberUsedByAnotherAccountIsReported() {
        when(db.query(contains("AND u.id = ?"), any(RowMapper.class), eq(3L), eq(40L))).thenReturn(List.of(person(true)));
        when(db.update(contains("SET full_name"), any(Object[].class))).thenThrow(new DuplicateKeyException("uq_users_phone"));
        assertError(ApiError.ACCOUNT_ALREADY_EXISTS,
                () -> accounts.update(account("COLLEGE_ADMIN"), Kind.FACULTY, 40, new Details("Krishna", "9876543210")));
    }
}
