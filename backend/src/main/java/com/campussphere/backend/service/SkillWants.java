package com.campussphere.backend.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.ApiError;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "I want to learn ..." posts. A post is seen by the poster's class or by their whole college (the poster chooses),
 * never by another college. Classmates discuss it in comments, say "Me too", or offer to teach; the poster accepts one
 * teacher, and the poster and everyone who said "Me too" then learn from that classmate (as a group when two or more).
 * Every lookup is limited to the signed-in student's own college and to posts they are allowed to see.
 */
@Service
@Transactional
public class SkillWants {
    public record WantInput(String title, String details, String category, String audience) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    public record TextInput(String text) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    public record Want(long id, long studentId, String student, String classLabel, String title, String details,
                       String category, String audience, String status, OffsetDateTime createdAt, long comments,
                       long supporters, long helpers, boolean mine, boolean meToo, String myHelp) {}
    public record Comment(long id, long authorId, String author, String body, OffsetDateTime createdAt, boolean mine, boolean deleted) {}
    /** Someone offering to teach. Everyone sees their own offer; only the poster sees all of them. */
    public record Helper(long id, long teacherId, String teacher, String classLabel, String message, String status,
                         OffsetDateTime createdAt, Double rating, long reviews) {}
    public record WantDetail(Want want, List<Comment> comments, List<Helper> helpers, List<String> supporterNames) {}

    private final JdbcTemplate db;
    private final SkillExchange skills;
    private final SkillNotices notices;
    private final SkillGroupMaker groups;

    public SkillWants(JdbcTemplate db, SkillExchange skills, SkillNotices notices, SkillGroupMaker groups) {
        this.db = db;
        this.skills = skills;
        this.notices = notices;
        this.groups = groups;
    }

    static WantInput clean(WantInput in) {
        if (in == null) throw ApiError.SKILL_INVALID.exception();
        String title = SkillExchange.text(in.title(), 80, true, ApiError.SKILL_INVALID);
        if (title.length() < 3 || !SkillExchange.CATEGORIES.contains(in.category())) throw ApiError.SKILL_INVALID.exception();
        return new WantInput(title, SkillExchange.text(in.details(), 1000, false, ApiError.SKILL_INVALID), in.category(),
                SkillExchange.cleanAudience(in.audience()));
    }

    private static final String CLASS_LABEL = """
            (SELECT p.name || ' · ' || d.name FROM public.student_enrollments e
             JOIN public.academic_programs p ON p.college_id = e.college_id AND p.id = e.program_id
             JOIN public.academic_divisions d ON d.college_id = e.college_id AND d.id = e.division_id
             WHERE e.college_id = %1$s.college_id AND e.student_id = %1$s.%2$s AND e.ended_at IS NULL LIMIT 1)""";

    // Arguments: me, me, me, college, [division for visibility] ...
    private static final String WANT_SELECT = """
            SELECT w.id, w.student_id, u.full_name, %s class_label, w.title, w.details, w.category, w.audience, w.status,
                   w.created_at,
                   (SELECT count(*) FROM public.skill_want_comments c WHERE c.college_id = w.college_id AND c.want_id = w.id
                      AND c.deleted_at IS NULL) comments,
                   (SELECT count(*) FROM public.skill_want_supporters s WHERE s.college_id = w.college_id AND s.want_id = w.id) supporters,
                   (SELECT count(*) FROM public.skill_want_helpers h WHERE h.college_id = w.college_id AND h.want_id = w.id
                      AND h.status IN ('OFFERED', 'ACCEPTED')) helpers,
                   w.student_id = ? mine,
                   EXISTS (SELECT 1 FROM public.skill_want_supporters s WHERE s.college_id = w.college_id AND s.want_id = w.id
                      AND s.student_id = ?) me_too,
                   (SELECT h.status FROM public.skill_want_helpers h WHERE h.college_id = w.college_id AND h.want_id = w.id
                      AND h.teacher_id = ?) my_help
            FROM public.skill_wants w JOIN public.users u ON u.id = w.student_id
            WHERE w.college_id = ?""".formatted(CLASS_LABEL.formatted("w", "student_id"));

