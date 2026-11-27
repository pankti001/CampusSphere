package com.campussphere.backend.service;

import java.time.*;
import java.util.*;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.SkillExchange.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Skill Exchange rules and, above all, college isolation. The fake database below holds two colleges, A and B, and
 * answers each lookup only for rows of the college the service asks about, as the real WHERE clauses do. The
 * negative tests prove a student of one college can never find, use or change anything of the other, whatever IDs
 * they send.
 */
class SkillExchangeTest {
    static final long A = 303L, B = 404L;
    // College A: me (7), classmate (8). College B: stranger (20).
    static final long ME = 7L, MATE = 8L, STRANGER = 20L;
    // College A rows.
    static final long A_OFFER = 100L, MY_OFFER = 101L, A_REQUEST = 200L, A_EXCHANGE = 201L, A_SESSION = 300L, A_NOTICE = 400L;
    // College B rows.
    static final long B_OFFER = 500L, B_REQUEST = 600L, B_SESSION = 700L, B_NOTICE = 800L;
    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-12-01T10:00:00Z");

    private final ScriptedJdbc db = new ScriptedJdbc();
    private SkillExchange skills;

    record Row(long college, long id, long owner, String status) {}
    // Offers: owner is the provider.
    final List<Row> offers = new ArrayList<>(List.of(new Row(A, A_OFFER, MATE, "ACTIVE"), new Row(A, MY_OFFER, ME, "ACTIVE"),
            new Row(A, 102L, ME, "ACTIVE"),
            new Row(B, B_OFFER, STRANGER, "ACTIVE")));
    final Map<Long, Long> profiles = Map.of(ME, A, MATE, A, STRANGER, B);
    final Map<Long, RequestRow> requests = new HashMap<>(Map.of(
            A_REQUEST, new RequestRow(A_REQUEST, MATE, ME, A_OFFER, null, "ACCEPTED"),
            A_EXCHANGE, new RequestRow(A_EXCHANGE, MATE, ME, A_OFFER, MY_OFFER, "ACCEPTED"),
            B_REQUEST, new RequestRow(B_REQUEST, STRANGER, 21L, B_OFFER, null, "REQUESTED")));
    final Map<Long, Long> requestCollege = new HashMap<>(Map.of(A_REQUEST, A, A_EXCHANGE, A, B_REQUEST, B));
    final Map<Long, SessionRow> sessions = new HashMap<>(Map.of(
            A_SESSION, new SessionRow(A_SESSION, MATE, ME, MATE, NOW.minusHours(3), NOW.minusHours(2), "COMPLETED"),
            B_SESSION, new SessionRow(B_SESSION, STRANGER, 21L, STRANGER, NOW.minusHours(3), NOW.minusHours(2), "COMPLETED")));
    final Map<Long, Long> sessionCollege = Map.of(A_SESSION, A, B_SESSION, B);
    final Map<Long, long[]> notices = Map.of(A_NOTICE, new long[]{A, ME}, B_NOTICE, new long[]{B, STRANGER});

    static long n(Object value) {
        return ((Number) value).longValue();
    }

