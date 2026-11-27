package com.campussphere.backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.StudyMaterials.FileDownload;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Student-only groups and chat. Every operation requires the STUDENT role and a student profile in the signed-in
 * account's college; there is deliberately no faculty, College Admin or platform route into conversations,
 * messages or reports. Class groups follow the student's current enrollment automatically. Study groups are made
 * by students (open to any student of the college, or invite-only). Private chats are between two students of the
 * same college unless either has blocked the other.
 */
@Service
@Transactional
public class StudentChats {
    static final int PAGE = 50;
    static final int MAX_GROUP_MEMBERS = 100;
    static final int MAX_OWNED_GROUPS = 20;
    static final Set<String> REASONS = Set.of("SPAM", "ABUSE", "HARASSMENT", "INAPPROPRIATE", "OTHER");

    // ---- Shapes ----

    public record Me(long id, String name, String email, String college, String collegeCode, String program,
                     String academicYear, String semester, String division) {}
    public record Conversation(long id, String kind, String name, String description, String joinPolicy,
                               boolean archived, String myRole, long members, long unread, Long otherStudentId,
                               String lastMessage, String lastSender, OffsetDateTime lastMessageAt, boolean blocked,
                               boolean canPost) {}
    public record Message(long id, long conversationId, long senderId, String sender, boolean mine, String body,
                          String attachmentName, String attachmentFormat, Long attachmentSize,
                          OffsetDateTime createdAt, boolean deleted, long readBy) {}
    public record Member(long studentId, String name, String role, boolean me) {}
    public record Person(long studentId, String name, String classLabel) {}
    public record OpenGroup(long id, String name, String description, long members) {}
    public record GroupInput(String name, String description, String joinPolicy) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported group field"); }
    }
    public record MessageInput(String body, UUID clientKey) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported message field"); }
    }
    public record ReportInput(String reason, String note, Boolean block) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported report field"); }
    }
    public record StudentRef(Long studentId) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    public record ReadInput(Long messageId) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }

    /** A conversation the actor is an active member of. */
    record Access(long college, long id, String kind, String role, boolean archived, Long other, boolean blocked) {
        boolean canPost() { return !archived && !blocked; }
    }

    private final JdbcTemplate db;
    private final MaterialFiles files;
    private final ChatStream stream;

    public StudentChats(JdbcTemplate db, MaterialFiles files, ChatStream stream) {
        this.db = db;
        this.files = files;
        this.stream = stream;
    }

    // ---- Rules ----

    static GroupInput cleanGroup(GroupInput in) {
        if (in == null) throw ApiError.CHAT_INVALID.exception();
        String name = in.name() == null ? "" : in.name().strip();
        String description = in.description() == null ? "" : in.description().strip();
        String policy = in.joinPolicy() == null ? "INVITE" : in.joinPolicy();
        if (name.length() < 3 || name.length() > 80 || description.length() > 500
                || !(policy.equals("OPEN") || policy.equals("INVITE"))) throw ApiError.CHAT_INVALID.exception();
        return new GroupInput(name, description, policy);
    }

    static String cleanBody(String body, boolean fileAttached) {
        String text = body == null ? "" : body.strip();
        if (text.length() > 4000 || (text.isEmpty() && !fileAttached)) throw ApiError.CHAT_INVALID.exception();
        return text;
    }

    /** The unique key of a private chat: the two student ids, smaller first. */
    static String directKey(long a, long b) {
        return Math.min(a, b) + ":" + Math.max(a, b);
    }

    // ---- Profile ----

    public Me me(Account actor) {
        long college = actor.requireCollege(CollegeRole.STUDENT, null);
        return db.query("""
                SELECT u.id, u.full_name, u.email, c.name, c.code, p.name, y.name, sem.name, d.name
                FROM public.users u JOIN public.colleges c ON c.id = u.college_id
                LEFT JOIN public.student_enrollments e ON e.college_id = u.college_id AND e.student_id = u.id AND e.ended_at IS NULL
                LEFT JOIN public.academic_programs p ON p.college_id = e.college_id AND p.id = e.program_id
                LEFT JOIN public.academic_years y ON y.college_id = e.college_id AND y.id = e.academic_year_id
                LEFT JOIN public.academic_semesters sem ON sem.college_id = e.college_id AND sem.id = e.semester_id
                LEFT JOIN public.academic_divisions d ON d.college_id = e.college_id AND d.id = e.division_id
                WHERE u.college_id = ? AND u.id = ?
                """, (r, i) -> new Me(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                r.getString(6), r.getString(7), r.getString(8), r.getString(9)), college, actor.id()).stream()
                .findFirst().orElseThrow(ApiError.ACCESS_DENIED::exception);
    }

    // ---- Membership ----

    /** The actor's college, after checking they have a student profile there. */
    long college(Account actor) {
        long college = actor.requireCollege(CollegeRole.STUDENT, null);
        if (!Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.student_profiles WHERE college_id = ? AND student_id = ?)
                """, Boolean.class, college, actor.id()))) throw ApiError.CHAT_NOT_ALLOWED.exception();
        return college;
    }

    /** Puts the student in their current class's group (creating it once) and out of any former class group. */
    long syncClass(Account actor) {
        long college = college(actor);
        var current = db.query("""
                SELECT e.program_id, e.academic_year_id, e.division_id, p.name, d.name, y.name
                FROM public.student_enrollments e
                JOIN public.academic_programs p ON p.college_id = e.college_id AND p.id = e.program_id
                JOIN public.academic_divisions d ON d.college_id = e.college_id AND d.id = e.division_id
                JOIN public.academic_years y ON y.college_id = e.college_id AND y.id = e.academic_year_id
                WHERE e.college_id = ? AND e.student_id = ? AND e.ended_at IS NULL
                """, (r, i) -> new Object[]{r.getLong(1), r.getLong(2), r.getLong(3),
                r.getString(4) + " · " + r.getString(5) + " (" + r.getString(6) + ")"}, college, actor.id());
        Long conversation = null;
        if (!current.isEmpty()) {
            Object[] c = current.get(0);
            String name = (String) c[3];
            db.update("""
                    INSERT INTO public.chat_conversations (college_id, kind, name, program_id, academic_year_id, division_id)
                    VALUES (?, 'CLASS', ?, ?, ?, ?) ON CONFLICT (college_id, division_id) WHERE kind = 'CLASS' DO NOTHING
                    """, college, name.length() > 80 ? name.substring(0, 80) : name, c[0], c[1], c[2]);
            conversation = db.queryForObject("""
                    SELECT id FROM public.chat_conversations WHERE college_id = ? AND kind = 'CLASS' AND division_id = ?
                    """, Long.class, college, c[2]);
            // The whole class, not only this student: everyone currently enrolled in the division is a member,
            // and anyone no longer enrolled in it leaves, so member lists and unread counts are right for all.
            db.update("""
                    INSERT INTO public.chat_members (college_id, conversation_id, student_id)
                    SELECT e.college_id, ?, e.student_id FROM public.student_enrollments e
                    WHERE e.college_id = ? AND e.division_id = ? AND e.ended_at IS NULL
                    ON CONFLICT (conversation_id, student_id) DO UPDATE SET left_at = NULL, joined_at = statement_timestamp()
                    WHERE chat_members.left_at IS NOT NULL
                    """, conversation, college, c[2]);
            db.update("""
                    UPDATE public.chat_members m SET left_at = statement_timestamp()
                    WHERE m.conversation_id = ? AND m.left_at IS NULL AND NOT EXISTS (
                        SELECT 1 FROM public.student_enrollments e WHERE e.college_id = m.college_id
                          AND e.student_id = m.student_id AND e.division_id = ? AND e.ended_at IS NULL)
                    """, conversation, c[2]);
        }
        db.update("""
                UPDATE public.chat_members m SET left_at = statement_timestamp()
                FROM public.chat_conversations c
                WHERE c.college_id = m.college_id AND c.id = m.conversation_id AND c.kind = 'CLASS'
                  AND m.college_id = ? AND m.student_id = ? AND m.left_at IS NULL AND m.conversation_id IS DISTINCT FROM ?
                """, college, actor.id(), conversation);
        return college;
    }

    /** The conversation, if the actor is an active member; otherwise CHAT_NOT_FOUND. */
    Access access(Account actor, long id) {
        long college = syncClass(actor);
        return db.query("""
                SELECT c.kind, me.role, c.archived_at IS NOT NULL,
                       (SELECT o.student_id FROM public.chat_members o
                        WHERE c.kind = 'DIRECT' AND o.conversation_id = c.id AND o.student_id <> me.student_id LIMIT 1)
                FROM public.chat_conversations c
                JOIN public.chat_members me ON me.conversation_id = c.id AND me.student_id = ? AND me.left_at IS NULL
                WHERE c.college_id = ? AND c.id = ?
                """, (r, i) -> {
            Long other = (Long) r.getObject(4, Long.class);
            return new Access(college, id, r.getString(1), r.getString(2), r.getBoolean(3), other,
                    other != null && blocked(actor.id(), other));
        }, actor.id(), college, id).stream().findFirst().orElseThrow(ApiError.CHAT_NOT_FOUND::exception);
    }

    private boolean blocked(long a, long b) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.chat_blocks
                               WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?))
                """, Boolean.class, a, b, b, a));
    }

    /** Another active student of the same college with a student profile. */
    private void requireStudent(long college, long student) {
        if (!Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.student_profiles s JOIN public.users u ON u.id = s.student_id
                               WHERE s.college_id = ? AND s.student_id = ? AND u.is_active IS TRUE)
                """, Boolean.class, college, student))) throw ApiError.CHAT_NOT_FOUND.exception();
    }

    // ---- Conversations ----

    private static final String CONVERSATIONS = """
            SELECT c.id, c.kind, CASE WHEN c.kind = 'DIRECT' THEN ou.full_name ELSE c.name END name, c.description,
                   c.join_policy, c.archived_at IS NOT NULL archived, me.role,
                   (SELECT count(*) FROM public.chat_members k WHERE k.conversation_id = c.id AND k.left_at IS NULL) members,
                   (SELECT count(*) FROM public.chat_messages x
                    WHERE x.college_id = c.college_id AND x.conversation_id = c.id AND x.id > me.last_read_message_id
                      AND x.sender_id <> me.student_id AND x.deleted_at IS NULL
                      AND NOT EXISTS (SELECT 1 FROM public.chat_blocks b WHERE b.blocker_id = me.student_id AND b.blocked_id = x.sender_id)
                      AND NOT EXISTS (SELECT 1 FROM public.chat_reports rp WHERE rp.reporter_id = me.student_id AND rp.message_id = x.id)) unread,
                   other.student_id other_id, l.body last_body, l.attachment_name last_file, l.deleted_at last_deleted,
                   l.sender last_sender, l.created_at last_at,
                   (other.student_id IS NOT NULL AND EXISTS (SELECT 1 FROM public.chat_blocks b
                        WHERE (b.blocker_id = me.student_id AND b.blocked_id = other.student_id)
                           OR (b.blocker_id = other.student_id AND b.blocked_id = me.student_id))) blocked
            FROM public.chat_conversations c
            JOIN public.chat_members me ON me.conversation_id = c.id AND me.student_id = ? AND me.left_at IS NULL
            LEFT JOIN public.chat_members other ON c.kind = 'DIRECT' AND other.conversation_id = c.id AND other.student_id <> me.student_id
            LEFT JOIN public.users ou ON ou.id = other.student_id
            LEFT JOIN LATERAL (
                SELECT x.body, x.attachment_name, x.deleted_at, su.full_name sender, x.created_at
                FROM public.chat_messages x JOIN public.users su ON su.id = x.sender_id
                WHERE x.college_id = c.college_id AND x.conversation_id = c.id
                  AND NOT EXISTS (SELECT 1 FROM public.chat_blocks b WHERE b.blocker_id = me.student_id AND b.blocked_id = x.sender_id)
                  AND NOT EXISTS (SELECT 1 FROM public.chat_reports rp WHERE rp.reporter_id = me.student_id AND rp.message_id = x.id)
                ORDER BY x.id DESC LIMIT 1) l ON true
            WHERE c.college_id = ?""";

    private static Conversation conversation(ResultSet r, int row) throws SQLException {
        boolean archived = r.getBoolean("archived");
        boolean blocked = r.getBoolean("blocked");
        String last = r.getObject("last_deleted") != null ? "Message deleted"
                : r.getString("last_file") != null && (r.getString("last_body") == null || r.getString("last_body").isEmpty())
                ? "📎 " + r.getString("last_file") : r.getString("last_body");
        return new Conversation(r.getLong("id"), r.getString("kind"), r.getString("name"), r.getString("description"),
                r.getString("join_policy"), archived, r.getString("role"), r.getLong("members"), r.getLong("unread"),
                (Long) r.getObject("other_id", Long.class), last, r.getString("last_sender"),
                r.getObject("last_at", OffsetDateTime.class), blocked, !archived && !blocked);
    }

    public List<Conversation> conversations(Account actor) {
        long college = syncClass(actor);
        return db.query(CONVERSATIONS + """

                 ORDER BY (c.kind = 'CLASS') DESC, coalesce(c.last_message_at, c.created_at) DESC, c.id DESC LIMIT 200
                """, StudentChats::conversation, actor.id(), college);
    }

    public Conversation conversation(Account actor, long id) {
        long college = syncClass(actor);
        return db.query(CONVERSATIONS + " AND c.id = ?", StudentChats::conversation, actor.id(), college, id)
                .stream().findFirst().orElseThrow(ApiError.CHAT_NOT_FOUND::exception);
    }

    public long unread(Account actor) {
        return conversations(actor).stream().mapToLong(Conversation::unread).sum();
    }

    /** Other active students of the college, by name. */
    public List<Person> people(Account actor, String q) {
        long college = college(actor);
        String query = q == null ? "" : q.strip();
        if (query.length() > 60) throw ApiError.CHAT_INVALID.exception();
        return db.query("""
                SELECT u.id, u.full_name, CASE WHEN d.id IS NULL THEN NULL ELSE p.name || ' · ' || d.name END
                FROM public.student_profiles s JOIN public.users u ON u.id = s.student_id AND u.is_active IS TRUE
                LEFT JOIN public.student_enrollments e ON e.college_id = s.college_id AND e.student_id = s.student_id AND e.ended_at IS NULL
                LEFT JOIN public.academic_programs p ON p.college_id = e.college_id AND p.id = e.program_id
                LEFT JOIN public.academic_divisions d ON d.college_id = e.college_id AND d.id = e.division_id
                WHERE s.college_id = ? AND s.student_id <> ? AND strpos(lower(u.full_name), lower(?)) > 0
                ORDER BY u.full_name, u.id LIMIT 30
                """, (r, i) -> new Person(r.getLong(1), r.getString(2), r.getString(3)), college, actor.id(), query);
    }

    /** Opens (or reopens) the private chat with another student. */
    public Conversation direct(Account actor, StudentRef ref) {
        long college = college(actor);
        if (ref == null || ref.studentId() == null || Objects.equals(ref.studentId(), actor.id())) throw ApiError.CHAT_INVALID.exception();
        long other = ref.studentId();
        requireStudent(college, other);
        if (blocked(actor.id(), other)) throw ApiError.CHAT_BLOCKED.exception();
        String key = directKey(actor.id(), other);
        db.update("""
                INSERT INTO public.chat_conversations (college_id, kind, direct_key, created_by) VALUES (?, 'DIRECT', ?, ?)
                ON CONFLICT (college_id, direct_key) WHERE kind = 'DIRECT' DO NOTHING
                """, college, key, actor.id());
        Long id = db.queryForObject("SELECT id FROM public.chat_conversations WHERE college_id = ? AND kind = 'DIRECT' AND direct_key = ?",
                Long.class, college, key);
        for (long student : new long[]{actor.id(), other}) {
            db.update("""
                    INSERT INTO public.chat_members (college_id, conversation_id, student_id) VALUES (?, ?, ?)
                    ON CONFLICT (conversation_id, student_id) DO NOTHING
                    """, college, id, student);
        }
        return conversation(actor, id);
    }

    // ---- Study groups ----

    public Conversation createGroup(Account actor, GroupInput input) {
        long college = college(actor);
        GroupInput group = cleanGroup(input);
        Long owned = db.queryForObject("""
                SELECT count(*) FROM public.chat_conversations c JOIN public.chat_members m ON m.conversation_id = c.id
                WHERE c.college_id = ? AND c.kind = 'GROUP' AND c.archived_at IS NULL AND m.student_id = ?
                  AND m.role = 'OWNER' AND m.left_at IS NULL
                """, Long.class, college, actor.id());
        if (owned != null && owned >= MAX_OWNED_GROUPS) throw ApiError.CHAT_LIMIT.exception();
        Long id = db.queryForObject("""
                INSERT INTO public.chat_conversations (college_id, kind, name, description, join_policy, created_by)
                VALUES (?, 'GROUP', ?, ?, ?, ?) RETURNING id
                """, Long.class, college, group.name(), group.description(), group.joinPolicy(), actor.id());
        db.update("INSERT INTO public.chat_members (college_id, conversation_id, student_id, role) VALUES (?, ?, ?, 'OWNER')",
                college, id, actor.id());
        return conversation(actor, id);
    }

    private Access ownedGroup(Account actor, long id) {
        Access access = access(actor, id);
        if (!access.kind().equals("GROUP") || !"OWNER".equals(access.role())) throw ApiError.CHAT_NOT_ALLOWED.exception();
        if (access.archived()) throw ApiError.CHAT_STATE_CONFLICT.exception();
        return access;
    }

    public Conversation updateGroup(Account actor, long id, GroupInput input) {
        Access access = ownedGroup(actor, id);
        GroupInput group = cleanGroup(input);
        db.update("UPDATE public.chat_conversations SET name = ?, description = ?, join_policy = ? WHERE college_id = ? AND id = ?",
                group.name(), group.description(), group.joinPolicy(), access.college(), id);
        return conversation(actor, id);
    }

    public Conversation archiveGroup(Account actor, long id) {
        Access access = ownedGroup(actor, id);
        db.update("UPDATE public.chat_conversations SET archived_at = statement_timestamp() WHERE college_id = ? AND id = ?",
                access.college(), id);
        return conversation(actor, id);
    }

    /** Open study groups of the college that the actor is not in. */
    public List<OpenGroup> openGroups(Account actor, String q) {
        long college = college(actor);
        String query = q == null ? "" : q.strip();
        if (query.length() > 60) throw ApiError.CHAT_INVALID.exception();
        return db.query("""
                SELECT c.id, c.name, c.description,
                       (SELECT count(*) FROM public.chat_members k WHERE k.conversation_id = c.id AND k.left_at IS NULL)
                FROM public.chat_conversations c
                WHERE c.college_id = ? AND c.kind = 'GROUP' AND c.join_policy = 'OPEN' AND c.archived_at IS NULL
                  AND strpos(lower(c.name), lower(?)) > 0
                  AND NOT EXISTS (SELECT 1 FROM public.chat_members m WHERE m.conversation_id = c.id AND m.student_id = ? AND m.left_at IS NULL)
                ORDER BY c.name, c.id LIMIT 50
                """, (r, i) -> new OpenGroup(r.getLong(1), r.getString(2), r.getString(3), r.getLong(4)), college, query, actor.id());
    }

    public Conversation join(Account actor, long id) {
        long college = college(actor);
        Boolean open = db.query("""
                SELECT c.join_policy = 'OPEN' AND c.archived_at IS NULL FROM public.chat_conversations c
                WHERE c.college_id = ? AND c.id = ? AND c.kind = 'GROUP' FOR UPDATE
                """, (r, i) -> r.getBoolean(1), college, id).stream().findFirst().orElseThrow(ApiError.CHAT_NOT_FOUND::exception);
        if (!open) throw ApiError.CHAT_NOT_ALLOWED.exception();
        addMember(college, id, actor.id());
        Conversation joined = conversation(actor, id);
        publish(college, id, "members");
        return joined;
    }

    public void leave(Account actor, long id) {
        Access access = access(actor, id);
        if (!access.kind().equals("GROUP")) throw ApiError.CHAT_NOT_ALLOWED.exception();
        db.update("""
                UPDATE public.chat_members SET left_at = statement_timestamp(), role = 'MEMBER'
                WHERE college_id = ? AND conversation_id = ? AND student_id = ?
                """, access.college(), id, actor.id());
        if ("OWNER".equals(access.role())) {
            // The longest-standing member becomes the owner; a group nobody is left in is archived.
            int promoted = db.update("""
                    UPDATE public.chat_members SET role = 'OWNER'
                    WHERE conversation_id = ? AND student_id = (SELECT student_id FROM public.chat_members
                        WHERE conversation_id = ? AND left_at IS NULL ORDER BY joined_at, student_id LIMIT 1)
                    """, id, id);
            if (promoted == 0) db.update("""
                    UPDATE public.chat_conversations SET archived_at = statement_timestamp()
                    WHERE college_id = ? AND id = ? AND archived_at IS NULL
                    """, access.college(), id);
        }
        publish(access.college(), id, "members");
    }

    public List<Member> members(Account actor, long id) {
        Access access = access(actor, id);
        return db.query("""
                SELECT m.student_id, u.full_name, m.role FROM public.chat_members m JOIN public.users u ON u.id = m.student_id
                WHERE m.college_id = ? AND m.conversation_id = ? AND m.left_at IS NULL
                ORDER BY (m.role = 'OWNER') DESC, u.full_name, m.student_id
                """, (r, i) -> new Member(r.getLong(1), r.getString(2), r.getString(3), r.getLong(1) == actor.id()),
                access.college(), id);
    }

    public List<Member> addMember(Account actor, long id, StudentRef ref) {
        Access access = ownedGroup(actor, id);
        if (ref == null || ref.studentId() == null || Objects.equals(ref.studentId(), actor.id())) throw ApiError.CHAT_INVALID.exception();
        requireStudent(access.college(), ref.studentId());
        if (blocked(actor.id(), ref.studentId())) throw ApiError.CHAT_BLOCKED.exception();
        addMember(access.college(), id, ref.studentId());
        publish(access.college(), id, "members");
        return members(actor, id);
    }

    private void addMember(long college, long id, long student) {
        Long size = db.queryForObject("SELECT count(*) FROM public.chat_members WHERE conversation_id = ? AND left_at IS NULL",
                Long.class, id);
        if (size != null && size >= MAX_GROUP_MEMBERS) throw ApiError.CHAT_LIMIT.exception();
        db.update("""
                INSERT INTO public.chat_members (college_id, conversation_id, student_id) VALUES (?, ?, ?)
                ON CONFLICT (conversation_id, student_id) DO UPDATE SET left_at = NULL, role = 'MEMBER',
                    joined_at = statement_timestamp() WHERE chat_members.left_at IS NOT NULL
                """, college, id, student);
    }

    public List<Member> removeMember(Account actor, long id, long student) {
        Access access = ownedGroup(actor, id);
        if (student == actor.id()) throw ApiError.CHAT_INVALID.exception();
        int removed = db.update("""
                UPDATE public.chat_members SET left_at = statement_timestamp()
                WHERE college_id = ? AND conversation_id = ? AND student_id = ? AND left_at IS NULL
                """, access.college(), id, student);
        if (removed == 0) throw ApiError.CHAT_NOT_FOUND.exception();
        publish(access.college(), id, "members");
        return members(actor, id);
    }

    // ---- Messages ----

    private static final String MESSAGES = """
            SELECT m.id, m.conversation_id, m.sender_id, u.full_name, m.body, m.attachment_name, m.attachment_format,
                   m.attachment_size, m.created_at, m.deleted_at IS NOT NULL deleted,
                   (SELECT count(*) FROM public.chat_members r WHERE r.conversation_id = m.conversation_id
                        AND r.student_id <> m.sender_id AND r.left_at IS NULL AND r.last_read_message_id >= m.id) read_by
            FROM public.chat_messages m JOIN public.users u ON u.id = m.sender_id
            WHERE m.college_id = ? AND m.conversation_id = ?
              AND NOT EXISTS (SELECT 1 FROM public.chat_blocks b WHERE b.blocker_id = ? AND b.blocked_id = m.sender_id)
              AND NOT EXISTS (SELECT 1 FROM public.chat_reports rp WHERE rp.reporter_id = ? AND rp.message_id = m.id)""";

    private Message message(ResultSet r, long me) throws SQLException {
        Number size = (Number) r.getObject("attachment_size");
        return new Message(r.getLong("id"), r.getLong("conversation_id"), r.getLong("sender_id"), r.getString("full_name"),
                r.getLong("sender_id") == me, r.getString("body"), r.getString("attachment_name"),
                r.getString("attachment_format"), size == null ? null : size.longValue(),
                r.getObject("created_at", OffsetDateTime.class), r.getBoolean("deleted"), r.getLong("read_by"));
    }

    /** Newest first; pass the oldest id you have as {@code before} for the page before it. */
    public List<Message> messages(Account actor, long id, Long before) {
        Access access = access(actor, id);
        if (before != null && before <= 0) throw ApiError.CHAT_INVALID.exception();
        String page = (before == null ? "" : " AND m.id < " + before) + " ORDER BY m.id DESC LIMIT " + PAGE;
        return db.query(MESSAGES + page, (r, i) -> message(r, actor.id()), access.college(), id, actor.id(), actor.id());
    }

    private Message one(Access access, Account actor, long messageId) {
        return db.query(MESSAGES + " AND m.id = ?", (r, i) -> message(r, actor.id()), access.college(), access.id(),
                actor.id(), actor.id(), messageId).stream().findFirst().orElseThrow(ApiError.CHAT_NOT_FOUND::exception);
    }

    private Access postable(Account actor, long id) {
        Access access = access(actor, id);
        if (access.blocked()) throw ApiError.CHAT_BLOCKED.exception();
        if (access.archived()) throw ApiError.CHAT_STATE_CONFLICT.exception();
        return access;
    }

    /** Sends a text message. Sending again with the same clientKey (a retry) returns the first message. */
    public Message send(Account actor, long id, MessageInput input) {
        if (input == null || input.clientKey() == null) throw ApiError.CHAT_INVALID.exception();
        String body = cleanBody(input.body(), false);
        Access access = postable(actor, id);
        Long existing = existing(access, actor, input.clientKey());
        if (existing != null) return one(access, actor, existing);
        Long created = db.queryForObject("""
                INSERT INTO public.chat_messages (college_id, conversation_id, sender_id, client_key, body)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, access.college(), id, actor.id(), input.clientKey(), body);
        return sent(access, actor, created == null ? 0 : created);
    }

    /** Sends one file with an optional caption. */
    public Message sendFile(Account actor, long id, MultipartFile file, String caption, UUID clientKey) throws IOException {
        if (clientKey == null) throw ApiError.CHAT_INVALID.exception();
        String body = cleanBody(caption, true);
        Access access = postable(actor, id);
        Long existing = existing(access, actor, clientKey);
        if (existing != null) return one(access, actor, existing);
        MaterialFiles.Upload upload;
        try {
            upload = MaterialFiles.inspect(file, EnumSet.allOf(MaterialFiles.Format.class), MaterialFiles.LIMIT);
        } catch (ApiError.Failure failure) {
            throw failure.error() == ApiError.MATERIAL_FILE_INVALID ? ApiError.CHAT_FILE_INVALID.exception() : failure;
        }
        UUID key = UUID.randomUUID();
        Long created = db.queryForObject("""
                INSERT INTO public.chat_messages (college_id, conversation_id, sender_id, client_key, body, attachment_name,
                    attachment_key, attachment_format, attachment_mime, attachment_size, attachment_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, access.college(), id, actor.id(), clientKey, body, upload.fileName(), key,
                upload.extension(), upload.mime(), upload.bytes().length, upload.sha256());
        Assignments.afterCommit(committed -> { if (!committed) files.delete(key); });
        files.save(key, upload.bytes());
        return sent(access, actor, created == null ? 0 : created);
    }

    private Long existing(Access access, Account actor, UUID clientKey) {
        var found = db.query("SELECT id, conversation_id FROM public.chat_messages WHERE sender_id = ? AND client_key = ?",
                (r, i) -> new long[]{r.getLong(1), r.getLong(2)}, actor.id(), clientKey);
        if (found.isEmpty()) return null;
        if (found.get(0)[1] != access.id()) throw ApiError.CHAT_INVALID.exception();
        return found.get(0)[0];
    }

    private Message sent(Access access, Account actor, long messageId) {
        db.update("UPDATE public.chat_conversations SET last_message_at = statement_timestamp() WHERE college_id = ? AND id = ?",
                access.college(), access.id());
        db.update("""
                UPDATE public.chat_members SET last_read_message_id = GREATEST(last_read_message_id, ?)
                WHERE conversation_id = ? AND student_id = ?
                """, messageId, access.id(), actor.id());
        publish(access.college(), access.id(), "message");
        return one(access, actor, messageId);
    }

    /** Deletes the actor's own message for everyone: its text and file are removed, a placeholder stays. */
    public Message delete(Account actor, long id, long messageId) {
        Access access = access(actor, id);
        var key = db.query("""
                SELECT attachment_key FROM public.chat_messages
                WHERE college_id = ? AND conversation_id = ? AND id = ? AND sender_id = ? AND deleted_at IS NULL FOR UPDATE
                """, (r, i) -> Optional.ofNullable(r.getObject(1, UUID.class)), access.college(), id, messageId, actor.id())
                .stream().findFirst().orElseThrow(ApiError.CHAT_NOT_FOUND::exception);
        db.update("""
                UPDATE public.chat_messages SET body = '', attachment_name = NULL, attachment_key = NULL, attachment_format = NULL,
                    attachment_mime = NULL, attachment_size = NULL, attachment_sha256 = NULL, deleted_at = statement_timestamp()
                WHERE college_id = ? AND id = ?
                """, access.college(), messageId);
        key.ifPresent(k -> Assignments.afterCommit(committed -> { if (committed) files.delete(k); }));
        publish(access.college(), id, "message");
        return one(access, actor, messageId);
    }

    /** Marks messages up to {@code messageId} as read (never moves backwards). */
    public void read(Account actor, long id, ReadInput input) {
        if (input == null || input.messageId() == null || input.messageId() < 0) throw ApiError.CHAT_INVALID.exception();
        Access access = access(actor, id);
        int changed = db.update("""
                UPDATE public.chat_members SET last_read_message_id = LEAST(?, (SELECT coalesce(max(id), 0)
                    FROM public.chat_messages WHERE college_id = ? AND conversation_id = ?))
                WHERE conversation_id = ? AND student_id = ? AND last_read_message_id < ?
                """, input.messageId(), access.college(), id, id, actor.id(), input.messageId());
        if (changed > 0) publish(access.college(), id, "read");
    }

    public FileDownload file(Account actor, long id, long messageId) {
        Access access = access(actor, id);
        Message visible = one(access, actor, messageId);
        if (visible.deleted() || visible.attachmentName() == null) throw ApiError.CHAT_NOT_FOUND.exception();
        var row = db.query("SELECT attachment_key, attachment_mime FROM public.chat_messages WHERE college_id = ? AND id = ?",
                (r, i) -> new Object[]{r.getObject(1, UUID.class), r.getString(2)}, access.college(), messageId).get(0);
        Path path = files.path((UUID) row[0]);
        if (!Files.isRegularFile(path)) throw ApiError.CHAT_NOT_FOUND.exception();
        return new FileDownload(path, visible.attachmentName(), (String) row[1], visible.attachmentSize());
    }

    /** Reports a message: it is hidden for the reporter from then on, and its sender can be blocked at once. */
    public void report(Account actor, long id, long messageId, ReportInput input) {
        if (input == null || input.reason() == null || !REASONS.contains(input.reason())) throw ApiError.CHAT_INVALID.exception();
        String note = input.note() == null || input.note().isBlank() ? null : input.note().strip();
        if (note != null && note.length() > 500) throw ApiError.CHAT_INVALID.exception();
        Access access = access(actor, id);
        Message reported = one(access, actor, messageId);
        if (reported.mine()) throw ApiError.CHAT_INVALID.exception();
        db.update("""
                INSERT INTO public.chat_reports (college_id, message_id, reporter_id, reason, note) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT uq_chat_report_once DO NOTHING
                """, access.college(), messageId, actor.id(), input.reason(), note);
        if (Boolean.TRUE.equals(input.block())) block(actor, new StudentRef(reported.senderId()));
    }

    // ---- Blocking ----

    public List<Person> blocked(Account actor) {
        long college = college(actor);
        return db.query("""
                SELECT u.id, u.full_name, NULL FROM public.chat_blocks b JOIN public.users u ON u.id = b.blocked_id
                WHERE b.college_id = ? AND b.blocker_id = ? ORDER BY u.full_name, u.id
                """, (r, i) -> new Person(r.getLong(1), r.getString(2), r.getString(3)), college, actor.id());
    }

    public void block(Account actor, StudentRef ref) {
        long college = college(actor);
        if (ref == null || ref.studentId() == null || Objects.equals(ref.studentId(), actor.id())) throw ApiError.CHAT_INVALID.exception();
        requireStudent(college, ref.studentId());
        db.update("""
                INSERT INTO public.chat_blocks (college_id, blocker_id, blocked_id) VALUES (?, ?, ?)
                ON CONFLICT (blocker_id, blocked_id) DO NOTHING
                """, college, actor.id(), ref.studentId());
    }

    public void unblock(Account actor, long student) {
        long college = college(actor);
        db.update("DELETE FROM public.chat_blocks WHERE college_id = ? AND blocker_id = ? AND blocked_id = ?",
                college, actor.id(), student);
    }

    // ---- Live updates ----

    /** After the transaction commits, tells the conversation's current members that it changed. */
    private void publish(long college, long id, String type) {
        List<Long> members = db.queryForList("""
                SELECT student_id FROM public.chat_members WHERE college_id = ? AND conversation_id = ? AND left_at IS NULL
                """, Long.class, college, id);
        Assignments.afterCommit(committed -> { if (committed) stream.publish(members, type, id); });
    }

    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter connect(Account actor) {
        college(actor);
        return stream.connect(actor.id());
    }
}
