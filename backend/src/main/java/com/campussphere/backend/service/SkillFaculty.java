package com.campussphere.backend.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Students asking a faculty member of their own college to teach a topic. Classmates join with "Me too"; the chosen
 * faculty member is notified, and on accepting sets a time that every student who joined is told about (and reminded
 * of). Students only see requests of their class or college; a faculty member only sees requests sent to them. Faculty
 * never see student chats through this.
 */
@Service
@Transactional
public class SkillFaculty {
    public record FacultyChoice(long id, String name, boolean teachesMyClass, String subjects) {}
    public record AskInput(Long facultyId, String topic, String details, String audience) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    public record AcceptInput(OffsetDateTime startsAt, OffsetDateTime endsAt, String mode, String place) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    public record ReasonInput(String reason) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    public record FacultyRequest(long id, long facultyId, String faculty, long createdById, String createdBy, String topic,
                                 String details, String audience, String status, OffsetDateTime startsAt, OffsetDateTime endsAt,
                                 String mode, String place, String reason, OffsetDateTime createdAt, long students,
                                 boolean joined, boolean mine, boolean canComplete) {}
    public record Student(long id, String name, String classLabel) {}
    public record FacultyRequestDetail(FacultyRequest request, List<Student> students) {}
    public record FacultyNotice(long id, String kind, String title, String body, long requestId, OffsetDateTime createdAt,
                                OffsetDateTime readAt) {}

    private final JdbcTemplate db;
    private final SkillExchange skills;
    private final SkillNotices notices;

    public SkillFaculty(JdbcTemplate db, SkillExchange skills, SkillNotices notices) {
        this.db = db;
        this.skills = skills;
        this.notices = notices;
    }