    private static Want want(ResultSet r) throws SQLException {
        return new Want(r.getLong("id"), r.getLong("student_id"), r.getString("full_name"), r.getString("class_label"),
                r.getString("title"), r.getString("details"), r.getString("category"), r.getString("audience"),
                r.getString("status"), r.getObject("created_at", OffsetDateTime.class), r.getLong("comments"),
                r.getLong("supporters"), r.getLong("helpers"), r.getBoolean("mine"), r.getBoolean("me_too"), r.getString("my_help"));
    }

    /** Open posts the student may see (their class's and the college's), newest first; [mine] lists their own posts instead. */
    public List<Want> list(Account actor, boolean mine, String q, String category, int page) {
        long college = skills.college(actor);
        String query = q == null || q.isBlank() ? null : q.strip();
        if ((query != null && query.length() > 60) || (category != null && !SkillExchange.CATEGORIES.contains(category))
                || page < 0 || page > 1000) throw ApiError.SKILL_INVALID.exception();
        var sql = new StringBuilder(WANT_SELECT);
        var args = new ArrayList<Object>(List.of(actor.id(), actor.id(), actor.id(), college));
        if (mine) {
            sql.append(" AND w.student_id = ?");
            args.add(actor.id());
        } else {
            sql.append(" AND w.status = 'OPEN' AND u.is_active IS TRUE AND (w.audience = 'COLLEGE' OR w.division_id = ?)");
            args.add(skills.division(college, actor.id()));
        }
        if (query != null) {
            sql.append(" AND (strpos(lower(w.title), lower(?)) > 0 OR strpos(lower(w.details), lower(?)) > 0)");
            args.add(query); args.add(query);
        }
        if (category != null) { sql.append(" AND w.category = ?"); args.add(category); }
        sql.append(" ORDER BY w.id DESC LIMIT ? OFFSET ?");
        args.add(SkillExchange.PAGE); args.add(page * SkillExchange.PAGE);
        return db.query(sql.toString(), (r, i) -> want(r), args.toArray());
    }

    /** A post the student may see: their own, a college-wide one, or one of their class. Anything else is not found. */
    private Want visible(long college, Account actor, long id, boolean lock) {
        Long division = skills.division(college, actor.id());
        List<Want> found = db.query(WANT_SELECT + " AND w.id = ? AND (w.student_id = ? OR w.audience = 'COLLEGE' OR w.division_id = ?)",
                (r, i) -> want(r), actor.id(), actor.id(), actor.id(), college, id, actor.id(), division);
        if (found.isEmpty()) throw ApiError.SKILL_NOT_FOUND.exception();
        if (lock) db.query("SELECT id FROM public.skill_wants WHERE college_id = ? AND id = ? FOR UPDATE", (r, i) -> r.getLong(1), college, id);
        return found.get(0);
    }

