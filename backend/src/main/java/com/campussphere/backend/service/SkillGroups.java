package com.campussphere.backend.service;

import java.time.OffsetDateTime;
import java.util.List;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.ApiError;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Learning groups: classmates learning the same thing from one student together. Only the teaching student and the
 * group's current learners can see a group; the teaching student sets the group's sessions. The group chat itself
 * lives in Chats. Everything is limited to the signed-in student's own college.
 */
@Service
@Transactional
public class SkillGroups {
    public record GroupSessionInput(OffsetDateTime startsAt, OffsetDateTime endsAt, String mode, String place) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    public record Member(long studentId, String name, String classLabel, long requestId) {}
    public record GroupSession(long id, OffsetDateTime startsAt, OffsetDateTime endsAt, String mode, String place, String status,
                               String cancelReason, boolean canComplete) {}
    public record Group(long id, String name, long teacherId, String teacher, boolean iTeach, String status, Long conversationId,
                        long members, GroupSession nextSession, Long myRequestId) {}
    public record GroupDetail(Group group, List<Member> members, List<GroupSession> sessions) {}

    private final JdbcTemplate db;
    private final SkillExchange skills;
    private final SkillNotices notices;

    public SkillGroups(JdbcTemplate db, SkillExchange skills, SkillNotices notices) {
        this.db = db;
        this.skills = skills;
        this.notices = notices;
    }

    // Arguments: me, college, me, me. Only groups the student teaches or currently learns in.
    private static final String GROUP_SELECT = """
            SELECT g.id, g.name, g.teacher_id, u.full_name, g.status, g.conversation_id,
                   (SELECT count(*) FROM public.skill_group_members m WHERE m.college_id = g.college_id AND m.group_id = g.id
                      AND m.left_at IS NULL) members,
                   (SELECT m.request_id FROM public.skill_group_members m WHERE m.college_id = g.college_id AND m.group_id = g.id
                      AND m.learner_id = ? AND m.left_at IS NULL) my_request
            FROM public.skill_groups g JOIN public.users u ON u.id = g.teacher_id
            WHERE g.college_id = ? AND (g.teacher_id = ? OR EXISTS (SELECT 1 FROM public.skill_group_members m
                  WHERE m.college_id = g.college_id AND m.group_id = g.id AND m.learner_id = ? AND m.left_at IS NULL))""";

    private List<Group> query(long college, Account actor, String extra, Object... more) {
        Object[] args = new Object[4 + more.length];
        args[0] = actor.id(); args[1] = college; args[2] = actor.id(); args[3] = actor.id();
        System.arraycopy(more, 0, args, 4, more.length);
        List<Group> groups = db.query(GROUP_SELECT + extra, (r, i) -> new Group(r.getLong(1), r.getString(2), r.getLong(3),
                r.getString(4), r.getLong(3) == actor.id(), r.getString(5), (Long) r.getObject(6, Long.class), r.getLong(7), null,
                (Long) r.getObject(8, Long.class)), args);
        return groups.stream().map(g -> new Group(g.id(), g.name(), g.teacherId(), g.teacher(), g.iTeach(), g.status(),
                g.conversationId(), g.members(), next(college, g.id()), g.myRequestId())).toList();
    }

    private GroupSession next(long college, long group) {
        return sessions(college, group, " AND status = 'SCHEDULED' AND ends_at >= ? ORDER BY starts_at LIMIT 1", skills.now())
                .stream().findFirst().orElse(null);
    }

    private List<GroupSession> sessions(long college, long group, String extra, Object... more) {
        Object[] args = new Object[2 + more.length];
        args[0] = college; args[1] = group;
        System.arraycopy(more, 0, args, 2, more.length);
        OffsetDateTime now = skills.now();
        return db.query("""
                SELECT id, starts_at, ends_at, mode, place, status, cancel_reason FROM public.skill_group_sessions
                WHERE college_id = ? AND group_id = ?""" + extra, (r, i) -> {
                    OffsetDateTime ends = r.getObject(3, OffsetDateTime.class);
                    return new GroupSession(r.getLong(1), r.getObject(2, OffsetDateTime.class), ends, r.getString(4),
                            r.getString(5), r.getString(6), r.getString(7), "SCHEDULED".equals(r.getString(6)) && !now.isBefore(ends));
                }, args);
    }

    public List<Group> groups(Account actor) {
        long college = skills.college(actor);
        return query(college, actor, " ORDER BY (g.status = 'ACTIVE') DESC, g.id DESC LIMIT 50");
    }

    private Group group(long college, Account actor, long id) {
        return query(college, actor, " AND g.id = ?", id).stream().findFirst().orElseThrow(ApiError.SKILL_NOT_FOUND::exception);
    }

