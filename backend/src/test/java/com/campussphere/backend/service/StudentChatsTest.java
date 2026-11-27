package com.campussphere.backend.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.StudentChats.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Rules and access of student groups and chat, against the SQL the service runs (no database). */
class StudentChatsTest {
    private static final long COLLEGE = 3L, ME = 7L;
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @TempDir Path storage;
    private final ScriptedJdbc db = new ScriptedJdbc();
    private final ChatStream stream = mock(ChatStream.class);
    private StudentChats chats;

    @BeforeEach void setUp() {
        chats = new StudentChats(db, new MaterialFiles(storage.toString()), stream);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static Account account(String... roles) {
        return new Account(ME, true, false, Set.of(roles), new CollegeScope(COLLEGE, true));
    }

    interface Action { void run() throws Exception; }

    private static void assertError(ApiError expected, Action action) {
        var failure = assertThrows(ApiError.Failure.class, action::run);
        assertEquals(expected, failure.error());
    }

    /** The student has a profile, no current class, and is a member of conversation 9 of the given kind. */
    private void member(String kind, String role, boolean archived, Long other, boolean blocked) {
        db.on("FROM public.student_profiles WHERE college_id = ? AND student_id = ?", true)
                .on("SELECT c.kind, me.role, c.archived_at IS NOT NULL", List.of(new Access(COLLEGE, 9, kind, role, archived, other, blocked)));
    }

    private static Message message(long id, long sender) {
        return new Message(id, 9, sender, "Asha", sender == ME, "Hi", null, null, null, null, false, 0);
    }

    private void commit() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
    }

    @Test void groupsAndMessagesAreValidated() {
        assertEquals(new GroupInput("Java revision", "Unit 3", "INVITE"),
                StudentChats.cleanGroup(new GroupInput("  Java revision ", " Unit 3 ", null)));
        for (GroupInput bad : List.of(new GroupInput("ab", "", "OPEN"), new GroupInput("x".repeat(81), "", "OPEN"),
                new GroupInput("Group", "d".repeat(501), "OPEN"), new GroupInput("Group", "", "SECRET"))) {
            assertError(ApiError.CHAT_INVALID, () -> StudentChats.cleanGroup(bad));
        }
        assertError(ApiError.CHAT_INVALID, () -> StudentChats.cleanGroup(null));
        assertEquals("Hi", StudentChats.cleanBody(" Hi ", false));
        assertEquals("", StudentChats.cleanBody("  ", true));
        assertError(ApiError.CHAT_INVALID, () -> StudentChats.cleanBody("   ", false));
        assertError(ApiError.CHAT_INVALID, () -> StudentChats.cleanBody("x".repeat(4001), false));
        assertEquals("4:12", StudentChats.directKey(12, 4));
        assertEquals(StudentChats.directKey(4, 12), StudentChats.directKey(12, 4));
    }

    @Test void onlyStudentsWithAProfileInTheirCollegeCanUseChat() {
        for (Account outsider : List.of(account("FACULTY"), account("COLLEGE_ADMIN"), account("SUPER_ADMIN"), account("ADMIN"),
                new Account(ME, true, false, Set.of("STUDENT"), null),
                new Account(ME, true, false, Set.of("STUDENT"), new CollegeScope(COLLEGE, false)))) {
            assertError(ApiError.ACCESS_DENIED, () -> chats.conversations(outsider));
            assertError(ApiError.ACCESS_DENIED, () -> chats.messages(outsider, 9, null));
            assertError(ApiError.ACCESS_DENIED, () -> chats.connect(outsider));
        }
        assertTrue(db.calls.isEmpty(), "refused before any query");
        db.on("FROM public.student_profiles WHERE college_id = ? AND student_id = ?", false);
        assertError(ApiError.CHAT_NOT_ALLOWED, () -> chats.conversations(account("STUDENT")));
        assertEquals(List.of(COLLEGE, ME), db.calls.get(0).args());
    }

