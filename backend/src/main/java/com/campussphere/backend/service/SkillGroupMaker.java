package com.campussphere.backend.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Forms learning groups: when two or more classmates are accepted to learn the same thing (one offered skill, or one
 * "I want to learn" post) from the same student, they learn together in one group of up to [MAX] learners, with its
 * own group chat (teacher and learners only). Runs inside the caller's transaction, always within one college.
 */
@Component
class SkillGroupMaker {
    static final int MAX = 5;

    /** An accepted request, as far as grouping needs it. */
    record Accepted(long id, long teacher, long learner, Long offer, Long want, String status) {}

    private final JdbcTemplate db;
    private final ChatStream stream;
    private final SkillNotices notices;

    @Autowired
    SkillGroupMaker(JdbcTemplate db, ChatStream stream, SkillNotices notices) {
        this.db = db;
        this.stream = stream;
        this.notices = notices;
    }

    private Accepted request(long college, long id) {
        return db.query("""
                SELECT id, provider_id, learner_id, offer_id, want_id, status FROM public.skill_requests
                WHERE college_id = ? AND id = ?
                """, (r, i) -> new Accepted(r.getLong(1), r.getLong(2), r.getLong(3), (Long) r.getObject(4, Long.class),
                (Long) r.getObject(5, Long.class), r.getString(6)), college, id).stream().findFirst().orElse(null);
    }

    private static String source(Accepted r) {
        return r.offer() != null ? "offer_id" : "want_id";
    }

    private static long sourceId(Accepted r) {
        return r.offer() != null ? r.offer() : r.want();
    }