    public WantDetail detail(Account actor, long id) {
        long college = skills.college(actor);
        Want want = visible(college, actor, id, false);
        List<Comment> comments = db.query("""
                SELECT c.id, c.author_id, u.full_name, c.body, c.created_at, c.deleted_at IS NOT NULL
                FROM public.skill_want_comments c JOIN public.users u ON u.id = c.author_id
                WHERE c.college_id = ? AND c.want_id = ? ORDER BY c.id LIMIT 200
                """, (r, i) -> new Comment(r.getLong(1), r.getLong(2), r.getString(3), r.getBoolean(6) ? "" : r.getString(4),
                r.getObject(5, OffsetDateTime.class), r.getLong(2) == actor.id(), r.getBoolean(6)), college, id);
        // The poster sees every offer to teach; anyone else only their own.
        List<Helper> helpers = db.query(("""
                SELECT h.id, h.teacher_id, u.full_name, %s class_label, h.message, h.status, h.created_at,
                       (SELECT avg(r.rating) FROM public.skill_reviews r WHERE r.college_id = h.college_id AND r.reviewee_id = h.teacher_id) rating,
                       (SELECT count(*) FROM public.skill_reviews r WHERE r.college_id = h.college_id AND r.reviewee_id = h.teacher_id) reviews
                FROM public.skill_want_helpers h JOIN public.users u ON u.id = h.teacher_id
                WHERE h.college_id = ? AND h.want_id = ? AND (? OR h.teacher_id = ?) ORDER BY h.id
                """).formatted(CLASS_LABEL.formatted("h", "teacher_id")), (r, i) -> {
                    Number rating = (Number) r.getObject("rating");
                    return new Helper(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6),
                            r.getObject(7, OffsetDateTime.class), rating == null ? null : Math.round(rating.doubleValue() * 10) / 10.0,
                            r.getLong("reviews"));
                }, college, id, want.mine(), actor.id());
        List<String> supporters = db.queryForList("""
                SELECT split_part(u.full_name, ' ', 1) FROM public.skill_want_supporters s JOIN public.users u ON u.id = s.student_id
                WHERE s.college_id = ? AND s.want_id = ? ORDER BY s.created_at LIMIT 50
                """, String.class, college, id);
        return new WantDetail(want, comments, helpers, supporters);
    }

    public Want create(Account actor, WantInput input) {
        long college = skills.college(actor);
        WantInput in = clean(input);
        Long division = skills.audienceClass(college, actor.id(), in.audience());
        Long open = db.queryForObject("SELECT count(*) FROM public.skill_wants WHERE college_id = ? AND student_id = ? AND status = 'OPEN'",
                Long.class, college, actor.id());
        if (open != null && open >= 10) throw ApiError.SKILL_STATE_CONFLICT.exception();
        Long id = db.queryForObject("""
                INSERT INTO public.skill_wants (college_id, student_id, title, details, category, audience, division_id)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, college, actor.id(), in.title(), in.details(), in.category(), in.audience(), division);
        return visible(college, actor, id == null ? 0 : id, false);
    }

    public Want close(Account actor, long id) {
        long college = skills.college(actor);
        Want want = visible(college, actor, id, true);
        if (!want.mine() || want.status().equals("CLOSED")) throw ApiError.SKILL_STATE_CONFLICT.exception();
        db.update("UPDATE public.skill_wants SET status = 'CLOSED', closed_at = statement_timestamp() WHERE college_id = ? AND id = ?",
                college, id);
        db.update("""
                UPDATE public.skill_want_helpers SET status = 'DECLINED', decided_at = statement_timestamp()
                WHERE college_id = ? AND want_id = ? AND status = 'OFFERED'
                """, college, id);
        return visible(college, actor, id, false);
    }

    // ---- Discussion ----

    public Comment comment(Account actor, long id, TextInput input) {
        long college = skills.college(actor);
        String body = SkillExchange.text(input == null ? null : input.text(), 1000, true, ApiError.SKILL_INVALID);
        Want want = visible(college, actor, id, false);
        if (want.status().equals("CLOSED")) throw ApiError.SKILL_STATE_CONFLICT.exception();
        Long commentId = db.queryForObject("""
                INSERT INTO public.skill_want_comments (college_id, want_id, author_id, body) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, college, id, actor.id(), body);
        long cid = commentId == null ? 0 : commentId;
        if (!want.mine()) {
            String who = db.queryForObject("SELECT split_part(full_name, ' ', 1) FROM public.users WHERE id = ?", String.class, actor.id());
            notices.student(college, want.studentId(), "WANT_COMMENT", "want:" + id + ":comment:" + cid, "New comment on your post",
                    who + " commented on \"" + want.title() + "\": " + body, SkillNotices.Links.want(id));
        }
        return detail(actor, id).comments().stream().filter(c -> c.id() == cid).findFirst()
                .orElseThrow(ApiError.SKILL_NOT_FOUND::exception);
    }

    public void deleteComment(Account actor, long id, long commentId) {
        long college = skills.college(actor);
        visible(college, actor, id, false);
        int changed = db.update("""
                UPDATE public.skill_want_comments SET deleted_at = statement_timestamp(), body = ''
                WHERE college_id = ? AND want_id = ? AND id = ? AND author_id = ? AND deleted_at IS NULL
                """, college, id, commentId, actor.id());
        if (changed == 0) throw ApiError.SKILL_NOT_FOUND.exception();
    }

    // ---- Me too ----

    public Want meToo(Account actor, long id, boolean join) {
        long college = skills.college(actor);
        Want want = visible(college, actor, id, true);
        if (want.mine() || !want.status().equals("OPEN")) throw ApiError.SKILL_STATE_CONFLICT.exception();
        if (join) db.update("""
                INSERT INTO public.skill_want_supporters (college_id, want_id, student_id) VALUES (?, ?, ?)
                ON CONFLICT (want_id, student_id) DO NOTHING
                """, college, id, actor.id());
        else db.update("DELETE FROM public.skill_want_supporters WHERE college_id = ? AND want_id = ? AND student_id = ?",
                college, id, actor.id());
        return visible(college, actor, id, false);
    }

    // ---- Offers to teach ----

    public Want help(Account actor, long id, TextInput input) {
        long college = skills.college(actor);
        String message = SkillExchange.text(input == null ? null : input.text(), 500, false, ApiError.SKILL_INVALID);
        Want want = visible(college, actor, id, true);
        if (want.mine() || !want.status().equals("OPEN")) throw ApiError.SKILL_STATE_CONFLICT.exception();
        int changed = db.update("""
                INSERT INTO public.skill_want_helpers (college_id, want_id, teacher_id, message) VALUES (?, ?, ?, ?)
                ON CONFLICT (want_id, teacher_id) DO UPDATE SET status = 'OFFERED', message = EXCLUDED.message,
                    decided_at = NULL, created_at = statement_timestamp() WHERE skill_want_helpers.status = 'WITHDRAWN'
                """, college, id, actor.id(), message);
        if (changed == 0) throw ApiError.SKILL_DUPLICATE.exception();
        String who = db.queryForObject("SELECT split_part(full_name, ' ', 1) FROM public.users WHERE id = ?", String.class, actor.id());
        notices.student(college, want.studentId(), "WANT_HELPER", "want:" + id + ":helper:" + actor.id() + ":" + System.currentTimeMillis(),
                "Someone can teach you", who + " offered to teach \"" + want.title() + "\"" + (message.isEmpty() ? "" : ": " + message),
                SkillNotices.Links.want(id));
        return visible(college, actor, id, false);
    }

    public Want withdraw(Account actor, long id) {
        long college = skills.college(actor);
        visible(college, actor, id, true);
        int changed = db.update("""
                UPDATE public.skill_want_helpers SET status = 'WITHDRAWN', decided_at = statement_timestamp()
                WHERE college_id = ? AND want_id = ? AND teacher_id = ? AND status = 'OFFERED'
                """, college, id, actor.id());
        if (changed == 0) throw ApiError.SKILL_STATE_CONFLICT.exception();
        return visible(college, actor, id, false);
    }

    /** The poster declines one offer to teach. */
    public WantDetail decline(Account actor, long id, long helperId) {
        long college = skills.college(actor);
        Want want = visible(college, actor, id, true);
        if (!want.mine()) throw ApiError.SKILL_NOT_FOUND.exception();
        Long teacher = db.query("""
                UPDATE public.skill_want_helpers SET status = 'DECLINED', decided_at = statement_timestamp()
                WHERE college_id = ? AND want_id = ? AND id = ? AND status = 'OFFERED' RETURNING teacher_id
                """, (r, i) -> r.getLong(1), college, id, helperId).stream().findFirst()
                .orElseThrow(ApiError.SKILL_STATE_CONFLICT::exception);
        notices.student(college, teacher, "WANT_MATCHED", "want:" + id + ":declined:" + helperId, "Offer not taken",
                want.student().split(" ")[0] + " chose not to take your offer for \"" + want.title() + "\"", SkillNotices.Links.want(id));
        return detail(actor, id);
    }

    /**
     * The poster accepts one offer to teach. The poster, and everyone who said "Me too", get an accepted request with
     * that classmate; with two or more learners they form a learning group with a group chat. Other offers are declined.
     * Returns the poster's own request.
     */
    public SkillExchange.Request accept(Account actor, long id, long helperId) {
        long college = skills.college(actor);
        Want want = visible(college, actor, id, true);
        if (!want.mine()) throw ApiError.SKILL_NOT_FOUND.exception();
        if (!want.status().equals("OPEN")) throw ApiError.SKILL_STATE_CONFLICT.exception();
        Long teacher = db.query("""
                UPDATE public.skill_want_helpers SET status = 'ACCEPTED', decided_at = statement_timestamp()
                WHERE college_id = ? AND want_id = ? AND id = ? AND status = 'OFFERED' RETURNING teacher_id
                """, (r, i) -> r.getLong(1), college, id, helperId).stream().findFirst()
                .orElseThrow(ApiError.SKILL_STATE_CONFLICT::exception);
        List<Long> declined = db.query("""
                UPDATE public.skill_want_helpers SET status = 'DECLINED', decided_at = statement_timestamp()
                WHERE college_id = ? AND want_id = ? AND status = 'OFFERED' RETURNING teacher_id
                """, (r, i) -> r.getLong(1), college, id);
        db.update("UPDATE public.skill_wants SET status = 'MATCHED', matched_at = statement_timestamp() WHERE college_id = ? AND id = ?",
                college, id);
        var learners = new ArrayList<Long>();
        learners.add(actor.id());
        learners.addAll(db.queryForList("""
                SELECT student_id FROM public.skill_want_supporters WHERE college_id = ? AND want_id = ? AND student_id <> ?
                ORDER BY created_at
                """, Long.class, college, id, teacher));
        String poster = want.student().split(" ")[0];
        long mine = 0;
        for (Long learner : learners) {
            Long request = db.query("""
                    INSERT INTO public.skill_requests (college_id, want_id, provider_id, learner_id, status, decided_at)
                    VALUES (?, ?, ?, ?, 'ACCEPTED', statement_timestamp())
                    ON CONFLICT (want_id, learner_id) WHERE status IN ('REQUESTED', 'ACCEPTED') DO NOTHING RETURNING id
                    """, (r, i) -> r.getLong(1), college, id, teacher, learner).stream().findFirst().orElse(null);
            if (request == null) continue;
            if (learner.longValue() == actor.id()) mine = request;
            else notices.student(college, learner, "WANT_MATCHED", "want:" + id + ":matched:" + learner, "A teacher was found",
                    "You said \"Me too\" to \"" + want.title() + "\". You will learn it with " + poster + ".", SkillNotices.Links.want(id));
            groups.afterAccepted(college, request);
        }
        notices.student(college, teacher, "WANT_MATCHED", "want:" + id + ":chosen", "You were chosen to teach",
                poster + " chose you to teach \"" + want.title() + "\"" + (learners.size() > 1 ? " (" + learners.size() + " learners)" : ""),
                SkillNotices.Links.want(id));
        for (Long other : declined) notices.student(college, other, "WANT_MATCHED", "want:" + id + ":other:" + other,
                "Someone else will teach", poster + " chose another classmate for \"" + want.title() + "\"", SkillNotices.Links.want(id));
        return skills.request(actor, mine);
    }
}