    @Test void conversationsAreOnlyThoseTheStudentIsAnActiveMemberOf() {
        db.on("FROM public.student_profiles WHERE college_id = ? AND student_id = ?", true);
        chats.conversations(account("STUDENT"));
        var list = db.calls("CASE WHEN c.kind = 'DIRECT'").get(0);
        assertTrue(list.sql().contains("me.student_id = ? AND me.left_at IS NULL") && list.sql().contains("WHERE c.college_id = ?"));
        assertEquals(List.of(ME, COLLEGE), list.args());
        // Not a member (or another college's chat): not found, and nothing is read or written.
        assertError(ApiError.CHAT_NOT_FOUND, () -> chats.messages(account("STUDENT"), 9, null));
        assertError(ApiError.CHAT_NOT_FOUND, () -> chats.send(account("STUDENT"), 9, new MessageInput("Hi", UUID.randomUUID())));
        assertFalse(db.ran("INSERT INTO public.chat_messages"));
    }

    @Test void classGroupFollowsTheCurrentEnrollment() {
        db.on("FROM public.student_profiles WHERE college_id = ? AND student_id = ?", true)
                .on("INSERT INTO public.chat_members", 2)
                .on("WHERE m.conversation_id = ? AND m.left_at IS NULL", 0)
                .on("FROM public.student_enrollments e", List.<Object[]>of(new Object[]{41L, 51L, 21L, "BSc CS · Class A (2026-27)"}))
                .on("SELECT id FROM public.chat_conversations WHERE college_id = ? AND kind = 'CLASS'", 30L);
        chats.conversations(account("STUDENT"));
        assertTrue(db.calls("INSERT INTO public.chat_conversations").get(0).sql().contains("ON CONFLICT (college_id, division_id) WHERE kind = 'CLASS' DO NOTHING"));
        // Everyone currently enrolled in the division joins, and anyone no longer enrolled in it leaves.
        var join = db.calls("INSERT INTO public.chat_members").get(0);
        assertTrue(join.sql().contains("e.division_id = ? AND e.ended_at IS NULL"));
        assertEquals(List.of(30L, COLLEGE, 21L), join.args());
        assertEquals(List.of(30L, 21L), db.calls("WHERE m.conversation_id = ? AND m.left_at IS NULL").get(0).args());
        // Any other class group this student was in is left.
        var leave = db.calls("m.conversation_id IS DISTINCT FROM ?").get(0);
        assertEquals(List.of(COLLEGE, ME, 30L), leave.args());
    }

    @Test void messagesHideBlockedSendersAndReportedMessagesForTheViewer() {
        member("GROUP", "MEMBER", false, null, false);
        chats.messages(account("STUDENT"), 9, 120L);
        var read = db.calls("FROM public.chat_messages m JOIN public.users u").get(0);
        assertTrue(read.sql().contains("b.blocker_id = ? AND b.blocked_id = m.sender_id")
                && read.sql().contains("rp.reporter_id = ? AND rp.message_id = m.id") && read.sql().contains("m.id < 120"));
        assertEquals(List.of(COLLEGE, 9L, ME, ME), read.args());
        assertError(ApiError.CHAT_INVALID, () -> chats.messages(account("STUDENT"), 9, -1L));
    }

    @Test void sendingIsRetrySafeAndTellsMembersAfterCommit() {
        member("GROUP", "MEMBER", false, null, false);
        UUID key = UUID.randomUUID();
        db.on("WHERE sender_id = ? AND client_key = ?", List.of())
                .on("INSERT INTO public.chat_messages", 55L)
                .on("SELECT student_id FROM public.chat_members", List.of(ME, 8L))
                .on("FROM public.chat_messages m JOIN public.users u", List.of(message(55, ME)));
        assertEquals(55, chats.send(account("STUDENT"), 9, new MessageInput(" Hi ", key)).id());
        var insert = db.calls("INSERT INTO public.chat_messages").get(0);
        assertEquals(List.of(COLLEGE, 9L, ME, key, "Hi"), insert.args());
        verify(stream, never()).publish(any(), any(), anyLong());
        commit();
        verify(stream).publish(List.of(ME, 8L), "message", 9);

        // The same clientKey again returns the first message and inserts nothing.
        var retry = new ScriptedJdbc();
        var again = new StudentChats(retry, new MaterialFiles(storage.toString()), stream);
        retry.on("FROM public.student_profiles WHERE college_id = ? AND student_id = ?", true)
                .on("SELECT c.kind, me.role, c.archived_at IS NOT NULL", List.of(new Access(COLLEGE, 9, "GROUP", "MEMBER", false, null, false)))
                .on("WHERE sender_id = ? AND client_key = ?", List.<Object>of(new long[]{55, 9}))
                .on("FROM public.chat_messages m JOIN public.users u", List.of(message(55, ME)));
        assertEquals(55, again.send(account("STUDENT"), 9, new MessageInput("Hi", key)).id());
        assertFalse(retry.ran("INSERT INTO public.chat_messages"));
        assertError(ApiError.CHAT_INVALID, () -> again.send(account("STUDENT"), 9, new MessageInput("Hi", null)));
    }

