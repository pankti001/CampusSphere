package com.campussphere.backend.service;

import java.time.*;
import java.util.*;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Skill Exchange version 2: "I want to learn" posts, learning groups and learning from faculty. The fake database holds
 * two colleges (A and B) and two classes in college A (31 and 32). It answers each lookup only for rows the asked-for
 * college, class and person may see, as the real WHERE clauses do. The negative tests prove that a class-only post or
 * request stays in its class, nothing crosses colleges, groups stay with their members, and faculty only see requests
 * sent to them.
 */
class SkillVersion2Test {
    static final long A = 303L, B = 404L;
    static final long ME = 7L, MATE = 8L, OTHER_CLASS = 9L, STRANGER = 20L, PROF = 50L, PROF_B = 60L;
    static final long CLASS_31 = 31L, CLASS_32 = 32L, CLASS_B = 41L;
    static final long W_CLASS = 1000L, W_COLLEGE = 1001L, W_B = 1002L;
    static final long F_CLASS = 2000L, F_B = 2001L;
    static final long GROUP = 3000L, GROUP_B = 3001L;
    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-12-01T10:00:00Z");

    record Person(long college, Long division) {}
    record Post(long id, long college, long owner, String audience, Long division, String status) {}
    record Ask(long id, long college, long faculty, long creator, String audience, Long division, String status, Set<Long> members) {}
    record Grp(long id, long college, long teacher, Set<Long> learners) {}

    final Map<Long, Person> students = Map.of(ME, new Person(A, CLASS_31), MATE, new Person(A, CLASS_31),
            OTHER_CLASS, new Person(A, CLASS_32), STRANGER, new Person(B, CLASS_B));
    final Map<Long, Long> faculty = Map.of(PROF, A, PROF_B, B);
    final Map<Long, Post> posts = new HashMap<>(Map.of(
            W_CLASS, new Post(W_CLASS, A, MATE, "CLASS", CLASS_31, "OPEN"),
            W_COLLEGE, new Post(W_COLLEGE, A, MATE, "COLLEGE", null, "OPEN"),
            W_B, new Post(W_B, B, STRANGER, "COLLEGE", null, "OPEN"),
            1003L, new Post(1003L, A, ME, "CLASS", CLASS_31, "OPEN")));
    final Map<Long, Ask> asks = new HashMap<>(Map.of(
            F_CLASS, new Ask(F_CLASS, A, PROF, MATE, "CLASS", CLASS_31, "OPEN", new HashSet<>(Set.of(MATE))),
            F_B, new Ask(F_B, B, PROF_B, STRANGER, "COLLEGE", null, "OPEN", new HashSet<>(Set.of(STRANGER))),
            2002L, new Ask(2002L, A, PROF, ME, "CLASS", CLASS_31, "OPEN", new HashSet<>(Set.of(ME)))));
    final Map<Long, Grp> groupRows = Map.of(GROUP, new Grp(GROUP, A, MATE, Set.of(ME)), GROUP_B, new Grp(GROUP_B, B, STRANGER, Set.of(21L)));

    private final ScriptedJdbc db = new ScriptedJdbc();
    private SkillExchange skills;
    private SkillWants wants;
    private SkillGroups groups;
    private SkillFaculty learning;

    static long n(Object v) { return ((Number) v).longValue(); }
    static boolean same(Object a, Long b) { return a != null && b != null && n(a) == b; }

    static Account student(long id, long college) {
        return new Account(id, true, false, Set.of("STUDENT"), new CollegeScope(college, true));
    }

    static Account teacher(long id, long college) {
        return new Account(id, true, false, Set.of("FACULTY"), new CollegeScope(college, true));
    }

    interface Action { void run(); }

    static void assertError(ApiError expected, Action action) {
        var failure = assertThrows(ApiError.Failure.class, action::run);
        assertEquals(expected, failure.error());
    }

    boolean seesPost(Post p, Object college, Object me, Object division) {
        return p.college() == n(college) && (p.owner() == n(me) || p.audience().equals("COLLEGE") || same(division, p.division()));
    }