    @BeforeEach void world() {
        skills = new SkillExchange(db, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        // A student profile exists only in that student's own college.
        db.answer("SELECT EXISTS (SELECT 1 FROM public.student_profiles WHERE college_id = ? AND student_id = ?)",
                a -> Objects.equals(profiles.get(n(a.get(1))), n(a.get(0))));
        // Offer lookups (college, id[, owner]).
        db.answer("SELECT student_id FROM public.skill_offers WHERE college_id = ? AND id = ? AND status = 'ACTIVE'",
                a -> offers.stream().filter(o -> o.college() == n(a.get(0)) && o.id() == n(a.get(1)) && o.status().equals("ACTIVE"))
                        .map(Row::owner).toList());
        db.answer("SELECT EXISTS (SELECT 1 FROM public.skill_offers WHERE college_id = ? AND id = ? AND student_id = ?",
                a -> offers.stream().anyMatch(o -> o.college() == n(a.get(0)) && o.id() == n(a.get(1)) && o.owner() == n(a.get(2))));
        db.answer("SELECT status FROM public.skill_offers",
                a -> offers.stream().filter(o -> o.college() == n(a.get(0)) && o.id() == n(a.get(1)) && o.owner() == n(a.get(2)))
                        .map(Row::status).toList());
        // The full offer view: args (me, college, id, me) for one offer.
        db.answer("FROM public.skill_offers o JOIN public.users u", a -> a.size() == 4
                ? offers.stream().filter(o -> o.college() == n(a.get(1)) && o.id() == n(a.get(2))
                        && (o.status().equals("ACTIVE") || o.owner() == n(a.get(3)))).map(o -> offer(o.id(), o.owner())).toList()
                : List.of());
        // Requests: (college, id, me, me) for the rule row; (college, me, me, id) for the view.
        db.answer("SELECT id, provider_id, learner_id, offer_id, exchange_offer_id, status FROM public.skill_requests",
                a -> Optional.ofNullable(requests.get(n(a.get(1)))).filter(r -> requestCollege.get(r.id()) == n(a.get(0))
                        && (r.providerId() == n(a.get(2)) || r.learnerId() == n(a.get(2)))).stream().toList());
        db.on("INSERT INTO public.skill_requests", 210L);
        db.answer("FROM public.skill_requests q", a -> {
            if (a.size() != 4) return List.of();
            long id = n(a.get(3));
            if (id == 210L && n(a.get(0)) == A) return List.of(request(210L, MATE, ME, "REQUESTED"));
            return Optional.ofNullable(requests.get(id)).filter(r -> requestCollege.get(id) == n(a.get(0))
                    && (r.providerId() == n(a.get(1)) || r.learnerId() == n(a.get(1))))
                    .map(r -> request(id, r.providerId(), r.learnerId(), r.status())).stream().toList();
        });
        // Sessions: (college, id, me, me) for the rule row; (me, college, me, me, id) for the view.
        db.answer("SELECT id, teacher_id, pupil_id, proposed_by", a -> Optional.ofNullable(sessions.get(n(a.get(1))))
                .filter(s -> sessionCollege.get(s.id()) == n(a.get(0)) && (s.teacherId() == n(a.get(2)) || s.pupilId() == n(a.get(2))))
                .stream().toList());
        db.on("INSERT INTO public.skill_sessions", 310L);
        db.answer("JOIN public.users tu", a -> {
            if (a.size() != 5 || !(a.get(4) instanceof Number id)) return List.of();
            long sid = n(id), college = n(a.get(1)), me = n(a.get(2));
            if (sid == 310L) return college == A ? List.of(session(sid)) : List.of();
            var s = sessions.get(sid);
            return s != null && sessionCollege.get(sid) == college && (s.teacherId() == me || s.pupilId() == me)
                    ? List.of(session(sid)) : List.of();
        });
        db.on("INSERT INTO public.skill_offers", 102L);
        db.on("SELECT (SELECT count(*) FROM public.skill_offers", new long[]{1, 0, 0, 0, 0});
        // Reviews.
        db.on("INSERT INTO public.skill_reviews", 900L);
        db.answer("FROM public.skill_reviews r JOIN public.users u", a -> n(a.get(0)) == A
                ? List.of(new Review(900L, 5, "", "Asha", "Java", NOW)) : List.of());
        // Notifications: (college, me, id).
        db.answer("UPDATE public.skill_notifications SET read_at", a -> Optional.ofNullable(notices.get(n(a.get(2))))
                .filter(x -> x[0] == n(a.get(0)) && x[1] == n(a.get(1))).isPresent() ? 1 : 0);
        db.answer("SELECT EXISTS (SELECT 1 FROM public.skill_notifications", a -> Optional.ofNullable(notices.get(n(a.get(2))))
                .filter(x -> x[0] == n(a.get(0)) && x[1] == n(a.get(1))).isPresent());
    }

    static Account student(long id, long college) {
        return new Account(id, true, false, Set.of("STUDENT"), new CollegeScope(college, true));
    }

    static Offer offer(long id, long owner) {
        return new Offer(id, owner, "Asha Patel", "BCA · A", "Java", "PROGRAMMING", "", "BEGINNER", "", "BOTH", "ACTIVE",
                null, 0, owner == ME, null, List.of());
    }

    static Request request(long id, long provider, long learner, String status) {
        return new Request(id, A_OFFER, "Java", provider, "Asha Patel", learner, "Riya Shah", null, null, "", status, NOW,
                null, provider == ME, provider == ME ? learner : provider, "Asha Patel", false, 0);
    }

    static Session session(long id) {
        return new Session(id, A_REQUEST, "Java", MATE, "Asha Patel", ME, "Riya Shah", NOW.plusDays(1),
                NOW.plusDays(1).plusHours(1), "ONLINE", "", "REQUESTED", false, MATE, "Asha Patel", false, false, false, null, null);
    }

    interface Action { void run(); }

    static void assertError(ApiError expected, Action action) {
        var failure = assertThrows(ApiError.Failure.class, action::run);
        assertEquals(expected, failure.error());
    }

    static SessionInput future(boolean teach) {
        return new SessionInput(teach, NOW.plusDays(1), NOW.plusDays(1).plusHours(1), "ONLINE", "Library");
    }

    // ---------------------------------------------------------------------------------------------------------
    // College isolation (negative tests)
    // ---------------------------------------------------------------------------------------------------------

    @Test void aStudentOfCollegeACannotSeeOrUseAnythingOfCollegeB() {
        var me = student(ME, A);
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.offer(me, B_OFFER));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.createRequest(me, new RequestInput(B_OFFER, null, "Hi")));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.createRequest(me, new RequestInput(A_OFFER, B_OFFER, "Hi")));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.request(me, B_REQUEST));
        for (String action : List.of("accept", "reject", "cancel")) {
            assertError(ApiError.SKILL_NOT_FOUND, () -> skills.decide(me, B_REQUEST, action));
        }
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.propose(me, B_REQUEST, future(false)));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.session(me, B_SESSION));
        for (String action : List.of("confirm", "cancel", "complete")) {
            assertError(ApiError.SKILL_NOT_FOUND, () -> skills.act(me, B_SESSION, action, new CancelInput("")));
        }
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.review(me, B_SESSION, new ReviewInput(5, "Great")));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.reviews(me, STRANGER));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.updateOffer(me, B_OFFER, validOffer()));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.setOfferStatus(me, B_OFFER, "pause"));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.setOfferStatus(me, B_OFFER, "archive"));
        assertError(ApiError.NOTIFICATION_NOT_FOUND, () -> skills.markRead(me, B_NOTICE));
        // Nothing was written for college B, and no statement ever named college B.
        assertFalse(db.ran("INSERT INTO"));
        assertFalse(db.ran("UPDATE public.skill_requests") || db.ran("UPDATE public.skill_sessions") || db.ran("UPDATE public.skill_offers"));
        db.calls.forEach(c -> assertFalse(c.args().contains(B), c.sql()));
    }

    @Test void aStudentOfCollegeBCannotSeeOrUseAnythingOfCollegeA() {
        var stranger = student(STRANGER, B);
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.offer(stranger, A_OFFER));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.createRequest(stranger, new RequestInput(A_OFFER, null, "")));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.request(stranger, A_REQUEST));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.decide(stranger, A_REQUEST, "cancel"));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.propose(stranger, A_EXCHANGE, future(true)));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.session(stranger, A_SESSION));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.act(stranger, A_SESSION, "cancel", null));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.review(stranger, A_SESSION, new ReviewInput(1, "")));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.reviews(stranger, ME));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.reviews(stranger, MATE));
        assertError(ApiError.NOTIFICATION_NOT_FOUND, () -> skills.markRead(stranger, A_NOTICE));
        assertFalse(db.ran("INSERT INTO"));
        db.calls.forEach(c -> assertFalse(c.args().contains(A), c.sql()));
    }

    @Test void aStudentWithoutAProfileInTheCollegeOfTheirTokenIsRefused() {
        // A college-B student whose account somehow named college A: there is no student profile in A, so nothing runs.
        var crafted = student(STRANGER, A);
        assertError(ApiError.ACCESS_DENIED, () -> skills.browse(crafted, new Filter(null, null, null, null, 0)));
        assertError(ApiError.ACCESS_DENIED, () -> skills.offer(crafted, A_OFFER));
        assertError(ApiError.ACCESS_DENIED, () -> skills.createRequest(crafted, new RequestInput(A_OFFER, null, "")));
        assertError(ApiError.ACCESS_DENIED, () -> skills.reviews(crafted, ME));
        assertEquals(4, db.calls.size(), "only the profile check ran each time");
        db.calls.forEach(c -> assertTrue(c.sql().contains("student_profiles"), c.sql()));
    }

    @Test void nonStudentsAndStudentsWithoutACollegeAreRefused() {
        for (Account outsider : List.of(
                new Account(ME, true, false, Set.of("FACULTY"), new CollegeScope(A, true)),
                new Account(ME, true, false, Set.of("COLLEGE_ADMIN"), new CollegeScope(A, true)),
                new Account(ME, true, false, Set.of("SUPER_ADMIN"), null),
                new Account(ME, true, false, Set.of("STUDENT"), null),
                new Account(ME, true, false, Set.of("STUDENT"), new CollegeScope(A, false)))) {
            assertError(ApiError.ACCESS_DENIED, () -> skills.browse(outsider, new Filter(null, null, null, null, 0)));
            assertError(ApiError.ACCESS_DENIED, () -> skills.summary(outsider));
            assertError(ApiError.ACCESS_DENIED, () -> skills.notices(outsider, 0));
        }
        assertTrue(db.calls.isEmpty());
    }

    @Test void everyStatementIsLimitedToTheStudentsOwnCollege() {
        var me = student(ME, A);
        skills.browse(me, new Filter("java", "PROGRAMMING", "BEGINNER", "ONLINE", 0));
        skills.myOffers(me);
        skills.offer(me, A_OFFER);
        skills.createOffer(me, validOffer());
        skills.createRequest(me, new RequestInput(A_OFFER, MY_OFFER, "Hi"));
        skills.requests(me, Box.ALL, null, 0);
        skills.propose(me, A_REQUEST, future(false));
        skills.sessions(me, When.UPCOMING);
        skills.sessions(me, When.HISTORY);
        skills.reviews(me, MATE);
        skills.notices(me, 0);
        skills.unread(me);
        skills.markRead(me, A_NOTICE);
        skills.summary(me);
        assertFalse(db.calls.isEmpty());
        for (var call : db.calls) {
            assertTrue(call.sql().contains("college_id = ?")
                    || (call.sql().contains("INSERT INTO") && call.sql().contains("(college_id,") && A == n(call.args().get(0))), call.sql());
            assertTrue(call.args().contains(A), "college A is bound: " + call.sql());
            assertFalse(call.args().contains(B), call.sql());
        }
        // Market search never shows the student's own offers or inactive ones, and pages are bounded.
        var market = db.calls("o.status = 'ACTIVE'").get(0);
        assertTrue(market.sql().contains("o.student_id <> ?") && market.sql().contains("u.is_active IS TRUE"));
        assertEquals(List.of(ME, A, ME), market.args().subList(0, 3));
    }

    @Test void clientsCannotSendACollegeOrAnyOtherExtraField() {
        assertThrows(IllegalArgumentException.class, () -> new RequestInput(A_OFFER, null, "").rejectUnknownField("collegeId", B));
        assertThrows(IllegalArgumentException.class, () -> new RequestInput(A_OFFER, null, "").rejectUnknownField("learnerId", ME));
        assertThrows(IllegalArgumentException.class, () -> validOffer().rejectUnknownField("studentId", STRANGER));
        assertThrows(IllegalArgumentException.class, () -> future(true).rejectUnknownField("collegeId", B));
        assertThrows(IllegalArgumentException.class, () -> new ReviewInput(5, "").rejectUnknownField("revieweeId", MATE));
        assertThrows(IllegalArgumentException.class, () -> new CancelInput("").rejectUnknownField("collegeId", B));
    }

    @Test void remindersStayInsideEachSessionsCollege() {
        db.on("INSERT INTO public.skill_notifications (college_id, student_id, kind, event_key, title, body, session_id)", 2);
        assertEquals(4, skills.sendReminders());
        var reminders = db.calls("'SESSION_REMINDER'");
        assertEquals(2, reminders.size());
        for (var r : reminders) {
            assertTrue(r.sql().contains("SELECT s.college_id, p.student_id"));
            assertTrue(r.sql().contains("o.college_id = s.college_id"));
            assertTrue(r.sql().contains("(VALUES (s.teacher_id), (s.pupil_id))"), "only the session's two students");
        }
        assertEquals(List.of("reminder24:", "Session within a day", "1 hour", "24 hours"), reminders.get(0).args());
        assertEquals(List.of("reminder1:", "Session within the hour", "0 minutes", "1 hour"), reminders.get(1).args());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Rules
    // ---------------------------------------------------------------------------------------------------------

    static OfferInput validOffer() {
        return new OfferInput(" Java basics ", "PROGRAMMING", " Loops ", "BEGINNER", "", "BOTH",
                List.of(new Slot(3, LocalTime.of(16, 0), LocalTime.of(17, 0)), new Slot(1, LocalTime.of(9, 0, 30), LocalTime.of(10, 0))));
    }

    @Test void offersAreCleanedAndChecked() {
        var clean = SkillExchange.cleanOffer(validOffer());
        assertEquals("Java basics", clean.name());
        assertEquals("Loops", clean.description());
        assertEquals(List.of(new Slot(1, LocalTime.of(9, 0), LocalTime.of(10, 0)), new Slot(3, LocalTime.of(16, 0), LocalTime.of(17, 0))),
                clean.slots());
        var slot = new Slot(1, LocalTime.of(9, 0), LocalTime.of(10, 0));
        for (OfferInput bad : List.of(
                new OfferInput("J", "PROGRAMMING", "", "BEGINNER", "", "BOTH", List.of()),
                new OfferInput("x".repeat(61), "PROGRAMMING", "", "BEGINNER", "", "BOTH", List.of()),
                new OfferInput("Java", "COOKING", "", "BEGINNER", "", "BOTH", List.of()),
                new OfferInput("Java", "PROGRAMMING", "", "EXPERT", "", "BOTH", List.of()),
                new OfferInput("Java", "PROGRAMMING", "", "BEGINNER", "", "POST", List.of()),
                new OfferInput("Java", "PROGRAMMING", "d".repeat(1001), "BEGINNER", "", "BOTH", List.of()),
                new OfferInput("Java", "PROGRAMMING", "", "BEGINNER", "m".repeat(301), "BOTH", List.of()),
                new OfferInput("Java", "PROGRAMMING", "", "BEGINNER", "", "BOTH", List.of(slot, slot)),
                new OfferInput("Java", "PROGRAMMING", "", "BEGINNER", "", "BOTH", List.of(new Slot(8, LocalTime.NOON, LocalTime.MIDNIGHT))),
                new OfferInput("Java", "PROGRAMMING", "", "BEGINNER", "", "BOTH", List.of(new Slot(2, LocalTime.NOON, LocalTime.NOON))),
                new OfferInput("Java", "PROGRAMMING", "", "BEGINNER", "", "BOTH", Collections.nCopies(15, slot)))) {
            assertError(ApiError.SKILL_INVALID, () -> SkillExchange.cleanOffer(bad));
        }
        assertError(ApiError.SKILL_INVALID, () -> SkillExchange.cleanOffer(null));
    }

    @Test void sessionsMustBeInTheFutureAndAtMostFourHours() {
        assertEquals("Library", SkillExchange.cleanSession(future(true), NOW).place());
        assertFalse(SkillExchange.cleanSession(new SessionInput(null, NOW.plusHours(1), NOW.plusHours(2), "IN_PERSON", null), NOW).teach());
        for (SessionInput bad : List.of(
                new SessionInput(true, NOW.minusMinutes(1), NOW.plusHours(1), "ONLINE", ""),
                new SessionInput(true, NOW.plusHours(2), NOW.plusHours(1), "ONLINE", ""),
                new SessionInput(true, NOW.plusHours(1), NOW.plusHours(5).plusMinutes(1), "ONLINE", ""),
                new SessionInput(true, NOW.plusDays(181), NOW.plusDays(181).plusHours(1), "ONLINE", ""),
                new SessionInput(true, NOW.plusHours(1), NOW.plusHours(2), "BOTH", ""),
                new SessionInput(true, null, NOW.plusHours(2), "ONLINE", ""))) {
            assertError(ApiError.SKILL_INVALID, () -> SkillExchange.cleanSession(bad, NOW));
        }
    }

    @Test void onlyTheProviderDecidesAndCancellingFollowsTheRules() {
        var requested = new RequestRow(1, MATE, ME, A_OFFER, null, "REQUESTED");
        assertEquals("ACCEPTED", SkillExchange.nextRequestStatus(requested, "accept", MATE));
        assertEquals("REJECTED", SkillExchange.nextRequestStatus(requested, "reject", MATE));
        assertEquals("CANCELLED", SkillExchange.nextRequestStatus(requested, "cancel", ME));
        assertError(ApiError.SKILL_STATE_CONFLICT, () -> SkillExchange.nextRequestStatus(requested, "accept", ME));
        assertError(ApiError.SKILL_STATE_CONFLICT, () -> SkillExchange.nextRequestStatus(requested, "reject", ME));
        assertError(ApiError.SKILL_STATE_CONFLICT, () -> SkillExchange.nextRequestStatus(requested, "cancel", MATE));
        var accepted = new RequestRow(1, MATE, ME, A_OFFER, null, "ACCEPTED");
        assertEquals("CANCELLED", SkillExchange.nextRequestStatus(accepted, "cancel", MATE));
        assertEquals("CANCELLED", SkillExchange.nextRequestStatus(accepted, "cancel", ME));
        assertError(ApiError.SKILL_STATE_CONFLICT, () -> SkillExchange.nextRequestStatus(accepted, "accept", MATE));
        for (String closed : List.of("REJECTED", "CANCELLED")) {
            var row = new RequestRow(1, MATE, ME, A_OFFER, null, closed);
            for (String action : List.of("accept", "reject", "cancel")) {
                assertError(ApiError.SKILL_STATE_CONFLICT, () -> SkillExchange.nextRequestStatus(row, action, MATE));
                assertError(ApiError.SKILL_STATE_CONFLICT, () -> SkillExchange.nextRequestStatus(row, action, ME));
            }
        }
        assertError(ApiError.SKILL_INVALID, () -> SkillExchange.nextRequestStatus(requested, "delete", MATE));
    }

    @Test void theLearnerOnlyTeachesBackInAMutualExchange() {
        var oneWay = new RequestRow(1, MATE, ME, A_OFFER, null, "ACCEPTED");
        assertEquals(A_OFFER, SkillExchange.taughtOffer(oneWay, MATE));
        assertError(ApiError.SKILL_INVALID, () -> SkillExchange.taughtOffer(oneWay, ME));
        var exchange = new RequestRow(1, MATE, ME, A_OFFER, MY_OFFER, "ACCEPTED");
        assertEquals(MY_OFFER, SkillExchange.taughtOffer(exchange, ME));
        assertEquals(A_OFFER, SkillExchange.taughtOffer(exchange, MATE));
        assertError(ApiError.SKILL_INVALID, () -> SkillExchange.taughtOffer(exchange, STRANGER));
    }

    @Test void ratingsAreOneToFive() {
        assertEquals(4, SkillExchange.cleanRating(new ReviewInput(4, "")));
        for (Integer bad : Arrays.asList(0, 6, -1, null)) {
            assertError(ApiError.SKILL_INVALID, () -> SkillExchange.cleanRating(new ReviewInput(bad, "")));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Flows inside one college
    // ---------------------------------------------------------------------------------------------------------

    @Test void askingForAClassmatesSkillNotifiesOnlyThatClassmate() {
        var me = student(ME, A);
        var created = skills.createRequest(me, new RequestInput(A_OFFER, MY_OFFER, " Please "));
        assertEquals(210L, created.id());
        var insert = db.calls("INSERT INTO public.skill_requests").get(0);
        assertEquals(List.of(A, A_OFFER, MATE, ME, MY_OFFER, "Please"), insert.args());
        var notice = db.calls("INSERT INTO public.skill_notifications").get(0);
        assertEquals(List.of(A, MATE, "REQUEST_NEW", "request:210:new"), notice.args().subList(0, 4));
    }

    @Test void studentsCannotRequestTheirOwnSkillOrSomeoneElsesExchangeOffer() {
        var me = student(ME, A);
        assertError(ApiError.SKILL_INVALID, () -> skills.createRequest(me, new RequestInput(MY_OFFER, null, "")));
        // The exchange offer must be the learner's own.
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.createRequest(me, new RequestInput(A_OFFER, A_OFFER, "")));
        assertError(ApiError.SKILL_INVALID, () -> skills.createRequest(me, new RequestInput(null, null, "")));
        assertError(ApiError.SKILL_INVALID, () -> skills.createRequest(me, new RequestInput(A_OFFER, null, "m".repeat(501))));
        db.answerFirst("INSERT INTO public.skill_requests", a -> { throw new DuplicateKeyException("uq_skill_request_open"); });
        assertError(ApiError.SKILL_DUPLICATE, () -> skills.createRequest(me, new RequestInput(A_OFFER, null, "")));
    }

    @Test void aOneWayLearnerCannotTeachButAMutualExchangeCan() {
        var me = student(ME, A);
        assertError(ApiError.SKILL_INVALID, () -> skills.propose(me, A_REQUEST, future(true)));
        skills.propose(me, A_REQUEST, future(false));
        var oneWay = db.calls("INSERT INTO public.skill_sessions").get(0);
        assertEquals(List.of(A, A_REQUEST, MATE, ME, A_OFFER, MATE, ME), oneWay.args().subList(0, 7));
        skills.propose(me, A_EXCHANGE, future(true));
        var exchange = db.calls("INSERT INTO public.skill_sessions").get(1);
        assertEquals(List.of(A, A_EXCHANGE, MATE, ME, MY_OFFER, ME, MATE), exchange.args().subList(0, 7));
        // The other partner is told about it.
        assertTrue(db.calls("INSERT INTO public.skill_notifications").stream().allMatch(c -> c.args().get(1).equals(MATE)));
    }

    @Test void sessionsNeedAnAcceptedRequest() {
        requests.put(A_REQUEST, new RequestRow(A_REQUEST, MATE, ME, A_OFFER, null, "REQUESTED"));
        assertError(ApiError.SKILL_STATE_CONFLICT, () -> skills.propose(student(ME, A), A_REQUEST, future(false)));
    }

    @Test void sessionStepsFollowTheRules() {
        var me = student(ME, A);
        // The proposer cannot confirm their own proposal; the partner can.
        sessions.put(A_SESSION, new SessionRow(A_SESSION, MATE, ME, ME, NOW.plusDays(1), NOW.plusDays(1).plusHours(1), "REQUESTED"));
        assertError(ApiError.SKILL_STATE_CONFLICT, () -> skills.act(me, A_SESSION, "confirm", null));
        assertError(ApiError.SKILL_STATE_CONFLICT, () -> skills.act(me, A_SESSION, "complete", null));
        skills.act(student(MATE, A), A_SESSION, "confirm", null);
        assertTrue(db.ran("SET status = 'SCHEDULED'"));
        // A scheduled session cannot be completed before it ends.
        sessions.put(A_SESSION, new SessionRow(A_SESSION, MATE, ME, ME, NOW.plusDays(1), NOW.plusDays(1).plusHours(1), "SCHEDULED"));
        assertError(ApiError.SKILL_STATE_CONFLICT, () -> skills.act(me, A_SESSION, "complete", null));
        sessions.put(A_SESSION, new SessionRow(A_SESSION, MATE, ME, ME, NOW.minusHours(2), NOW.minusHours(1), "SCHEDULED"));
        skills.act(me, A_SESSION, "complete", null);
        assertTrue(db.ran("SET status = 'COMPLETED'"));
        // A completed session cannot be cancelled; an unknown step is refused.
        sessions.put(A_SESSION, new SessionRow(A_SESSION, MATE, ME, ME, NOW.minusHours(2), NOW.minusHours(1), "COMPLETED"));
        assertError(ApiError.SKILL_STATE_CONFLICT, () -> skills.act(me, A_SESSION, "cancel", new CancelInput("")));
        assertError(ApiError.SKILL_INVALID, () -> skills.act(me, A_SESSION, "restart", null));
    }

    @Test void reviewsAreOncePerCompletedSessionAboutThePartner() {
        var me = student(ME, A);
        skills.review(me, A_SESSION, new ReviewInput(5, " Clear and patient "));
        var insert = db.calls("INSERT INTO public.skill_reviews").get(0);
        assertEquals(List.of(A, A_SESSION, ME, MATE, 5, "Clear and patient"), insert.args());
        db.answerFirst("INSERT INTO public.skill_reviews", a -> { throw new DuplicateKeyException("uq_skill_review_once"); });
        assertError(ApiError.SKILL_REVIEW_EXISTS, () -> skills.review(me, A_SESSION, new ReviewInput(4, "")));
        sessions.put(A_SESSION, new SessionRow(A_SESSION, MATE, ME, ME, NOW.plusDays(1), NOW.plusDays(1).plusHours(1), "SCHEDULED"));
        assertError(ApiError.SKILL_STATE_CONFLICT, () -> skills.review(me, A_SESSION, new ReviewInput(4, "")));
        assertError(ApiError.SKILL_INVALID, () -> skills.review(me, A_SESSION, new ReviewInput(9, "")));
    }

    @Test void onlyTheOwnerChangesAnOffer() {
        var mate = student(MATE, A);
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.updateOffer(mate, MY_OFFER, validOffer()));
        assertError(ApiError.SKILL_NOT_FOUND, () -> skills.setOfferStatus(mate, MY_OFFER, "archive"));
        var me = student(ME, A);
        assertError(ApiError.SKILL_STATE_CONFLICT, () -> skills.setOfferStatus(me, MY_OFFER, "resume"));
        assertNull(skills.setOfferStatus(me, MY_OFFER, "archive"));
        assertEquals(List.of("ARCHIVED", A, MY_OFFER), db.calls("UPDATE public.skill_offers SET status").get(0).args());
    }

    @Test void aStudentHasAtMostFifteenLiveOffers() {
        db.on("SELECT count(*) FROM public.skill_offers WHERE college_id = ? AND student_id = ? AND status <> 'ARCHIVED'", 15L);
        assertError(ApiError.SKILL_STATE_CONFLICT, () -> skills.createOffer(student(ME, A), validOffer()));
        assertFalse(db.ran("INSERT INTO public.skill_offers"));
    }
}