    public GroupDetail detail(Account actor, long id) {
        long college = skills.college(actor);
        Group group = group(college, actor, id);
        List<Member> members = db.query("""
                SELECT m.learner_id, u.full_name,
                       (SELECT p.name || ' · ' || d.name FROM public.student_enrollments e
                        JOIN public.academic_programs p ON p.college_id = e.college_id AND p.id = e.program_id
                        JOIN public.academic_divisions d ON d.college_id = e.college_id AND d.id = e.division_id
                        WHERE e.college_id = m.college_id AND e.student_id = m.learner_id AND e.ended_at IS NULL LIMIT 1),
                       m.request_id
                FROM public.skill_group_members m JOIN public.users u ON u.id = m.learner_id
                WHERE m.college_id = ? AND m.group_id = ? AND m.left_at IS NULL ORDER BY m.joined_at
                """, (r, i) -> new Member(r.getLong(1), r.getString(2), r.getString(3), r.getLong(4)), college, id);
        return new GroupDetail(group, members, sessions(college, id, " ORDER BY starts_at DESC LIMIT 50"));
    }

    private Group teaching(long college, Account actor, long id) {
        Group group = group(college, actor, id);
        if (!group.iTeach()) throw ApiError.SKILL_NOT_FOUND.exception();
        if (!group.status().equals("ACTIVE")) throw ApiError.SKILL_STATE_CONFLICT.exception();
        return group;
    }

    private List<Long> learners(long college, long group) {
        return db.queryForList("""
                SELECT learner_id FROM public.skill_group_members WHERE college_id = ? AND group_id = ? AND left_at IS NULL
                """, Long.class, college, group);
    }

    /** The teaching student sets a session for the whole group; every learner is told and later reminded. */
    public GroupDetail schedule(Account actor, long id, GroupSessionInput input) {
        long college = skills.college(actor);
        SkillExchange.SessionInput in = SkillExchange.cleanSession(input == null ? null
                : new SkillExchange.SessionInput(true, input.startsAt(), input.endsAt(), input.mode(), input.place()), skills.now());
        Group group = teaching(college, actor, id);
        Long session = db.queryForObject("""
                INSERT INTO public.skill_group_sessions (college_id, group_id, starts_at, ends_at, mode, place)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, college, id, in.startsAt(), in.endsAt(), in.mode(), in.place());
        for (Long learner : learners(college, id)) {
            notices.student(college, learner, "GROUP_SESSION", "group-session:" + session + ":new:" + learner, "Group session set",
                    group.teacher().split(" ")[0] + " set a session for " + group.name(), SkillNotices.Links.group(id, session));
        }
        return detail(actor, id);
    }

    /** action is "cancel" (with an optional reason) or "complete" (after it ends). Teaching student only. */
    public GroupDetail act(Account actor, long id, long sessionId, String action, String reason) {
        long college = skills.college(actor);
        Group group = teaching(college, actor, id);
        GroupSession s = sessions(college, id, " AND id = ? FOR UPDATE", sessionId).stream().findFirst()
                .orElseThrow(ApiError.SKILL_NOT_FOUND::exception);
        if (!s.status().equals("SCHEDULED")) throw ApiError.SKILL_STATE_CONFLICT.exception();
        switch (action) {
            case "cancel" -> {
                String why = SkillExchange.text(reason, 300, false, ApiError.SKILL_INVALID);
                db.update("""
                        UPDATE public.skill_group_sessions SET status = 'CANCELLED', cancelled_at = statement_timestamp(), cancel_reason = ?
                        WHERE college_id = ? AND id = ?
                        """, why.isEmpty() ? null : why, college, sessionId);
                for (Long learner : learners(college, id)) {
                    notices.student(college, learner, "GROUP_CANCELLED", "group-session:" + sessionId + ":cancelled:" + learner,
                            "Group session cancelled", group.name() + " session was cancelled" + (why.isEmpty() ? "" : ": " + why),
                            SkillNotices.Links.group(id, sessionId));
                }
            }
            case "complete" -> {
                if (!s.canComplete()) throw ApiError.SKILL_STATE_CONFLICT.exception();
                db.update("UPDATE public.skill_group_sessions SET status = 'COMPLETED', completed_at = statement_timestamp() WHERE college_id = ? AND id = ?",
                        college, sessionId);
            }
            default -> throw ApiError.SKILL_INVALID.exception();
        }
        return detail(actor, id);
    }

    /** A learner leaves the group: their request is cancelled, which also takes them out of the group chat. */
    public void leave(Account actor, long id) {
        long college = skills.college(actor);
        Group group = group(college, actor, id);
        if (group.iTeach() || group.myRequestId() == null) throw ApiError.SKILL_STATE_CONFLICT.exception();
        skills.decide(actor, group.myRequestId(), "cancel");
    }
}
