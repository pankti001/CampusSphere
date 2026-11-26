package com.campussphere.backend.service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal notifications telling faculty members about their own lessons.
 *
 * <p>They are created only when a timetable is published or unpublished, inside that same transaction, so a failed
 * publication creates none and drafts never create any. Each notice has an event key (publication + lesson); the
 * database's unique key makes a repeated event insert nothing twice. Only the faculty member named on the lesson is
 * notified, and only within the timetable's college. Faculty can list their own notices and mark them as read.
 */
@Service
@Transactional(readOnly = true)
public class FacultyNotifications {
    static final int PAGE_SIZE = 50;
    private static final String[] DAYS = {"", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);

    /** A published lesson. */
    record Lesson(long entryId, long facultyId, long subjectId, String subjectCode, String subject, int day,
                  LocalTime start, LocalTime end, String classroomCode) {
        boolean sameSlot(Lesson other) {
            return day == other.day && start.equals(other.start) && end.equals(other.end);
        }

        boolean same(Lesson other) {
            return facultyId == other.facultyId && subjectId == other.subjectId && sameSlot(other)
                    && Objects.equals(classroomCode, other.classroomCode);
        }
    }

    /** One notice to send: kind ASSIGNED, CHANGED, REASSIGNED or CANCELLED; previous is set for CHANGED. */
    record Change(String kind, long facultyId, Lesson lesson, Lesson previous) {}

    record Context(String program, String semester, String division, String academicYear) {}

    public record Notice(long id, String kind, String title, String body, String subjectCode, String subject,
                         String program, String semester, String division, String academicYear, int day,
                         LocalTime start, LocalTime end, String classroomCode, LocalDate startsOn,
                         OffsetDateTime createdAt, OffsetDateTime readAt) {}