    /** The signed-in faculty member's college, after checking they have a faculty profile there. */
    long facultyCollege(Account actor) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        if (!Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.faculty_profiles WHERE college_id = ? AND faculty_id = ?)
                """, Boolean.class, college, actor.id()))) throw ApiError.ACCESS_DENIED.exception();
        return college;
    }

    // Arguments: me (joined), me (mine), college.
    private static final String SELECT = """
            SELECT fr.id, fr.faculty_id, fu.full_name faculty, fr.created_by, cu.full_name created_by_name, fr.topic, fr.details,
                   fr.audience, fr.status, fr.starts_at, fr.ends_at, fr.mode, fr.place, fr.reason, fr.created_at,
                   (SELECT count(*) FROM public.skill_faculty_request_members m WHERE m.college_id = fr.college_id
                      AND m.request_id = fr.id) students,
                   EXISTS (SELECT 1 FROM public.skill_faculty_request_members m WHERE m.college_id = fr.college_id
                      AND m.request_id = fr.id AND m.student_id = ?) joined,
                   fr.created_by = ? mine
            FROM public.skill_faculty_requests fr
            JOIN public.users fu ON fu.id = fr.faculty_id
            JOIN public.users cu ON cu.id = fr.created_by
            WHERE fr.college_id = ?""";

    private FacultyRequest row(ResultSet r) throws SQLException {
        OffsetDateTime ends = r.getObject("ends_at", OffsetDateTime.class);
        String status = r.getString("status");
        return new FacultyRequest(r.getLong("id"), r.getLong("faculty_id"), r.getString("faculty"), r.getLong("created_by"),
                r.getString("created_by_name"), r.getString("topic"), r.getString("details"), r.getString("audience"), status,
                r.getObject("starts_at", OffsetDateTime.class), ends, r.getString("mode"), r.getString("place"),
                r.getString("reason"), r.getObject("created_at", OffsetDateTime.class), r.getLong("students"),
                r.getBoolean("joined"), r.getBoolean("mine"),
                "ACCEPTED".equals(status) && ends != null && !skills.now().isBefore(ends));
    }

    private List<Student> students(long college, long request) {
        return db.query("""
                SELECT m.student_id, u.full_name,
                       (SELECT p.name || ' · ' || d.name FROM public.student_enrollments e
                        JOIN public.academic_programs p ON p.college_id = e.college_id AND p.id = e.program_id
                        JOIN public.academic_divisions d ON d.college_id = e.college_id AND d.id = e.division_id
                        WHERE e.college_id = m.college_id AND e.student_id = m.student_id AND e.ended_at IS NULL LIMIT 1)
                FROM public.skill_faculty_request_members m JOIN public.users u ON u.id = m.student_id
                WHERE m.college_id = ? AND m.request_id = ? ORDER BY m.joined_at
                """, (r, i) -> new Student(r.getLong(1), r.getString(2), r.getString(3)), college, request);
    }

    // ---------------------------------------------------------------- Students

    /** Faculty of the student's own college; those who teach the student's class come first. */
    public List<FacultyChoice> faculty(Account actor) {
        long college = skills.college(actor);
        Long division = skills.division(college, actor.id());
        return db.query("""
                SELECT u.id, u.full_name,
                       EXISTS (SELECT 1 FROM public.faculty_subject_assignments a WHERE a.college_id = fp.college_id
                          AND a.faculty_id = fp.faculty_id AND a.division_id = ? AND a.ended_at IS NULL) mine,
                       (SELECT string_agg(DISTINCT s.name, ', ') FROM public.faculty_subject_assignments a
                        JOIN public.academic_subjects s ON s.college_id = a.college_id AND s.id = a.subject_id
                        WHERE a.college_id = fp.college_id AND a.faculty_id = fp.faculty_id AND a.division_id = ? AND a.ended_at IS NULL)
                FROM public.faculty_profiles fp JOIN public.users u ON u.id = fp.faculty_id AND u.college_id = fp.college_id
                WHERE fp.college_id = ? AND u.is_active IS TRUE
                ORDER BY 3 DESC, u.full_name, u.id LIMIT 200
                """, (r, i) -> new FacultyChoice(r.getLong(1), r.getString(2), r.getBoolean(3), r.getString(4)),
                division, division, college);
    }

    /** Requests the student may see: open or accepted ones of their class or college, and every one they joined. */
    public List<FacultyRequest> requests(Account actor) {
        long college = skills.college(actor);
        Long division = skills.division(college, actor.id());
        return db.query(SELECT + " AND (" + JOINED + " OR (fr.status IN ('OPEN', 'ACCEPTED') AND (fr.audience = 'COLLEGE' OR fr.division_id = ?)))"
                        + " ORDER BY (fr.status IN ('OPEN', 'ACCEPTED')) DESC, fr.id DESC LIMIT 50",
                (r, i) -> row(r), actor.id(), actor.id(), college, actor.id(), division);
    }

    private static final String JOINED = "EXISTS (SELECT 1 FROM public.skill_faculty_request_members jm"
            + " WHERE jm.college_id = fr.college_id AND jm.request_id = fr.id AND jm.student_id = ?)";

    private FacultyRequest visible(long college, Account actor, long id, boolean lock) {
        Long division = skills.division(college, actor.id());
        FacultyRequest found = db.query(SELECT + " AND fr.id = ? AND (" + JOINED + " OR fr.audience = 'COLLEGE' OR fr.division_id = ?)",
                (r, i) -> row(r), actor.id(), actor.id(), college, id, actor.id(), division).stream().findFirst()
                .orElseThrow(ApiError.SKILL_NOT_FOUND::exception);
        if (lock) db.query("SELECT id FROM public.skill_faculty_requests WHERE college_id = ? AND id = ? FOR UPDATE",
                (r, i) -> r.getLong(1), college, id);
        return found;
    }

    public FacultyRequestDetail request(Account actor, long id) {
        long college = skills.college(actor);
        return new FacultyRequestDetail(visible(college, actor, id, false), students(college, id));
    }

    public FacultyRequest ask(Account actor, AskInput input) {
        long college = skills.college(actor);
        if (input == null || input.facultyId() == null) throw ApiError.SKILL_INVALID.exception();
        String topic = SkillExchange.text(input.topic(), 120, true, ApiError.SKILL_INVALID);
        if (topic.length() < 3) throw ApiError.SKILL_INVALID.exception();
        String details = SkillExchange.text(input.details(), 1000, false, ApiError.SKILL_INVALID);
        String audience = SkillExchange.cleanAudience(input.audience());
        if (!Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.faculty_profiles fp JOIN public.users u ON u.id = fp.faculty_id
                               WHERE fp.college_id = ? AND fp.faculty_id = ? AND u.is_active IS TRUE)
                """, Boolean.class, college, input.facultyId()))) throw ApiError.SKILL_NOT_FOUND.exception();
        Long division = skills.audienceClass(college, actor.id(), audience);
        Long open = db.queryForObject("SELECT count(*) FROM public.skill_faculty_requests WHERE college_id = ? AND created_by = ? AND status = 'OPEN'",
                Long.class, college, actor.id());
        if (open != null && open >= 5) throw ApiError.SKILL_STATE_CONFLICT.exception();
        Long id = db.queryForObject("""
                INSERT INTO public.skill_faculty_requests (college_id, faculty_id, created_by, topic, details, audience, division_id)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, college, input.facultyId(), actor.id(), topic, details, audience, division);
        long request = id == null ? 0 : id;
        db.update("INSERT INTO public.skill_faculty_request_members (college_id, request_id, student_id) VALUES (?, ?, ?)",
                college, request, actor.id());
        FacultyRequest created = visible(college, actor, request, false);
        notices.faculty(college, input.facultyId(), "NEW", "request:" + request + ":new", "Students want to learn from you",
                created.createdBy() + " asked you to teach \"" + topic + "\". Classmates can join before you reply.", request);
        return created;
    }

    /** "Me too" while the request is open, or accepted and not yet started. */
    public FacultyRequest join(Account actor, long id) {
        long college = skills.college(actor);
        FacultyRequest r = visible(college, actor, id, true);
        boolean open = r.status().equals("OPEN") || (r.status().equals("ACCEPTED") && r.startsAt().isAfter(skills.now()));
        if (!open || r.joined()) throw ApiError.SKILL_STATE_CONFLICT.exception();
        db.update("""
                INSERT INTO public.skill_faculty_request_members (college_id, request_id, student_id) VALUES (?, ?, ?)
                ON CONFLICT (request_id, student_id) DO NOTHING
                """, college, id, actor.id());
        long count = r.students() + 1;
        notices.faculty(college, r.facultyId(), "JOINED", "request:" + id + ":joined:" + actor.id(), "More students joined",
                count + " students now want to learn \"" + r.topic() + "\"", id);
        return visible(college, actor, id, false);
    }

    public FacultyRequest leave(Account actor, long id) {
        long college = skills.college(actor);
        FacultyRequest r = visible(college, actor, id, true);
        if (r.mine() || !r.joined() || !(r.status().equals("OPEN") || r.status().equals("ACCEPTED")))
            throw ApiError.SKILL_STATE_CONFLICT.exception();
        db.update("DELETE FROM public.skill_faculty_request_members WHERE college_id = ? AND request_id = ? AND student_id = ?",
                college, id, actor.id());
        return visible(college, actor, id, false);
    }

    /** The student who asked cancels it while the faculty member has not replied. */
    public FacultyRequest cancel(Account actor, long id) {
        long college = skills.college(actor);
        FacultyRequest r = visible(college, actor, id, true);
        if (!r.mine() || !r.status().equals("OPEN")) throw ApiError.SKILL_STATE_CONFLICT.exception();
        db.update("""
                UPDATE public.skill_faculty_requests SET status = 'CANCELLED', decided_at = statement_timestamp(),
                    reason = 'Cancelled by the student who asked' WHERE college_id = ? AND id = ?
                """, college, id);
        notices.faculty(college, r.facultyId(), "CANCELLED", "request:" + id + ":cancelled", "Request withdrawn",
                "The students withdrew their request for \"" + r.topic() + "\"", id);
        tellStudents(college, id, actor.id(), "FACULTY_CANCELLED", "Request cancelled",
                r.createdBy().split(" ")[0] + " cancelled the request to " + r.faculty() + " for \"" + r.topic() + "\"");
        return visible(college, actor, id, false);
    }

    private void tellStudents(long college, long request, Long except, String kind, String title, String body) {
        for (Student s : students(college, request)) {
            if (except != null && s.id() == except) continue;
            notices.student(college, s.id(), kind, "faculty:" + request + ":" + kind + ":" + s.id(), title, body,
                    SkillNotices.Links.faculty(request));
        }
    }

    // ---------------------------------------------------------------- Faculty

    private FacultyRequest mine(long college, Account actor, long id, boolean lock) {
        FacultyRequest found = db.query(SELECT + " AND fr.id = ? AND fr.faculty_id = ?" + (lock ? " FOR UPDATE OF fr" : ""),
                (r, i) -> row(r), actor.id(), actor.id(), college, id, actor.id()).stream().findFirst()
                .orElseThrow(ApiError.SKILL_NOT_FOUND::exception);
        return found;
    }

    /** Requests sent to the signed-in faculty member; open ones first. */
    public List<FacultyRequest> facultyRequests(Account actor) {
        long college = facultyCollege(actor);
        return db.query(SELECT + " AND fr.faculty_id = ? ORDER BY (fr.status = 'OPEN') DESC, (fr.status = 'ACCEPTED') DESC, fr.id DESC LIMIT 100",
                (r, i) -> row(r), actor.id(), actor.id(), college, actor.id());
    }

    public FacultyRequestDetail facultyRequest(Account actor, long id) {
        long college = facultyCollege(actor);
        return new FacultyRequestDetail(mine(college, actor, id, false), students(college, id));
    }

    private static String when(OffsetDateTime start) {
        return start.atZoneSameInstant(java.time.ZoneId.of("Asia/Kolkata"))
                .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", java.util.Locale.UK));
    }

    public FacultyRequestDetail accept(Account actor, long id, AcceptInput input) {
        long college = facultyCollege(actor);
        SkillExchange.SessionInput in = SkillExchange.cleanSession(input == null ? null
                : new SkillExchange.SessionInput(true, input.startsAt(), input.endsAt(), input.mode(), input.place()), skills.now());
        FacultyRequest r = mine(college, actor, id, true);
        if (!r.status().equals("OPEN")) throw ApiError.SKILL_STATE_CONFLICT.exception();
        db.update("""
                UPDATE public.skill_faculty_requests SET status = 'ACCEPTED', starts_at = ?, ends_at = ?, mode = ?, place = ?,
                    decided_at = statement_timestamp() WHERE college_id = ? AND id = ?
                """, in.startsAt(), in.endsAt(), in.mode(), in.place(), college, id);
        tellStudents(college, id, null, "FACULTY_ACCEPTED", r.faculty() + " will teach you",
                "\"" + r.topic() + "\" on " + when(in.startsAt()) + (in.mode().equals("ONLINE") ? " (online)" : " (in person)")
                        + (in.place().isEmpty() ? "" : " · " + in.place()));
        return facultyRequest(actor, id);
    }

    public FacultyRequestDetail decline(Account actor, long id, ReasonInput input) {
        long college = facultyCollege(actor);
        String reason = SkillExchange.text(input == null ? null : input.reason(), 300, false, ApiError.SKILL_INVALID);
        FacultyRequest r = mine(college, actor, id, true);
        if (!r.status().equals("OPEN")) throw ApiError.SKILL_STATE_CONFLICT.exception();
        db.update("""
                UPDATE public.skill_faculty_requests SET status = 'DECLINED', reason = ?, decided_at = statement_timestamp()
                WHERE college_id = ? AND id = ?
                """, reason.isEmpty() ? null : reason, college, id);
        tellStudents(college, id, null, "FACULTY_DECLINED", r.faculty() + " cannot teach this",
                "\"" + r.topic() + "\"" + (reason.isEmpty() ? "" : ": " + reason));
        return facultyRequest(actor, id);
    }

    /** action is "cancel" (an accepted session, with a reason) or "complete" (after it ends). */
    public FacultyRequestDetail act(Account actor, long id, String action, ReasonInput input) {
        long college = facultyCollege(actor);
        FacultyRequest r = mine(college, actor, id, true);
        if (!r.status().equals("ACCEPTED")) throw ApiError.SKILL_STATE_CONFLICT.exception();
        switch (action) {
            case "cancel" -> {
                String reason = SkillExchange.text(input == null ? null : input.reason(), 300, false, ApiError.SKILL_INVALID);
                db.update("UPDATE public.skill_faculty_requests SET status = 'CANCELLED', reason = ? WHERE college_id = ? AND id = ?",
                        reason.isEmpty() ? null : reason, college, id);
                tellStudents(college, id, null, "FACULTY_CANCELLED", "Session cancelled",
                        r.faculty() + " cancelled \"" + r.topic() + "\"" + (reason.isEmpty() ? "" : ": " + reason));
            }
            case "complete" -> {
                if (!r.canComplete()) throw ApiError.SKILL_STATE_CONFLICT.exception();
                db.update("UPDATE public.skill_faculty_requests SET status = 'COMPLETED' WHERE college_id = ? AND id = ?", college, id);
            }
            default -> throw ApiError.SKILL_INVALID.exception();
        }
        return facultyRequest(actor, id);
    }

    public List<FacultyNotice> notices(Account actor) {
        long college = facultyCollege(actor);
        return db.query("""
                SELECT id, kind, title, body, request_id, created_at, read_at FROM public.skill_faculty_notices
                WHERE college_id = ? AND faculty_id = ? ORDER BY id DESC LIMIT 100
                """, (r, i) -> new FacultyNotice(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5),
                r.getObject(6, OffsetDateTime.class), r.getObject(7, OffsetDateTime.class)), college, actor.id());
    }

    public long unread(Account actor) {
        long college = facultyCollege(actor);
        Long n = db.queryForObject("""
                SELECT count(*) FROM public.skill_faculty_notices WHERE college_id = ? AND faculty_id = ? AND read_at IS NULL
                """, Long.class, college, actor.id());
        return n == null ? 0 : n;
    }

    public void read(Account actor, long id) {
        long college = facultyCollege(actor);
        int changed = db.update("""
                UPDATE public.skill_faculty_notices SET read_at = statement_timestamp()
                WHERE college_id = ? AND faculty_id = ? AND id = ? AND read_at IS NULL
                """, college, actor.id(), id);
        if (changed == 0 && !Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.skill_faculty_notices WHERE college_id = ? AND faculty_id = ? AND id = ?)
                """, Boolean.class, college, actor.id(), id))) throw ApiError.NOTIFICATION_NOT_FOUND.exception();
    }
}