    @Test void blockedPrivateChatsAndArchivedGroupsTakeNoMessages() {
        member("DIRECT", "MEMBER", false, 8L, true);
        assertError(ApiError.CHAT_BLOCKED, () -> chats.send(account("STUDENT"), 9, new MessageInput("Hi", UUID.randomUUID())));
        var archived = new ScriptedJdbc();
        var group = new StudentChats(archived, new MaterialFiles(storage.toString()), stream);
        archived.on("FROM public.student_profiles WHERE college_id = ? AND student_id = ?", true)
                .on("SELECT c.kind, me.role, c.archived_at IS NOT NULL", List.of(new Access(COLLEGE, 9, "GROUP", "MEMBER", true, null, false)));
        assertError(ApiError.CHAT_STATE_CONFLICT, () -> group.send(account("STUDENT"), 9, new MessageInput("Hi", UUID.randomUUID())));
        assertFalse(db.ran("INSERT INTO public.chat_messages") || archived.ran("INSERT INTO public.chat_messages"));
    }

    @Test void privateChatsNeedAnotherUnblockedStudentOfTheSameCollege() {
        db.on("FROM public.student_profiles WHERE college_id = ? AND student_id = ?", true);
        assertError(ApiError.CHAT_INVALID, () -> chats.direct(account("STUDENT"), new StudentRef(ME)));
        assertError(ApiError.CHAT_INVALID, () -> chats.direct(account("STUDENT"), new StudentRef(null)));
        // Another college's student (or an inactive one) is not found in this college.
        assertError(ApiError.CHAT_NOT_FOUND, () -> chats.direct(account("STUDENT"), new StudentRef(8L)));
        assertEquals(List.of(COLLEGE, 8L), db.calls("FROM public.student_profiles s JOIN public.users u").get(0).args());
        db.on("FROM public.student_profiles s JOIN public.users u", true).on("FROM public.chat_blocks", true);
        assertError(ApiError.CHAT_BLOCKED, () -> chats.direct(account("STUDENT"), new StudentRef(8L)));
        assertFalse(db.ran("INSERT INTO public.chat_conversations"));
    }

    @Test void onlyTheOwnerManagesAStudyGroupAndClassGroupsCannotBeLeft() {
        member("GROUP", "MEMBER", false, null, false);
        assertError(ApiError.CHAT_NOT_ALLOWED, () -> chats.updateGroup(account("STUDENT"), 9, new GroupInput("Renamed", "", "OPEN")));
        assertError(ApiError.CHAT_NOT_ALLOWED, () -> chats.addMember(account("STUDENT"), 9, new StudentRef(8L)));
        assertError(ApiError.CHAT_NOT_ALLOWED, () -> chats.removeMember(account("STUDENT"), 9, 8L));
        assertError(ApiError.CHAT_NOT_ALLOWED, () -> chats.archiveGroup(account("STUDENT"), 9));
        var classDb = new ScriptedJdbc();
        var classChats = new StudentChats(classDb, new MaterialFiles(storage.toString()), stream);
        classDb.on("FROM public.student_profiles WHERE college_id = ? AND student_id = ?", true)
                .on("SELECT c.kind, me.role, c.archived_at IS NOT NULL", List.of(new Access(COLLEGE, 9, "CLASS", "MEMBER", false, null, false)));
        assertError(ApiError.CHAT_NOT_ALLOWED, () -> classChats.leave(account("STUDENT"), 9));
        assertError(ApiError.CHAT_NOT_ALLOWED, () -> classChats.updateGroup(account("STUDENT"), 9, new GroupInput("Renamed", "", "OPEN")));
        assertFalse(db.ran("UPDATE public.chat_conversations SET name") || classDb.ran("SET left_at = statement_timestamp(), role"));
    }