    /** After a request is accepted: the learner joins the active group for that skill, or a group forms. */
    void afterAccepted(long college, long requestId) {
        Accepted r = request(college, requestId);
        if (r == null || !r.status().equals("ACCEPTED")) return;
        String column = source(r);
        Long group = db.query("SELECT id FROM public.skill_groups WHERE college_id = ? AND " + column
                + " = ? AND status = 'ACTIVE' FOR UPDATE", (x, i) -> x.getLong(1), college, sourceId(r)).stream().findFirst().orElse(null);
        if (group != null) {
            Long size = db.queryForObject("SELECT count(*) FROM public.skill_group_members WHERE college_id = ? AND group_id = ? AND left_at IS NULL",
                    Long.class, college, group);
            if (size != null && size >= MAX) return;
            join(college, group, r);
            publish(college, group);
            return;
        }
        // Other accepted learners of the same skill and teacher who are not in a group yet.
        List<Accepted> others = db.query("SELECT id, provider_id, learner_id, offer_id, want_id, status FROM public.skill_requests q "
                + "WHERE q.college_id = ? AND q." + column + " = ? AND q.provider_id = ? AND q.status = 'ACCEPTED' AND q.id <> ? "
                + "AND NOT EXISTS (SELECT 1 FROM public.skill_group_members m JOIN public.skill_groups g "
                + "ON g.college_id = m.college_id AND g.id = m.group_id WHERE m.college_id = q.college_id "
                + "AND m.request_id = q.id AND m.left_at IS NULL AND g.status = 'ACTIVE') ORDER BY q.decided_at, q.id LIMIT ?",
                (x, i) -> new Accepted(x.getLong(1), x.getLong(2), x.getLong(3), (Long) x.getObject(4, Long.class),
                        (Long) x.getObject(5, Long.class), x.getString(6)), college, sourceId(r), r.teacher(), r.id(), MAX - 1);
        if (others.isEmpty()) return;
        String skill = db.queryForObject(r.offer() != null
                ? "SELECT name FROM public.skill_offers WHERE college_id = ? AND id = ?"
                : "SELECT title FROM public.skill_wants WHERE college_id = ? AND id = ?", String.class, college, sourceId(r));
        String name = ("Learn: " + skill);
        if (name.length() > 80) name = name.substring(0, 80);
        Long chat = db.queryForObject("""
                INSERT INTO public.chat_conversations (college_id, kind, name, description, join_policy, created_by)
                VALUES (?, 'GROUP', ?, ?, 'INVITE', ?) RETURNING id
                """, Long.class, college, name, "Learning group for " + skill + " (Skill Exchange)", r.teacher());
        db.update("INSERT INTO public.chat_members (college_id, conversation_id, student_id, role) VALUES (?, ?, ?, 'OWNER')",
                college, chat, r.teacher());
        Long created = db.queryForObject("""
                INSERT INTO public.skill_groups (college_id, teacher_id, offer_id, want_id, name, conversation_id)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, college, r.teacher(), r.offer(), r.want(), name, chat);
        long id = created == null ? 0 : created;
        var members = new ArrayList<>(others);
        members.add(0, r);
        for (Accepted m : members) join(college, id, m);
        notices.student(college, r.teacher(), "GROUP_JOINED", "group:" + id + ":teacher", "Learning group formed",
                members.size() + " classmates will learn " + skill + " from you together. A group chat was created.",
                SkillNotices.Links.group(id, null));
        publish(college, id);
    }

    private void join(long college, long group, Accepted r) {
        db.update("""
                INSERT INTO public.skill_group_members (college_id, group_id, learner_id, request_id) VALUES (?, ?, ?, ?)
                ON CONFLICT (group_id, learner_id) DO UPDATE SET left_at = NULL, request_id = EXCLUDED.request_id,
                    joined_at = statement_timestamp() WHERE skill_group_members.left_at IS NOT NULL
                """, college, group, r.learner(), r.id());
        Long chat = chat(college, group);
        if (chat != null) db.update("""
                INSERT INTO public.chat_members (college_id, conversation_id, student_id) VALUES (?, ?, ?)
                ON CONFLICT (conversation_id, student_id) DO UPDATE SET left_at = NULL, role = 'MEMBER', left_message_id = NULL,
                    hidden = false, left_by_choice = false, joined_at = statement_timestamp() WHERE chat_members.left_at IS NOT NULL
                """, college, chat, r.learner());
        notices.student(college, r.learner(), "GROUP_JOINED", "group:" + group + ":joined:" + r.id(), "You joined a learning group",
                "You will learn together with classmates. Open the group to see sessions and the group chat.",
                SkillNotices.Links.group(group, null));
    }

    /** After a request is cancelled: the learner leaves its group; an empty group closes and its chat is archived. */
    void afterCancelled(long college, long requestId) {
        List<long[]> rows = db.query("""
                SELECT m.group_id, m.learner_id FROM public.skill_group_members m
                JOIN public.skill_groups g ON g.college_id = m.college_id AND g.id = m.group_id
                WHERE m.college_id = ? AND m.request_id = ? AND m.left_at IS NULL AND g.status = 'ACTIVE'
                """, (r, i) -> new long[]{r.getLong(1), r.getLong(2)}, college, requestId);
        for (long[] row : rows) {
            long group = row[0];
            db.update("UPDATE public.skill_group_members SET left_at = statement_timestamp() WHERE college_id = ? AND group_id = ? AND learner_id = ?",
                    college, group, row[1]);
            Long chat = chat(college, group);
            // Out of the group chat too, keeping what was said until now readable (like leaving any group).
            if (chat != null) db.update("""
                    UPDATE public.chat_members SET left_at = statement_timestamp(),
                        left_message_id = (SELECT coalesce(max(id), 0) FROM public.chat_messages WHERE college_id = ? AND conversation_id = ?),
                        last_read_message_id = GREATEST(last_read_message_id,
                            (SELECT coalesce(max(id), 0) FROM public.chat_messages WHERE college_id = ? AND conversation_id = ?))
                    WHERE college_id = ? AND conversation_id = ? AND student_id = ? AND left_at IS NULL
                    """, college, chat, college, chat, college, chat, row[1]);
            Long left = db.queryForObject("SELECT count(*) FROM public.skill_group_members WHERE college_id = ? AND group_id = ? AND left_at IS NULL",
                    Long.class, college, group);
            if (left != null && left == 0) close(college, group);
            publish(college, group);
        }
    }

    /** Closes a group: scheduled group sessions are cancelled and the group chat is archived (still readable). */
    void close(long college, long group) {
        db.update("UPDATE public.skill_groups SET status = 'CLOSED', closed_at = statement_timestamp() WHERE college_id = ? AND id = ? AND status = 'ACTIVE'",
                college, group);
        db.update("""
                UPDATE public.skill_group_sessions SET status = 'CANCELLED', cancelled_at = statement_timestamp(),
                    cancel_reason = coalesce(cancel_reason, 'The group closed') WHERE college_id = ? AND group_id = ? AND status = 'SCHEDULED'
                """, college, group);
        Long chat = chat(college, group);
        if (chat != null) db.update("UPDATE public.chat_conversations SET archived_at = statement_timestamp() WHERE college_id = ? AND id = ? AND archived_at IS NULL",
                college, chat);
    }

    private Long chat(long college, long group) {
        return db.query("SELECT conversation_id FROM public.skill_groups WHERE college_id = ? AND id = ?",
                (r, i) -> (Long) r.getObject(1, Long.class), college, group).stream().findFirst().orElse(null);
    }

    private void publish(long college, long group) {
        Long chat = chat(college, group);
        if (chat == null || stream == null) return;
        List<Long> members = db.queryForList("""
                SELECT student_id FROM public.chat_members WHERE college_id = ? AND conversation_id = ? AND left_at IS NULL
                """, Long.class, college, chat);
        Assignments.afterCommit(committed -> { if (committed) stream.publish(members, "members", chat); });
    }
}
