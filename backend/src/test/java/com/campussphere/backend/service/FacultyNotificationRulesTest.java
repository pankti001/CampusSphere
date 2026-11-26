package com.campussphere.backend.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.FacultyNotifications.*;
import com.campussphere.backend.service.Timetables.Publish;
import com.campussphere.backend.service.Timetables.Summary;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FacultyNotificationRulesTest {
    private static final long KRISHNA = 61, MEERA = 62;
    private static final LocalTime NINE = LocalTime.of(9, 0), TEN = LocalTime.of(10, 0), ELEVEN = LocalTime.of(11, 0);

    private static Lesson java(long entry, long faculty, int day, LocalTime start, LocalTime end, String room) {
        return new Lesson(entry, faculty, 71, "JAVA", "Java Programming", day, start, end, room);
    }

    private static Lesson python(long entry, long faculty, int day, LocalTime start, LocalTime end, String room) {
        return new Lesson(entry, faculty, 72, "PY", "Python Programming", day, start, end, room);
    }

    private static List<String> kinds(List<Change> changes) {
        return changes.stream().map(c -> c.kind() + ":" + c.facultyId()).toList();
    }

    @Test void firstPublicationAssignsEveryLessonToItsOwnFaculty() {
        var changes = FacultyNotifications.diff(List.of(), List.of(java(1, KRISHNA, 1, NINE, TEN, "101"),
                python(2, KRISHNA, 1, TEN, ELEVEN, "203"), java(3, MEERA, 2, NINE, TEN, "101")));
        assertEquals(List.of("ASSIGNED:61", "ASSIGNED:61", "ASSIGNED:62"), kinds(changes));
    }

    @Test void unchangedLessonsSendNothing() {
        var lessons = List.of(java(1, KRISHNA, 1, NINE, TEN, "101"));
        assertTrue(FacultyNotifications.diff(lessons, List.of(java(9, KRISHNA, 1, NINE, TEN, "101"))).isEmpty());
    }

    @Test void timeAndRoomChangesAreOneChangeNotice() {
        var changes = FacultyNotifications.diff(List.of(java(1, KRISHNA, 1, NINE, TEN, "101")),
                List.of(java(9, KRISHNA, 2, TEN, ELEVEN, "203")));
        assertEquals(List.of("CHANGED:61"), kinds(changes));
        assertEquals("101", changes.getFirst().previous().classroomCode());
        var ctx = new Context("BSc", "Semester 1", "A", "2026-2027");
        var text = FacultyNotifications.text(changes.getFirst(), ctx, LocalDate.of(2026, 12, 1));
        assertEquals("Class time and room changed: Java Programming (JAVA)", text[0]);
        assertTrue(text[1].startsWith("Now Tuesday 10:00–11:00 in Room 203 (was Monday 09:00–10:00 in Room 101)"));

        var room = FacultyNotifications.diff(List.of(java(1, KRISHNA, 1, NINE, TEN, "101")), List.of(java(9, KRISHNA, 1, NINE, TEN, "203")));
        assertEquals("Classroom changed: Java Programming (JAVA)", FacultyNotifications.text(room.getFirst(), ctx, LocalDate.of(2026, 11, 30))[0]);
    }

    @Test void reassignmentTellsBothFacultyMembers() {
        var changes = FacultyNotifications.diff(List.of(java(1, KRISHNA, 1, NINE, TEN, "101")),
                List.of(java(9, MEERA, 1, NINE, TEN, "101")));
        assertEquals(List.of("REASSIGNED:61", "ASSIGNED:62"), kinds(changes));
    }

    @Test void removedLessonsAreCancelledAndNewOnesAssigned() {
        var changes = FacultyNotifications.diff(List.of(java(1, KRISHNA, 1, NINE, TEN, "101")),
                List.of(python(9, MEERA, 3, NINE, TEN, "203")));
        assertEquals(List.of("ASSIGNED:62", "CANCELLED:61"), kinds(changes));
    }

    @Test void krishnaGetsAPersonalNoticeWithEveryDetail() {
        var change = new Change("ASSIGNED", KRISHNA, java(1, KRISHNA, 1, NINE, TEN, "101"), null);
        var text = FacultyNotifications.text(change, new Context("BSc Computer Science", "Semester 3", "A", "2026-2027"),
                LocalDate.of(2026, 11, 30));
        assertEquals("New class: Java Programming (JAVA)", text[0]);
        assertEquals("Monday 09:00–10:00 · BSc Computer Science · Semester 3 · Division A · 2026-2027 · Room 101"
                + " · from Mon 30 Nov 2026", text[1]);
        assertEquals(LocalDate.of(2026, 11, 30), FacultyNotifications.nextOccurrence(LocalDate.of(2026, 11, 26), 1));
        assertEquals(LocalDate.of(2026, 11, 26), FacultyNotifications.nextOccurrence(LocalDate.of(2026, 11, 26), 4));
    }

    // ---- Writing notices ----

    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final FacultyNotifications service = new FacultyNotifications(db);

    @Test void publicationInsertsOneRetrySafeNoticePerChangeForTheAssignedFacultyOnly() {
        when(db.query(contains("FROM public.timetable_entries e"), any(RowMapper.class), eq(3L), eq(11L)))
                .thenReturn(List.of(java(101, KRISHNA, 1, NINE, TEN, "101"), python(102, KRISHNA, 1, TEN, ELEVEN, "203")));
        when(db.queryForObject(contains("FROM public.timetables t"), any(RowMapper.class), eq(3L), eq(11L)))
                .thenReturn(new Context("BSc", "Semester 1", "A", "2026-2027"));
        when(db.update(contains("INSERT INTO public.faculty_notifications"), any(Object[].class))).thenReturn(1);

        assertEquals(2, service.onPublish(3, 11, null));
        verify(db).update(argThat((String sql) -> sql.contains("ON CONFLICT ON CONSTRAINT uq_faculty_notification_event DO NOTHING")),
                eq(3L), eq(KRISHNA), eq(11L), eq("pub:11:ASSIGNED:101"), eq("ASSIGNED"), eq("New class: Java Programming (JAVA)"),
                anyString(), eq("JAVA"), eq("Java Programming"), eq("BSc"), eq("Semester 1"), eq("A"), eq("2026-2027"),
                eq(1), eq(NINE), eq(TEN), eq("101"), any(LocalDate.class));
        verify(db).update(anyString(), eq(3L), eq(KRISHNA), eq(11L), eq("pub:11:ASSIGNED:102"), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        // A retried event inserts nothing new: the database reports 0 rows for each duplicate key.
        when(db.update(contains("INSERT INTO public.faculty_notifications"), any(Object[].class))).thenReturn(0);
        assertEquals(0, service.onPublish(3, 11, null));
    }

    @Test void unpublishingCancelsEveryLessonOfThatVersion() {
        when(db.query(contains("FROM public.timetable_entries e"), any(RowMapper.class), eq(3L), eq(11L)))
                .thenReturn(List.of(java(101, KRISHNA, 1, NINE, TEN, "101")));
        when(db.queryForObject(contains("FROM public.timetables t"), any(RowMapper.class), eq(3L), eq(11L)))
                .thenReturn(new Context("BSc", "Semester 1", "A", "2026-2027"));
        when(db.update(anyString(), any(Object[].class))).thenReturn(1);
        assertEquals(1, service.onUnpublish(3, 11));
        verify(db).update(anyString(), eq(3L), eq(KRISHNA), eq(11L), eq("unpub:11:CANCELLED:101"), eq("CANCELLED"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ---- Faculty inbox ----

    private static Account account(String role) {
        return new Account(KRISHNA, true, false, Set.of(role), new CollegeScope(3L, true));
    }

    @Test void inboxIsOnlyForFacultyAndOnlyTheirOwnNotices() {
        for (var role : List.of("STUDENT", "COLLEGE_ADMIN", "SUPER_ADMIN")) {
            assertThrows(ApiError.Failure.class, () -> service.list(account(role), false, 0));
            assertThrows(ApiError.Failure.class, () -> service.markRead(account(role), 1));
        }
        verifyNoInteractions(db);
        service.list(account("FACULTY"), true, 0);
        verify(db).query(argThat((String sql) -> sql.contains("WHERE college_id=? AND faculty_id=?") && sql.contains("read_at IS NULL")),
                any(RowMapper.class), eq(3L), eq(KRISHNA), eq(50), eq(0));
    }

    @Test void markingSomeoneElsesNoticeIsNotFoundButRereadingIsFine() {
        when(db.update(contains("SET read_at"), eq(3L), eq(KRISHNA), eq(5L))).thenReturn(0);
        when(db.queryForList(contains("SELECT id FROM public.faculty_notifications"), eq(Long.class), eq(3L), eq(KRISHNA), eq(5L)))
                .thenReturn(List.of());
        var failure = assertThrows(ApiError.Failure.class, () -> service.markRead(account("FACULTY"), 5));
        assertEquals(ApiError.NOTIFICATION_NOT_FOUND, failure.error());

        when(db.queryForList(contains("SELECT id FROM public.faculty_notifications"), eq(Long.class), eq(3L), eq(KRISHNA), eq(6L)))
                .thenReturn(List.of(6L));
        assertDoesNotThrow(() -> service.markRead(account("FACULTY"), 6)); // already read: nothing changes
    }

    // ---- Notifications follow a successful publication only ----

    @Test void publishingNotifiesAfterTheStatusChangeAndDraftEditsNeverNotify() {
        var notifications = mock(FacultyNotifications.class);
        var timetables = new Timetables(db, notifications);
        var admin = new Account(7L, true, false, Set.of("COLLEGE_ADMIN"), new CollegeScope(3L, true));
        when(db.queryForList(contains("FROM public.colleges"), eq(Long.class), eq(3L))).thenReturn(List.of(3L));
        when(db.query(contains("SELECT id, version, revision"), any(RowMapper.class), eq(3L), eq(11L)))
                .thenReturn(List.<Object[]>of(new Object[]{new Timetables.Locked(11, 2, 4, 21, 31), "DRAFT"}));
        var now = OffsetDateTime.parse("2026-11-26T10:00:00+05:30");
        when(db.query(contains("(SELECT count(*) FROM public.timetable_entries e"), any(RowMapper.class), eq(3L), eq(11L)))
                .thenReturn(List.of(new Summary(11, 2, "DRAFT", 4, 21, 31, "2026-2027", "Science", "BSc", "Semester 1", "A",
                        2, 0, now, now, null, null)));
        when(db.query(contains("status='PUBLISHED' FOR UPDATE"), any(RowMapper.class), eq(3L), eq(21L), eq(31L)))
                .thenReturn(List.of(new long[]{9L, 1L}));
        when(notifications.onPublish(3, 11, 9L)).thenReturn(2);

        var published = timetables.publish(admin, 11, new Publish(4L));
        assertEquals(2, published.notified());
        InOrder order = inOrder(db, notifications);
        order.verify(db).update(contains("SET status='ARCHIVED'"), eq(7L), eq(11L), eq(3L), eq(9L));
        order.verify(db).update(contains("SET status='PUBLISHED'"), eq(7L), eq(3L), eq(11L));
        order.verify(notifications).onPublish(3, 11, 9L);

        // A rejected publication (stale revision) never reaches the notifications.
        reset(notifications);
        assertThrows(ApiError.Failure.class, () -> timetables.publish(admin, 11, new Publish(3L)));
        verifyNoInteractions(notifications);
    }
}