    @Test void groupLimitsAndJoinRules() {
        db.on("FROM public.student_profiles WHERE college_id = ? AND student_id = ?", true)
                .on("m.role = 'OWNER' AND m.left_at IS NULL", 20L);
        assertError(ApiError.CHAT_LIMIT, () -> chats.createGroup(account("STUDENT"), new GroupInput("Java revision", "", "OPEN")));
        db.on("c.join_policy = 'OPEN' AND c.archived_at IS NULL", List.of(false));
        assertError(ApiError.CHAT_NOT_ALLOWED, () -> chats.join(account("STUDENT"), 9));
        assertFalse(db.ran("INSERT INTO public.chat_conversations") || db.ran("INSERT INTO public.chat_members"));
    }

    @Test void reportsHideTheMessageAndCanBlockTheSender() {
        member("GROUP", "MEMBER", false, null, false);
        db.on("FROM public.chat_messages m JOIN public.users u", List.of(message(55, 8)))
                .on("FROM public.student_profiles s JOIN public.users u", true);
        assertError(ApiError.CHAT_INVALID, () -> chats.report(account("STUDENT"), 9, 55, new ReportInput("RUDE", null, false)));
        chats.report(account("STUDENT"), 9, 55, new ReportInput("ABUSE", " insults ", true));
        assertEquals(List.of(COLLEGE, 55L, ME, "ABUSE", "insults"), db.calls("INSERT INTO public.chat_reports").get(0).args());
        assertEquals(List.of(COLLEGE, ME, 8L), db.calls("INSERT INTO public.chat_blocks").get(0).args());
        // Nobody can report their own message.
        var own = new ScriptedJdbc();
        var mine = new StudentChats(own, new MaterialFiles(storage.toString()), stream);
        own.on("FROM public.student_profiles WHERE college_id = ? AND student_id = ?", true)
                .on("SELECT c.kind, me.role, c.archived_at IS NOT NULL", List.of(new Access(COLLEGE, 9, "GROUP", "MEMBER", false, null, false)))
                .on("FROM public.chat_messages m JOIN public.users u", List.of(message(56, ME)));
        assertError(ApiError.CHAT_INVALID, () -> mine.report(account("STUDENT"), 9, 56, new ReportInput("SPAM", null, false)));
    }

    @Test void filesAreCheckedAndOnlyTheSenderDeletesAMessage() {
        member("GROUP", "MEMBER", false, null, false);
        db.on("WHERE sender_id = ? AND client_key = ?", List.of());
        assertError(ApiError.CHAT_FILE_INVALID, () -> chats.sendFile(account("STUDENT"), 9,
                new MockMultipartFile("file", "virus.exe", "application/pdf", PDF), null, UUID.randomUUID()));
        assertError(ApiError.CHAT_FILE_INVALID, () -> chats.sendFile(account("STUDENT"), 9,
                new MockMultipartFile("file", "notes.pdf", "application/pdf", "MZ".getBytes()), "see", UUID.randomUUID()));
        assertFalse(db.ran("INSERT INTO public.chat_messages"));
        // Deleting finds only the actor's own, undeleted message.
        assertError(ApiError.CHAT_NOT_FOUND, () -> chats.delete(account("STUDENT"), 9, 55));
        var lookup = db.calls("SELECT attachment_key FROM public.chat_messages").get(0);
        assertTrue(lookup.sql().contains("sender_id = ? AND deleted_at IS NULL"));
        assertEquals(List.of(COLLEGE, 9L, 55L, ME), lookup.args());
    }

    @Test void readMarksNeverMoveBackwards() {
        member("DIRECT", "MEMBER", false, 8L, false);
        db.on("UPDATE public.chat_members SET last_read_message_id = LEAST", 0);
        chats.read(account("STUDENT"), 9, new ReadInput(40L));
        assertTrue(db.calls("SET last_read_message_id = LEAST").get(0).sql().contains("last_read_message_id < ?"));
        assertError(ApiError.CHAT_INVALID, () -> chats.read(account("STUDENT"), 9, new ReadInput(-1L)));
        commit();
        verify(stream, never()).publish(any(), any(), anyLong());
    }

    @Test void theStreamSendsOnlyToConnectedMembersAndLimitsConnections() {
        var live = new ChatStream();
        try {
            for (int i = 0; i < ChatStream.MAX_PER_STUDENT + 2; i++) live.connect(ME);
            assertEquals(ChatStream.MAX_PER_STUDENT, live.connections(ME));
            assertEquals(0, live.connections(8L));
            live.publish(List.of(ME, 8L), "message", 9);
        } finally {
            live.stop();
        }
    }
}