    SkillWants.Want want(Post p, long me) {
        return new SkillWants.Want(p.id(), p.owner(), "Ravi Test", "BSc · A", "Excel formulas", "", "OTHER", p.audience(),
                p.status(), NOW, 0, 0, 0, p.owner() == me, false, null);
    }

    SkillFaculty.FacultyRequest ask(Ask a, long me) {
        return new SkillFaculty.FacultyRequest(a.id(), a.faculty(), "Prof Test", a.creator(), "Ravi Test", "Trees", "",
                a.audience(), a.status(), null, null, null, null, null, NOW, a.members().size(), a.members().contains(me),
                a.creator() == me, false);
    }

    @BeforeEach void world() {
        var notices = new SkillNotices(db);
        var maker = new SkillGroupMaker(db, null, notices);
        skills = new SkillExchange(db, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC), notices, maker);
        wants = new SkillWants(db, skills, notices, maker);
        groups = new SkillGroups(db, skills, notices);
        learning = new SkillFaculty(db, skills, notices);
        db.answer("SELECT EXISTS (SELECT 1 FROM public.student_profiles WHERE college_id = ? AND student_id = ?)",
                a -> students.containsKey(n(a.get(1))) && students.get(n(a.get(1))).college() == n(a.get(0)));
        db.answer("SELECT division_id FROM public.student_enrollments", a -> {
            Person p = students.get(n(a.get(1)));
            return p != null && p.college() == n(a.get(0)) ? List.of(p.division()) : List.of();
        });
        db.answer("SELECT EXISTS (SELECT 1 FROM public.faculty_profiles WHERE college_id = ? AND faculty_id = ?)",
                a -> Objects.equals(faculty.get(n(a.get(1))), n(a.get(0))));
        db.answer("SELECT EXISTS (SELECT 1 FROM public.faculty_profiles fp JOIN public.users u",
                a -> Objects.equals(faculty.get(n(a.get(1))), n(a.get(0))));
        // Posts: one post (me, me, me, college, id, me, division); the feed (me, me, me, college, division, ...).
        db.answer(" AND w.id = ? AND (w.student_id = ?", a -> Optional.ofNullable(posts.get(n(a.get(4))))
                .filter(p -> seesPost(p, a.get(3), a.get(0), a.get(6))).map(p -> want(p, n(a.get(0)))).stream().toList());
        db.answer("FROM public.skill_wants w JOIN public.users u", a -> {
            return posts.values().stream().filter(p -> p.college() == n(a.get(3)) && p.status().equals("OPEN")
                    && (p.audience().equals("COLLEGE") || same(a.get(4), p.division()))).map(p -> want(p, n(a.get(0)))).toList();
        });
        db.answer("SELECT id FROM public.skill_wants WHERE college_id = ? AND id = ? FOR UPDATE", a -> List.of(n(a.get(1))));
        db.on("INSERT INTO public.skill_want_comments", 4000L);
        db.on("FROM public.skill_want_comments c JOIN public.users u", List.of(new SkillWants.Comment(4000L, ME, "Asha Test", "Hi", NOW, true, false)));
        db.on("INSERT INTO public.skill_wants", 1003L);
        // Faculty requests: faculty view (me, me, college, id, me); student view (me, me, college, id, me, division).
        db.answer(" AND fr.id = ? AND fr.faculty_id = ?", a -> Optional.ofNullable(asks.get(n(a.get(3))))
                .filter(x -> x.college() == n(a.get(2)) && x.faculty() == n(a.get(4))).map(x -> ask(x, n(a.get(0)))).stream().toList());
        db.answer(" AND fr.id = ? AND (EXISTS", a -> Optional.ofNullable(asks.get(n(a.get(3))))
                .filter(x -> x.college() == n(a.get(2)) && (x.members().contains(n(a.get(4))) || x.audience().equals("COLLEGE")
                        || same(a.get(5), x.division()))).map(x -> ask(x, n(a.get(0)))).stream().toList());
        db.answer("ORDER BY (fr.status IN ('OPEN', 'ACCEPTED')) DESC", a -> asks.values().stream()
                .filter(x -> x.college() == n(a.get(2)) && (x.members().contains(n(a.get(3))) || x.audience().equals("COLLEGE")
                        || same(a.get(4), x.division()))).map(x -> ask(x, n(a.get(0)))).toList());
        db.answer(" AND fr.faculty_id = ? ORDER BY", a -> asks.values().stream()
                .filter(x -> x.college() == n(a.get(2)) && x.faculty() == n(a.get(3))).map(x -> ask(x, n(a.get(0)))).toList());
        db.on("INSERT INTO public.skill_faculty_requests", 2002L);
        db.answer("SELECT id FROM public.skill_faculty_requests WHERE college_id = ? AND id = ? FOR UPDATE", a -> List.of(n(a.get(1))));
        // Groups: one group (me, college, me, me, id).
        db.answer("FROM public.skill_groups g JOIN public.users u", a -> {
            var found = new ArrayList<SkillGroups.Group>();
            for (Grp g : groupRows.values()) {
                long me = n(a.get(0));
                boolean member = g.teacher() == me || g.learners().contains(me);
                if (g.college() == n(a.get(1)) && member && (a.size() < 5 || g.id() == n(a.get(4))))
                    found.add(new SkillGroups.Group(g.id(), "Learn: Java", g.teacher(), "Ravi Test", g.teacher() == me, "ACTIVE",
                            900L, g.learners().size(), null, g.learners().contains(me) ? 5000L : null));
            }
            return found;
        });
        db.answer("SELECT learner_id FROM public.skill_group_members", a -> n(a.get(1)) == GROUP && n(a.get(0)) == A
                ? List.of(ME) : List.of());
        db.on("INSERT INTO public.skill_group_sessions", 6000L);
    }

    // ------------------------------------------------------------------ Posts

    @Test void aClassOnlyPostStaysInItsClassAndCollegePostsStayInTheirCollege() {
        // The poster's classmate sees both posts; a student of another class only the college-wide one.
        assertEquals(W_CLASS, wants.detail(student(ME, A), W_CLASS).want().id());
        assertEquals(W_COLLEGE, wants.detail(student(OTHER_CLASS, A), W_COLLEGE).want().id());
        var other = student(OTHER_CLASS, A);
        assertError(ApiError.SKILL_NOT_FOUND, () -> wants.detail(other, W_CLASS));
        assertError(ApiError.SKILL_NOT_FOUND, () -> wants.comment(other, W_CLASS, new SkillWants.TextInput("Hi")));
        assertError(ApiError.SKILL_NOT_FOUND, () -> wants.meToo(other, W_CLASS, true));
        assertError(ApiError.SKILL_NOT_FOUND, () -> wants.help(other, W_CLASS, new SkillWants.TextInput("I can")));
        assertEquals(List.of(W_COLLEGE), wants.list(other, false, null, null, 0).stream().map(SkillWants.Want::id).toList());
        // A student of another college sees neither.
        var stranger = student(STRANGER, B);
        for (long id : List.of(W_CLASS, W_COLLEGE)) {
            assertError(ApiError.SKILL_NOT_FOUND, () -> wants.detail(stranger, id));
            assertError(ApiError.SKILL_NOT_FOUND, () -> wants.comment(stranger, id, new SkillWants.TextInput("Hi")));
            assertError(ApiError.SKILL_NOT_FOUND, () -> wants.meToo(stranger, id, true));
            assertError(ApiError.SKILL_NOT_FOUND, () -> wants.help(stranger, id, new SkillWants.TextInput("")));
            assertError(ApiError.SKILL_NOT_FOUND, () -> wants.accept(stranger, id, 1));
            assertError(ApiError.SKILL_NOT_FOUND, () -> wants.close(stranger, id));
        }
        assertTrue(wants.list(stranger, false, null, null, 0).stream().noneMatch(w -> w.id() == W_CLASS || w.id() == W_COLLEGE));
        // And nobody of college A sees college B's post.
        assertError(ApiError.SKILL_NOT_FOUND, () -> wants.detail(student(ME, A), W_B));
        assertFalse(db.ran("INSERT INTO public.skill_want"));
        assertFalse(db.ran("INSERT INTO public.skill_notifications"));
    }

    @Test void theFeedUsesTheStudentsOwnClassAndCollege() {
        wants.list(student(OTHER_CLASS, A), false, "excel", "OTHER", 0);
        var feed = db.calls("FROM public.skill_wants w JOIN public.users u").get(0);
        assertTrue(feed.sql().contains("w.college_id = ?") && feed.sql().contains("(w.audience = 'COLLEGE' OR w.division_id = ?)"));
        assertEquals(List.of(OTHER_CLASS, OTHER_CLASS, OTHER_CLASS, A, CLASS_32), feed.args().subList(0, 5));
    }

    @Test void onlyThePosterAcceptsOrClosesAndPostsAreChecked() {
        var me = student(ME, A);
        assertError(ApiError.SKILL_NOT_FOUND, () -> wants.accept(me, W_CLASS, 1));
        assertError(ApiError.SKILL_NOT_FOUND, () -> wants.decline(me, W_CLASS, 1));
        assertError(ApiError.SKILL_STATE_CONFLICT, () -> wants.close(me, W_CLASS));
        // The poster cannot say "Me too" or offer to teach their own post.
        var poster = student(MATE, A);
        assertError(ApiError.SKILL_STATE_CONFLICT, () -> wants.meToo(poster, W_CLASS, true));
        assertError(ApiError.SKILL_STATE_CONFLICT, () -> wants.help(poster, W_CLASS, new SkillWants.TextInput("")));
        for (var bad : List.of(new SkillWants.WantInput("Hi", "", "OTHER", "CLASS"),
                new SkillWants.WantInput("x".repeat(81), "", "OTHER", "CLASS"),
                new SkillWants.WantInput("Excel", "d".repeat(1001), "OTHER", "CLASS"),
                new SkillWants.WantInput("Excel", "", "COOKING", "CLASS"),
                new SkillWants.WantInput("Excel", "", "OTHER", "EVERYONE"))) {
            assertError(ApiError.SKILL_INVALID, () -> SkillWants.clean(bad));
        }
        assertEquals("COLLEGE", SkillWants.clean(new SkillWants.WantInput(" Excel ", null, "OTHER", null)).audience());
    }

    @Test void aCommentNotifiesOnlyThePosterInTheSameCollege() {
        db.on("SELECT split_part(full_name, ' ', 1) FROM public.users", "Asha");
        wants.comment(student(ME, A), W_CLASS, new SkillWants.TextInput(" Me too, any tips? "));
        var insert = db.calls("INSERT INTO public.skill_want_comments").get(0);
        assertEquals(List.of(A, W_CLASS, ME, "Me too, any tips?"), insert.args());
        var notice = db.calls("INSERT INTO public.skill_notifications").get(0);
        assertEquals(List.of(A, MATE, "WANT_COMMENT"), notice.args().subList(0, 3));
        assertEquals(W_CLASS, n(notice.args().get(8)), "linked to the post");
    }

    @Test void postingToTheClassUsesTheStudentsOwnClass() {
        wants.create(student(ME, A), new SkillWants.WantInput("Excel formulas", "", "OTHER", "CLASS"));
        var insert = db.calls("INSERT INTO public.skill_wants").get(0);
        assertEquals(List.of(A, ME, "Excel formulas", "", "OTHER", "CLASS", CLASS_31), insert.args());
    }

    // ------------------------------------------------------------------ Faculty

    @Test void facultyRequestsStayInTheirClassCollegeAndWithTheChosenFaculty() {
        var other = student(OTHER_CLASS, A);
        assertError(ApiError.SKILL_NOT_FOUND, () -> learning.request(other, F_CLASS));
        assertError(ApiError.SKILL_NOT_FOUND, () -> learning.join(other, F_CLASS));
        var stranger = student(STRANGER, B);
        for (Action a : List.<Action>of(() -> learning.request(stranger, F_CLASS), () -> learning.join(stranger, F_CLASS),
                () -> learning.leave(stranger, F_CLASS), () -> learning.cancel(stranger, F_CLASS))) {
            assertError(ApiError.SKILL_NOT_FOUND, a);
        }
        assertTrue(learning.requests(other).isEmpty());
        assertTrue(learning.requests(stranger).stream().noneMatch(r -> r.id() == F_CLASS));
        // Another faculty member, of this college or another, cannot see or answer it.
        var input = new SkillFaculty.AcceptInput(NOW.plusDays(1), NOW.plusDays(1).plusHours(1), "IN_PERSON", "Room 101");
        for (Account notChosen : List.of(teacher(PROF_B, B), teacher(PROF_B, A))) {
            assertThrows(ApiError.Failure.class, () -> learning.facultyRequest(notChosen, F_CLASS));
            assertThrows(ApiError.Failure.class, () -> learning.accept(notChosen, F_CLASS, input));
            assertThrows(ApiError.Failure.class, () -> learning.decline(notChosen, F_CLASS, new SkillFaculty.ReasonInput("")));
        }
        assertTrue(learning.facultyRequests(teacher(PROF_B, B)).stream().noneMatch(r -> r.id() == F_CLASS));
        assertFalse(db.ran("UPDATE public.skill_faculty_requests"));
        assertFalse(db.ran("INSERT INTO public.skill_faculty_request_members"));
        // The chosen faculty member sees it.
        assertEquals(F_CLASS, learning.facultyRequest(teacher(PROF, A), F_CLASS).request().id());
    }

    @Test void studentsCanOnlyAskFacultyOfTheirOwnCollege() {
        var me = student(ME, A);
        assertError(ApiError.SKILL_NOT_FOUND, () -> learning.ask(me, new SkillFaculty.AskInput(PROF_B, "Trees", "", "CLASS")));
        assertFalse(db.ran("INSERT INTO public.skill_faculty_requests"));
        learning.ask(me, new SkillFaculty.AskInput(PROF, " Data structures: trees ", "", "CLASS"));
        var insert = db.calls("INSERT INTO public.skill_faculty_requests").get(0);
        assertEquals(List.of(A, PROF, ME, "Data structures: trees", "", "CLASS", CLASS_31), insert.args());
        assertEquals(List.of(A, 2002L, ME), db.calls("INSERT INTO public.skill_faculty_request_members").get(0).args());
        var notice = db.calls("INSERT INTO public.skill_faculty_notices").get(0);
        assertEquals(List.of(A, PROF, "NEW"), notice.args().subList(0, 3));
        assertError(ApiError.SKILL_INVALID, () -> learning.ask(me, new SkillFaculty.AskInput(PROF, "ab", "", "CLASS")));
        assertError(ApiError.SKILL_INVALID, () -> learning.ask(me, new SkillFaculty.AskInput(null, "Trees", "", "CLASS")));
    }

    @Test void theFacultySideIsForFacultyAndTheStudentSideForStudents() {
        assertError(ApiError.ACCESS_DENIED, () -> learning.facultyRequests(student(ME, A)));
        assertError(ApiError.ACCESS_DENIED, () -> learning.notices(student(ME, A)));
        assertError(ApiError.ACCESS_DENIED, () -> learning.requests(teacher(PROF, A)));
        assertError(ApiError.ACCESS_DENIED, () -> wants.list(teacher(PROF, A), false, null, null, 0));
        assertError(ApiError.ACCESS_DENIED, () -> groups.groups(teacher(PROF, A)));
        // A faculty account whose token names another college has no faculty profile there.
        assertError(ApiError.ACCESS_DENIED, () -> learning.facultyRequests(teacher(PROF, B)));
    }

    @Test void acceptingTellsEveryStudentWhoJoinedInTheSameCollege() {
        asks.put(F_CLASS, new Ask(F_CLASS, A, PROF, MATE, "CLASS", CLASS_31, "OPEN", new HashSet<>(Set.of(MATE, ME))));
        db.on("SELECT m.student_id, u.full_name", List.of(new SkillFaculty.Student(MATE, "Ravi Test", null),
                new SkillFaculty.Student(ME, "Asha Test", null)));
        learning.accept(teacher(PROF, A), F_CLASS,
                new SkillFaculty.AcceptInput(NOW.plusDays(1), NOW.plusDays(1).plusHours(1), "IN_PERSON", "Room 101"));
        var update = db.calls("UPDATE public.skill_faculty_requests SET status = 'ACCEPTED'").get(0);
        assertEquals(List.of(A, F_CLASS), update.args().subList(4, 6));
        var told = db.calls("INSERT INTO public.skill_notifications");
        assertEquals(2, told.size());
        assertTrue(told.stream().allMatch(c -> c.args().get(0).equals(A) && c.args().get(2).equals("FACULTY_ACCEPTED")));
        assertEquals(Set.of(MATE, ME), new HashSet<>(told.stream().map(c -> n(c.args().get(1))).toList()));
        assertError(ApiError.SKILL_INVALID, () -> learning.accept(teacher(PROF, A), F_CLASS,
                new SkillFaculty.AcceptInput(NOW.minusHours(1), NOW, "ONLINE", "")));
    }

    // ------------------------------------------------------------------ Groups

    @Test void aGroupIsOnlyForItsTeacherAndLearners() {
        for (Account outsider : List.of(student(OTHER_CLASS, A), student(STRANGER, B))) {
            assertError(ApiError.SKILL_NOT_FOUND, () -> groups.detail(outsider, GROUP));
            assertError(ApiError.SKILL_NOT_FOUND, () -> groups.leave(outsider, GROUP));
            assertError(ApiError.SKILL_NOT_FOUND, () -> groups.schedule(outsider, GROUP,
                    new SkillGroups.GroupSessionInput(NOW.plusDays(1), NOW.plusDays(1).plusHours(1), "ONLINE", "")));
            assertTrue(groups.groups(outsider).stream().noneMatch(g -> g.id() == GROUP));
        }
        assertError(ApiError.SKILL_NOT_FOUND, () -> groups.detail(student(ME, A), GROUP_B));
        // A learner cannot set group sessions; only the teaching student can.
        assertError(ApiError.SKILL_NOT_FOUND, () -> groups.schedule(student(ME, A), GROUP,
                new SkillGroups.GroupSessionInput(NOW.plusDays(1), NOW.plusDays(1).plusHours(1), "ONLINE", "")));
        assertFalse(db.ran("INSERT INTO public.skill_group_sessions"));
    }

    @Test void theTeachingStudentSetsGroupSessionsAndLearnersAreTold() {
        groups.schedule(student(MATE, A), GROUP,
                new SkillGroups.GroupSessionInput(NOW.plusDays(1), NOW.plusDays(1).plusHours(2), "IN_PERSON", "Library"));
        var insert = db.calls("INSERT INTO public.skill_group_sessions").get(0);
        assertEquals(List.of(A, GROUP), insert.args().subList(0, 2));
        var told = db.calls("INSERT INTO public.skill_notifications");
        assertEquals(1, told.size());
        assertEquals(List.of(A, ME, "GROUP_SESSION"), told.get(0).args().subList(0, 3));
        assertError(ApiError.SKILL_INVALID, () -> groups.schedule(student(MATE, A), GROUP,
                new SkillGroups.GroupSessionInput(NOW.plusDays(1), NOW.plusDays(1).plusHours(5), "ONLINE", "")));
    }

    @Test void twoAcceptedLearnersOfTheSameSkillFormAGroupWithAChat() {
        var maker = new SkillGroupMaker(db, null, new SkillNotices(db));
        db.on("SELECT name FROM public.skill_offers", "Java basics");
        db.on("FROM public.skill_requests q", List.of(new SkillGroupMaker.Accepted(501, MATE, OTHER_CLASS, 100L, null, "ACCEPTED")));
        db.on("SELECT id, provider_id, learner_id, offer_id, want_id, status FROM public.skill_requests",
                List.of(new SkillGroupMaker.Accepted(500, MATE, ME, 100L, null, "ACCEPTED")));
        db.on("INSERT INTO public.chat_conversations", 900L);
        db.on("INSERT INTO public.skill_groups", 3100L);
        db.on("SELECT conversation_id FROM public.skill_groups", List.of(900L));
        maker.afterAccepted(A, 500);
        assertEquals(List.of(A, "Learn: Java basics", "Learning group for Java basics (Skill Exchange)", MATE),
                db.calls("INSERT INTO public.chat_conversations").get(0).args());
        assertEquals(Arrays.asList(A, MATE, 100L, null, "Learn: Java basics", 900L), db.calls("INSERT INTO public.skill_groups").get(0).args());
        var members = db.calls("INSERT INTO public.skill_group_members");
        assertEquals(List.of(List.of(A, 3100L, ME, 500L), List.of(A, 3100L, OTHER_CLASS, 501L)), members.stream().map(c -> c.args()).toList());
        var chatMembers = db.calls("INSERT INTO public.chat_members");
        assertEquals(3, chatMembers.size(), "teacher and both learners");
        assertTrue(chatMembers.stream().allMatch(c -> c.args().get(0).equals(A)));
        // Each learner and the teacher are told; all in college A.
        assertTrue(db.calls("INSERT INTO public.skill_notifications").stream().allMatch(c -> c.args().get(0).equals(A)));
        assertEquals(3, db.calls("INSERT INTO public.skill_notifications").size());
    }

    @Test void theLastLearnerLeavingClosesTheGroupAndArchivesItsChat() {
        var maker = new SkillGroupMaker(db, null, new SkillNotices(db));
        db.on("SELECT m.group_id, m.learner_id", List.of(new long[]{3100L, ME}));
        db.on("SELECT conversation_id FROM public.skill_groups", List.of(900L));
        db.on("SELECT count(*) FROM public.skill_group_members", 0L);
        maker.afterCancelled(A, 500);
        assertTrue(db.ran("UPDATE public.skill_group_members SET left_at"));
        assertTrue(db.ran("UPDATE public.chat_members SET left_at"));
        assertEquals(List.of(A, 3100L), db.calls("UPDATE public.skill_groups SET status = 'CLOSED'").get(0).args());
        assertTrue(db.ran("UPDATE public.skill_group_sessions SET status = 'CANCELLED'"));
        assertEquals(List.of(A, 900L), db.calls("UPDATE public.chat_conversations SET archived_at").get(0).args());
    }

    // ------------------------------------------------------------------ Scoping

    @Test void everyStatementNamesTheStudentsOwnCollegeOnly() {
        var me = student(ME, A);
        db.on("SELECT split_part(full_name, ' ', 1) FROM public.users", "Asha");
        wants.list(me, false, null, null, 0);
        wants.list(me, true, null, null, 0);
        wants.detail(me, W_CLASS);
        wants.comment(me, W_CLASS, new SkillWants.TextInput("Hi"));
        wants.meToo(me, W_COLLEGE, true);
        wants.help(me, W_COLLEGE, new SkillWants.TextInput("I can"));
        learning.faculty(me);
        learning.requests(me);
        learning.request(me, F_CLASS);
        groups.groups(me);
        groups.detail(me, GROUP);
        assertFalse(db.calls.isEmpty());
        for (var call : db.calls) {
            if (call.sql().contains("FROM public.users WHERE id = ?")) continue; // a first name by user id only
            assertTrue(call.args().contains(A), "college A is bound: " + call.sql());
            assertFalse(call.args().contains(B), call.sql());
        }
    }

    @Test void clientsCannotSendACollegeClassOrPersonInTheNewForms() {
        assertThrows(IllegalArgumentException.class, () -> new SkillWants.WantInput("Excel", "", "OTHER", "CLASS").rejectUnknownField("collegeId", B));
        assertThrows(IllegalArgumentException.class, () -> new SkillWants.WantInput("Excel", "", "OTHER", "CLASS").rejectUnknownField("divisionId", 32));
        assertThrows(IllegalArgumentException.class, () -> new SkillWants.TextInput("Hi").rejectUnknownField("authorId", MATE));
        assertThrows(IllegalArgumentException.class, () -> new SkillFaculty.AskInput(PROF, "Trees", "", "CLASS").rejectUnknownField("collegeId", B));
        assertThrows(IllegalArgumentException.class, () -> new SkillFaculty.AcceptInput(NOW, NOW, "ONLINE", "").rejectUnknownField("facultyId", PROF_B));
        assertThrows(IllegalArgumentException.class, () -> new SkillGroups.GroupSessionInput(NOW, NOW, "ONLINE", "").rejectUnknownField("groupId", GROUP_B));
    }
}