    private static final RowMapper<Notice> NOTICE = (r, i) -> new Notice(r.getLong("id"), r.getString("kind"),
            r.getString("title"), r.getString("body"), r.getString("subject_code"), r.getString("subject"),
            r.getString("program"), r.getString("semester"), r.getString("division"), r.getString("academic_year"),
            r.getInt("day_of_week"), r.getObject("start_time", LocalTime.class), r.getObject("end_time", LocalTime.class),
            r.getString("classroom_code"), r.getObject("starts_on", LocalDate.class),
            r.getObject("created_at", OffsetDateTime.class), r.getObject("read_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public FacultyNotifications(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- What changed (pure, unit-tested) ----

    /**
     * Compares the previously published lessons with the newly published ones. Unchanged lessons produce nothing.
     * The same subject and faculty member at a different time or room is one CHANGED notice. The same subject and
     * time with a different faculty member tells the old one REASSIGNED and the new one ASSIGNED. Any other new
     * lesson is ASSIGNED and any other removed lesson is CANCELLED.
     */
    static List<Change> diff(List<Lesson> before, List<Lesson> after) {
        var oldLeft = new ArrayList<>(before);
        var newLeft = new ArrayList<Lesson>();
        for (var lesson : after) {
            var same = oldLeft.stream().filter(o -> o.same(lesson)).findFirst();
            if (same.isPresent()) oldLeft.remove(same.get());
            else newLeft.add(lesson);
        }
        var changes = new ArrayList<Change>();
        for (var it = newLeft.iterator(); it.hasNext(); ) {
            var lesson = it.next();
            var moved = oldLeft.stream().filter(o -> o.facultyId() == lesson.facultyId() && o.subjectId() == lesson.subjectId())
                    .min(Comparator.comparingInt((Lesson o) -> o.day() == lesson.day() ? 0 : 1)
                            .thenComparingInt(o -> Math.abs(o.start().toSecondOfDay() - lesson.start().toSecondOfDay())));
            if (moved.isPresent()) {
                oldLeft.remove(moved.get());
                it.remove();
                changes.add(new Change("CHANGED", lesson.facultyId(), lesson, moved.get()));
            }
        }
        for (var lesson : newLeft) {
            var replaced = oldLeft.stream().filter(o -> o.subjectId() == lesson.subjectId() && o.sameSlot(lesson)
                    && o.facultyId() != lesson.facultyId()).findFirst();
            if (replaced.isPresent()) {
                oldLeft.remove(replaced.get());
                changes.add(new Change("REASSIGNED", replaced.get().facultyId(), replaced.get(), null));
            }
            changes.add(new Change("ASSIGNED", lesson.facultyId(), lesson, null));
        }
        for (var lesson : oldLeft) changes.add(new Change("CANCELLED", lesson.facultyId(), lesson, null));
        return changes;
    }

    static String slot(Lesson l) {
        return DAYS[l.day()] + " " + l.start() + "–" + l.end();
    }

    private static String room(Lesson l) {
        return l.classroomCode() == null ? "no room" : "Room " + l.classroomCode();
    }

    /** Title and body of a notice. */
    static String[] text(Change c, Context ctx, LocalDate startsOn) {
        var l = c.lesson();
        String subject = l.subject() + " (" + l.subjectCode() + ")";
        String cls = ctx.program() + " · " + ctx.semester() + " · Division " + ctx.division() + " · " + ctx.academicYear();
        String from = " · from " + startsOn.format(DATE);
        String[] text = switch (c.kind()) {
            case "ASSIGNED" -> new String[]{"New class: " + subject, slot(l) + " · " + cls + " · " + room(l) + from};
            case "CHANGED" -> {
                var p = c.previous();
                boolean time = !p.sameSlot(l);
                boolean place = !Objects.equals(p.classroomCode(), l.classroomCode());
                String what = time && place ? "Class time and room changed: " : time ? "Class time changed: "
                        : place ? "Classroom changed: " : "Class changed: ";
                yield new String[]{what + subject, "Now " + slot(l) + " in " + room(l) + " (was " + slot(p) + " in "
                        + room(p) + ") · " + cls + from};
            }
            case "REASSIGNED" -> new String[]{"Class reassigned: " + subject, "Your " + slot(l) + " class for " + cls
                    + " in " + room(l) + " is now taught by another faculty member."};
            default -> new String[]{"Class removed: " + subject, slot(l) + " · " + cls + " · " + room(l)
                    + " is no longer in your timetable."};
        };
        return new String[]{cut(text[0], 160), cut(text[1], 600)};
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    static LocalDate nextOccurrence(LocalDate today, int day) {
        return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(day)));
    }

    // ---- Created by timetable publication (always inside the caller's transaction) ----

    /** After a version is published: notify faculty about what is new or different from the previous version. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int onPublish(long college, long timetableId, Long previousTimetableId) {
        var before = previousTimetableId == null ? List.<Lesson>of() : lessons(college, previousTimetableId);
        return insert(college, timetableId, "pub:" + timetableId, diff(before, lessons(college, timetableId)));
    }

    /** After a version is unpublished: every lesson in it is cancelled for its faculty member. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int onUnpublish(long college, long timetableId) {
        var changes = lessons(college, timetableId).stream()
                .map(l -> new Change("CANCELLED", l.facultyId(), l, null)).toList();
        return insert(college, timetableId, "unpub:" + timetableId, changes);
    }

    private List<Lesson> lessons(long college, long timetableId) {
        return jdbc.query("""
                SELECT e.id, e.faculty_id, e.subject_id, sub.code subject_code, sub.name subject, e.day_of_week,
                       e.start_time, e.end_time, c.code classroom_code
                FROM public.timetable_entries e
                JOIN public.academic_subjects sub ON sub.college_id=e.college_id AND sub.id=e.subject_id
                LEFT JOIN public.classrooms c ON c.college_id=e.college_id AND c.id=e.classroom_id
                WHERE e.college_id=? AND e.timetable_id=? AND e.kind='LESSON'
                ORDER BY e.day_of_week, e.start_time, e.id
                """, (r, i) -> new Lesson(r.getLong("id"), r.getLong("faculty_id"), r.getLong("subject_id"),
                r.getString("subject_code"), r.getString("subject"), r.getInt("day_of_week"),
                r.getObject("start_time", LocalTime.class), r.getObject("end_time", LocalTime.class),
                r.getString("classroom_code")), college, timetableId);
    }

    private int insert(long college, long timetableId, String eventPrefix, List<Change> changes) {
        if (changes.isEmpty()) return 0;
        var ctx = jdbc.queryForObject("""
                SELECT p.name program, sem.name semester, v.name division, y.code academic_year
                FROM public.timetables t
                JOIN public.academic_programs p ON p.college_id=t.college_id AND p.id=t.program_id
                JOIN public.academic_semesters sem ON sem.college_id=t.college_id AND sem.id=t.semester_id
                JOIN public.academic_divisions v ON v.college_id=t.college_id AND v.id=t.division_id
                JOIN public.academic_years y ON y.college_id=t.college_id AND y.id=t.academic_year_id
                WHERE t.college_id=? AND t.id=?
                """, (r, i) -> new Context(r.getString("program"), r.getString("semester"), r.getString("division"),
                r.getString("academic_year")), college, timetableId);
        LocalDate today = LocalDate.now(Timetables.COLLEGE_ZONE);
        int created = 0;
        for (var c : changes) {
            var l = c.lesson();
            LocalDate startsOn = nextOccurrence(today, l.day());
            String[] text = text(c, ctx, startsOn);
            created += jdbc.update("""
                    INSERT INTO public.faculty_notifications (college_id, faculty_id, timetable_id, event_key, kind,
                        title, body, subject_code, subject, program, semester, division, academic_year, day_of_week,
                        start_time, end_time, classroom_code, starts_on)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT ON CONSTRAINT uq_faculty_notification_event DO NOTHING
                    """, college, c.facultyId(), timetableId, eventPrefix + ":" + c.kind() + ":" + l.entryId(), c.kind(),
                    text[0], text[1], l.subjectCode(), l.subject(), ctx.program(), ctx.semester(), ctx.division(),
                    ctx.academicYear(), l.day(), l.start(), l.end(), l.classroomCode(), startsOn);
        }
        return created;
    }

    // ---- Faculty inbox ----

    public List<Notice> list(Account account, boolean unreadOnly, int page) {
        long college = account.requireCollege(CollegeRole.FACULTY, null);
        if (page < 0 || page > 10_000) throw ApiError.INVALID_REQUEST.exception();
        return jdbc.query("""
                SELECT * FROM public.faculty_notifications
                WHERE college_id=? AND faculty_id=?""" + (unreadOnly ? " AND read_at IS NULL" : "") + """

                ORDER BY id DESC LIMIT ? OFFSET ?
                """, NOTICE, college, account.id(), PAGE_SIZE, page * PAGE_SIZE);
    }

    public long unread(Account account) {
        long college = account.requireCollege(CollegeRole.FACULTY, null);
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM public.faculty_notifications WHERE college_id=? AND faculty_id=? AND read_at IS NULL
                """, Long.class, college, account.id());
        return count == null ? 0 : count;
    }

    /** Marks one of the signed-in faculty member's notices as read; reading it again changes nothing. */
    @Transactional
    public void markRead(Account account, long id) {
        long college = account.requireCollege(CollegeRole.FACULTY, null);
        int changed = jdbc.update("""
                UPDATE public.faculty_notifications SET read_at=GREATEST(statement_timestamp(), created_at)
                WHERE college_id=? AND faculty_id=? AND id=? AND read_at IS NULL
                """, college, account.id(), id);
        if (changed == 0 && jdbc.queryForList("""
                SELECT id FROM public.faculty_notifications WHERE college_id=? AND faculty_id=? AND id=?
                """, Long.class, college, account.id(), id).isEmpty()) throw ApiError.NOTIFICATION_NOT_FOUND.exception();
    }

    @Transactional
    public int markAllRead(Account account) {
        long college = account.requireCollege(CollegeRole.FACULTY, null);
        return jdbc.update("""
                UPDATE public.faculty_notifications SET read_at=GREATEST(statement_timestamp(), created_at)
                WHERE college_id=? AND faculty_id=? AND read_at IS NULL
                """, college, account.id());
    }
}
