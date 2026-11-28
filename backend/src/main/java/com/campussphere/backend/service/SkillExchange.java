package com.campussphere.backend.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skill Exchange, strictly inside one college. The college always comes from the signed-in student's account;
 * every query is limited to it, so an offer, request, session, review or notice of another college is simply
 * "not found". Students only (faculty, College Admins and platform roles have no route here). The database
 * enforces the same isolation through college-scoped foreign keys.
 */
@Service
@Transactional
public class SkillExchange {
    static final int PAGE = 30;
    static final int MAX_OFFERS = 15;
    static final int MAX_SLOTS = 14;
    static final Set<String> CATEGORIES = Set.of("PROGRAMMING", "DESIGN", "LANGUAGES", "MATHEMATICS", "SCIENCE",
            "BUSINESS", "ARTS", "MUSIC", "SPORTS", "ACADEMICS", "OTHER");
    static final Set<String> LEVELS = Set.of("BEGINNER", "INTERMEDIATE", "ADVANCED");
    static final Set<String> OFFER_MODES = Set.of("ONLINE", "IN_PERSON", "BOTH");
    static final Set<String> SESSION_MODES = Set.of("ONLINE", "IN_PERSON");

    // ---- Shapes ----

    public record Slot(int day, LocalTime start, LocalTime end) {}
    /** audience is "CLASS" (only the student's own class) or "COLLEGE" (every student of the college); null = COLLEGE. */
    public record OfferInput(String name, String category, String description, String level, String teachingMethod,
                             String mode, List<Slot> slots, String audience) {
        public OfferInput(String name, String category, String description, String level, String teachingMethod,
                          String mode, List<Slot> slots) {
            this(name, category, description, level, teachingMethod, mode, slots, null);
        }
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    public record Offer(long id, long providerId, String provider, String classLabel, String name, String category,
                        String description, String level, String teachingMethod, String mode, String status,
                        Double rating, long reviews, boolean mine, String myRequest, List<Slot> slots, String audience) {}
    static final Set<String> AUDIENCES = Set.of("CLASS", "COLLEGE");
    public record Filter(String q, String category, String level, String mode, int page) {
        public Filter {
            q = q == null || q.isBlank() ? null : q.strip();
            if ((q != null && q.length() > 60) || (category != null && !CATEGORIES.contains(category))
                    || (level != null && !LEVELS.contains(level)) || (mode != null && !SESSION_MODES.contains(mode))
                    || page < 0 || page > 1000) throw ApiError.SKILL_INVALID.exception();
        }
    }
    public record RequestInput(Long offerId, Long exchangeOfferId, String message) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    /** A request comes from an offered skill (offerId) or from an accepted "I want to learn" post (wantId). */
    public record Request(long id, Long offerId, String offerName, long providerId, String provider, long learnerId,
                          String learner, Long exchangeOfferId, String exchangeOfferName, String message, String status,
                          OffsetDateTime createdAt, OffsetDateTime decidedAt, boolean incoming, long partnerId,
                          String partner, boolean exchange, long completedSessions, Long wantId, Long groupId) {}
    public record SessionInput(Boolean teach, OffsetDateTime startsAt, OffsetDateTime endsAt, String mode, String place) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    public record Session(long id, long requestId, String skill, long teacherId, String teacher, long pupilId, String pupil,
                          OffsetDateTime startsAt, OffsetDateTime endsAt, String mode, String place, String status,
                          boolean iTeach, long partnerId, String partner, boolean needsMyConfirmation,
                          boolean canComplete, boolean canReview, Integer myRating, String cancelReason) {}
    public record CancelInput(String reason) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    public record ReviewInput(Integer rating, String comment) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    public record Review(long id, int rating, String comment, String reviewer, String skill, OffsetDateTime createdAt) {}
    public record Notice(long id, String kind, String title, String body, Long requestId, Long sessionId,
                         OffsetDateTime startsAt, OffsetDateTime createdAt, OffsetDateTime readAt, Long wantId, Long groupId,
                         Long groupSessionId, Long facultyRequestId) {}
    public record Summary(long myOffers, long incomingRequests, long outgoingRequests, long upcomingSessions,
                          Session nextSession, long unreadNotices, long openWants, long groups, long facultyRequests) {}
    public enum Box { INCOMING, OUTGOING, ALL }
    public enum When { UPCOMING, HISTORY }

    /** The stored request, for rules. */
    record RequestRow(long id, long providerId, long learnerId, Long offerId, Long exchangeOfferId, String status, Long wantId) {
        RequestRow(long id, long providerId, long learnerId, Long offerId, Long exchangeOfferId, String status) {
            this(id, providerId, learnerId, offerId, exchangeOfferId, status, null);
        }
    }
    /** The stored session, for rules. */
    record SessionRow(long id, long teacherId, long pupilId, long proposedBy, OffsetDateTime startsAt,
                      OffsetDateTime endsAt, String status) {}

    private final JdbcTemplate db;
    private final Clock clock;
    private final SkillNotices notices;
    private final SkillGroupMaker groups;

    @Autowired
    public SkillExchange(JdbcTemplate db, SkillNotices notices, SkillGroupMaker groups) {
        this(db, Clock.systemUTC(), notices, groups);
    }

    /** For tests: no live chat updates. */
    SkillExchange(JdbcTemplate db, Clock clock) {
        this(db, clock, new SkillNotices(db), new SkillGroupMaker(db, null, new SkillNotices(db)));
    }

    SkillExchange(JdbcTemplate db, Clock clock, SkillNotices notices, SkillGroupMaker groups) {
        this.db = db;
        this.clock = clock;
        this.notices = notices;
        this.groups = groups;
    }

    OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /** The student's current class (division), or null when not enrolled. Never taken from the app. */
    Long division(long college, long student) {
        return db.queryForList("""
                SELECT division_id FROM public.student_enrollments WHERE college_id = ? AND student_id = ? AND ended_at IS NULL
                """, Long.class, college, student).stream().findFirst().orElse(null);
    }

    /** The class to store for an audience: the student's own class for CLASS (refused when not in a class), else null. */
    Long audienceClass(long college, long student, String audience) {
        if (!"CLASS".equals(audience)) return null;
        Long division = division(college, student);
        if (division == null) throw ApiError.SKILL_NO_CLASS.exception();
        return division;
    }

    static String cleanAudience(String audience) {
        String value = audience == null ? "COLLEGE" : audience;
        if (!AUDIENCES.contains(value)) throw ApiError.SKILL_INVALID.exception();
        return value;
    }

    static String text(String value, int max, boolean required, ApiError error) {
        String clean = value == null ? "" : value.strip();
        if (clean.length() > max || (required && clean.isEmpty())) throw error.exception();
        return clean;
    }

    /** The signed-in student's college, after checking they have a student profile there. */
    long college(Account actor) {
        long college = actor.requireCollege(CollegeRole.STUDENT, null);
        if (!Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.student_profiles WHERE college_id = ? AND student_id = ?)
                """, Boolean.class, college, actor.id()))) throw ApiError.ACCESS_DENIED.exception();
        return college;
    }

    // ---- Rules (no database) ----

    private static String text(String value, int max, boolean required) {
        String clean = value == null ? "" : value.strip();
        if (clean.length() > max || (required && clean.isEmpty())) throw ApiError.SKILL_INVALID.exception();
        return clean;
    }

    static OfferInput cleanOffer(OfferInput in) {
        if (in == null) throw ApiError.SKILL_INVALID.exception();
        String name = text(in.name(), 60, true);
        if (name.length() < 2 || !CATEGORIES.contains(in.category()) || !LEVELS.contains(in.level())
                || !OFFER_MODES.contains(in.mode())) throw ApiError.SKILL_INVALID.exception();
        List<Slot> slots = in.slots() == null ? List.of() : in.slots();
        if (slots.size() > MAX_SLOTS) throw ApiError.SKILL_INVALID.exception();
        var seen = new HashSet<String>();
        var clean = new ArrayList<Slot>();
        for (Slot s : slots) {
            if (s == null || s.day() < 1 || s.day() > 7 || s.start() == null || s.end() == null || !s.end().isAfter(s.start())
                    || !seen.add(s.day() + "@" + s.start())) throw ApiError.SKILL_INVALID.exception();
            clean.add(new Slot(s.day(), s.start().withSecond(0).withNano(0), s.end().withSecond(0).withNano(0)));
        }
        clean.sort(Comparator.comparingInt(Slot::day).thenComparing(Slot::start));
        return new OfferInput(name, in.category(), text(in.description(), 1000, false), in.level(),
                text(in.teachingMethod(), 300, false), in.mode(), List.copyOf(clean), cleanAudience(in.audience()));
    }

    static SessionInput cleanSession(SessionInput in, OffsetDateTime now) {
        if (in == null || in.startsAt() == null || in.endsAt() == null || !SESSION_MODES.contains(in.mode()))
            throw ApiError.SKILL_INVALID.exception();
        if (!in.startsAt().isAfter(now) || !in.endsAt().isAfter(in.startsAt())
                || Duration.between(in.startsAt(), in.endsAt()).toMinutes() > 240
                || in.startsAt().isAfter(now.plusDays(180))) throw ApiError.SKILL_INVALID.exception();
        return new SessionInput(Boolean.TRUE.equals(in.teach()), in.startsAt(), in.endsAt(), in.mode(), text(in.place(), 300, false));
    }

    /** The status a request moves to when [me] takes [action]; refuses anything the rules do not allow. */
    static String nextRequestStatus(RequestRow r, String action, long me) {
        boolean provider = r.providerId() == me;
        return switch (action) {
            case "accept" -> { if (!provider || !r.status().equals("REQUESTED")) throw ApiError.SKILL_STATE_CONFLICT.exception(); yield "ACCEPTED"; }
            case "reject" -> { if (!provider || !r.status().equals("REQUESTED")) throw ApiError.SKILL_STATE_CONFLICT.exception(); yield "REJECTED"; }
            case "cancel" -> {
                boolean open = r.status().equals("REQUESTED") ? !provider : r.status().equals("ACCEPTED");
                if (!open) throw ApiError.SKILL_STATE_CONFLICT.exception();
                yield "CANCELLED";
            }
            default -> throw ApiError.SKILL_INVALID.exception();
        };
    }

    /** Which offer a session teaches: the provider teaches the requested offer; the learner only their exchange offer. */
    static Long taughtOffer(RequestRow r, long teacher) {
        if (teacher == r.providerId()) return r.offerId();
        if (teacher == r.learnerId() && r.exchangeOfferId() != null) return r.exchangeOfferId();
        throw ApiError.SKILL_INVALID.exception();
    }

    static int cleanRating(ReviewInput in) {
        if (in == null || in.rating() == null || in.rating() < 1 || in.rating() > 5) throw ApiError.SKILL_INVALID.exception();
        return in.rating();
    }

    // ---- Offers ----

    private static final String OFFER_SELECT = """
            SELECT o.id, o.student_id, u.full_name,
                   (SELECT p.name || ' · ' || d.name FROM public.student_enrollments e
                    JOIN public.academic_programs p ON p.college_id = e.college_id AND p.id = e.program_id
                    JOIN public.academic_divisions d ON d.college_id = e.college_id AND d.id = e.division_id
                    WHERE e.college_id = o.college_id AND e.student_id = o.student_id AND e.ended_at IS NULL LIMIT 1) class_label,
                   o.name, o.category, o.description, o.level, o.teaching_method, o.mode, o.status, o.audience,
                   (SELECT avg(r.rating) FROM public.skill_reviews r
                    JOIN public.skill_sessions s ON s.college_id = r.college_id AND s.id = r.session_id
                    WHERE s.college_id = o.college_id AND s.offer_id = o.id AND r.reviewee_id = s.teacher_id) rating,
                   (SELECT count(*) FROM public.skill_reviews r
                    JOIN public.skill_sessions s ON s.college_id = r.college_id AND s.id = r.session_id
                    WHERE s.college_id = o.college_id AND s.offer_id = o.id AND r.reviewee_id = s.teacher_id) reviews,
                   (SELECT q.status FROM public.skill_requests q WHERE q.college_id = o.college_id AND q.offer_id = o.id
                      AND q.learner_id = ? AND q.status IN ('REQUESTED', 'ACCEPTED') LIMIT 1) my_request
            FROM public.skill_offers o JOIN public.users u ON u.id = o.student_id
            WHERE o.college_id = ?""";

    /** Active offers of other students in the signed-in student's college: college-wide ones and those of their own class. */
    public List<Offer> browse(Account actor, Filter f) {
        long college = college(actor);
        Long division = division(college, actor.id());
        var sql = new StringBuilder(OFFER_SELECT).append(" AND o.status = 'ACTIVE' AND u.is_active IS TRUE AND o.student_id <> ?")
                .append(" AND (o.audience = 'COLLEGE' OR o.division_id = ?)");
        var args = new ArrayList<Object>(Arrays.asList(actor.id(), college, actor.id(), division));
        if (f.q() != null) {
            sql.append(" AND (strpos(lower(o.name), lower(?)) > 0 OR strpos(lower(o.description), lower(?)) > 0)");
            args.add(f.q()); args.add(f.q());
        }
        if (f.category() != null) { sql.append(" AND o.category = ?"); args.add(f.category()); }
        if (f.level() != null) { sql.append(" AND o.level = ?"); args.add(f.level()); }
        if (f.mode() != null) { sql.append(" AND o.mode IN (?, 'BOTH')"); args.add(f.mode()); }
        sql.append(" ORDER BY o.updated_at DESC, o.id DESC LIMIT ? OFFSET ?");
        args.add(PAGE); args.add(f.page() * PAGE);
        return withSlots(college, db.query(sql.toString(), (r, i) -> offer(r, actor.id(), List.of()), args.toArray()));
    }

    public List<Offer> myOffers(Account actor) {
        long college = college(actor);
        return withSlots(college, db.query(OFFER_SELECT + " AND o.student_id = ? AND o.status <> 'ARCHIVED' ORDER BY lower(o.name), o.id",
                (r, i) -> offer(r, actor.id(), List.of()), actor.id(), college, actor.id()));
    }

    /** One offer: an active offer the student may see (college-wide, or of their class), or their own in any state. */
    public Offer offer(Account actor, long id) {
        long college = college(actor);
        Long division = division(college, actor.id());
        var found = db.query(OFFER_SELECT + " AND o.id = ? AND (o.student_id = ? OR (o.status = 'ACTIVE' AND (o.audience = 'COLLEGE' OR o.division_id = ?)))",
                (r, i) -> offer(r, actor.id(), List.of()), actor.id(), college, id, actor.id(), division);
        if (found.isEmpty()) throw ApiError.SKILL_NOT_FOUND.exception();
        return withSlots(college, found).get(0);
    }

    public Offer createOffer(Account actor, OfferInput input) {
        long college = college(actor);
        OfferInput in = cleanOffer(input);
        Long live = db.queryForObject("""
                SELECT count(*) FROM public.skill_offers WHERE college_id = ? AND student_id = ? AND status <> 'ARCHIVED'
                """, Long.class, college, actor.id());
        if (live != null && live >= MAX_OFFERS) throw ApiError.SKILL_STATE_CONFLICT.exception();
        Long division = audienceClass(college, actor.id(), in.audience());
        Long id;
        try {
            id = db.queryForObject("""
                    INSERT INTO public.skill_offers (college_id, student_id, name, category, description, level,
                        teaching_method, mode, audience, division_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, college, actor.id(), in.name(), in.category(), in.description(), in.level(),
                    in.teachingMethod(), in.mode(), in.audience(), division);
        } catch (DuplicateKeyException duplicate) {
            throw ApiError.SKILL_DUPLICATE.exception();
        }
        saveSlots(college, id == null ? 0 : id, in.slots());
        return offer(actor, id == null ? 0 : id);
    }

    public Offer updateOffer(Account actor, long id, OfferInput input) {
        long college = college(actor);
        OfferInput in = cleanOffer(input);
        ownOffer(college, actor.id(), id);
        Long division = audienceClass(college, actor.id(), in.audience());
        try {
            db.update("""
                    UPDATE public.skill_offers SET name = ?, category = ?, description = ?, level = ?, teaching_method = ?,
                        mode = ?, audience = ?, division_id = ?, updated_at = statement_timestamp()
                    WHERE college_id = ? AND id = ? AND student_id = ?
                    """, in.name(), in.category(), in.description(), in.level(), in.teachingMethod(), in.mode(),
                    in.audience(), division, college, id, actor.id());
        } catch (DuplicateKeyException duplicate) {
            throw ApiError.SKILL_DUPLICATE.exception();
        }
        db.update("DELETE FROM public.skill_offer_slots WHERE college_id = ? AND offer_id = ?", college, id);
        saveSlots(college, id, in.slots());
        return offer(actor, id);
    }

    /** action is "pause", "resume" or "archive". */
    public Offer setOfferStatus(Account actor, long id, String action) {
        long college = college(actor);
        String status = ownOffer(college, actor.id(), id);
        String next = switch (action) {
            case "pause" -> status.equals("ACTIVE") ? "PAUSED" : null;
            case "resume" -> status.equals("PAUSED") ? "ACTIVE" : null;
            case "archive" -> "ARCHIVED";
            default -> throw ApiError.SKILL_INVALID.exception();
        };
        if (next == null) throw ApiError.SKILL_STATE_CONFLICT.exception();
        db.update("UPDATE public.skill_offers SET status = ?, updated_at = statement_timestamp() WHERE college_id = ? AND id = ?",
                next, college, id);
        if (next.equals("ARCHIVED")) return null;
        return offer(actor, id);
    }

    private String ownOffer(long college, long me, long id) {
        return db.query("""
                SELECT status FROM public.skill_offers
                WHERE college_id = ? AND id = ? AND student_id = ? AND status <> 'ARCHIVED' FOR UPDATE
                """, (r, i) -> r.getString(1), college, id, me).stream().findFirst()
                .orElseThrow(ApiError.SKILL_NOT_FOUND::exception);
    }

    private void saveSlots(long college, long offer, List<Slot> slots) {
        for (Slot s : slots) {
            db.update("INSERT INTO public.skill_offer_slots (college_id, offer_id, day_of_week, start_time, end_time) VALUES (?, ?, ?, ?, ?)",
                    college, offer, s.day(), s.start(), s.end());
        }
    }

    private List<Offer> withSlots(long college, List<Offer> offers) {
        if (offers.isEmpty()) return offers;
        String marks = String.join(",", Collections.nCopies(offers.size(), "?"));
        var args = new ArrayList<Object>();
        args.add(college);
        offers.forEach(o -> args.add(o.id()));
        var byOffer = new HashMap<Long, List<Slot>>();
        db.query("SELECT offer_id, day_of_week, start_time, end_time FROM public.skill_offer_slots WHERE college_id = ? AND offer_id IN ("
                + marks + ") ORDER BY day_of_week, start_time", (org.springframework.jdbc.core.RowCallbackHandler) r ->
                byOffer.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(new Slot(r.getInt(2),
                        r.getObject(3, LocalTime.class), r.getObject(4, LocalTime.class))), args.toArray());
        return offers.stream().map(o -> new Offer(o.id(), o.providerId(), o.provider(), o.classLabel(), o.name(), o.category(),
                o.description(), o.level(), o.teachingMethod(), o.mode(), o.status(), o.rating(), o.reviews(), o.mine(),
                o.myRequest(), byOffer.getOrDefault(o.id(), List.of()), o.audience())).toList();
    }

    private static Offer offer(ResultSet r, long me, List<Slot> slots) throws SQLException {
        Number rating = (Number) r.getObject("rating");
        return new Offer(r.getLong("id"), r.getLong("student_id"), r.getString("full_name"), r.getString("class_label"),
                r.getString("name"), r.getString("category"), r.getString("description"), r.getString("level"),
                r.getString("teaching_method"), r.getString("mode"), r.getString("status"),
                rating == null ? null : Math.round(rating.doubleValue() * 10) / 10.0, r.getLong("reviews"),
                r.getLong("student_id") == me, r.getString("my_request"), slots, r.getString("audience"));
    }

    // ---- Requests ----

    private static final String REQUEST_SELECT = """
            SELECT q.id, q.offer_id, coalesce(o.name, w.title) offer_name, q.provider_id, pu.full_name provider, q.learner_id,
                   lu.full_name learner, q.exchange_offer_id, xo.name exchange_name, q.message, q.status, q.created_at,
                   q.decided_at, q.want_id,
                   (SELECT count(*) FROM public.skill_sessions s WHERE s.college_id = q.college_id AND s.request_id = q.id
                      AND s.status = 'COMPLETED') completed,
                   (SELECT m.group_id FROM public.skill_group_members m JOIN public.skill_groups g
                      ON g.college_id = m.college_id AND g.id = m.group_id
                    WHERE m.college_id = q.college_id AND m.request_id = q.id AND m.left_at IS NULL AND g.status = 'ACTIVE'
                    LIMIT 1) group_id
            FROM public.skill_requests q
            LEFT JOIN public.skill_offers o ON o.college_id = q.college_id AND o.id = q.offer_id
            LEFT JOIN public.skill_wants w ON w.college_id = q.college_id AND w.id = q.want_id
            LEFT JOIN public.skill_offers xo ON xo.college_id = q.college_id AND xo.id = q.exchange_offer_id
            JOIN public.users pu ON pu.id = q.provider_id
            JOIN public.users lu ON lu.id = q.learner_id
            WHERE q.college_id = ? AND (q.provider_id = ? OR q.learner_id = ?)""";

    private static Request request(ResultSet r, long me) throws SQLException {
        long provider = r.getLong("provider_id");
        boolean incoming = provider == me;
        Number exchange = (Number) r.getObject("exchange_offer_id");
        return new Request(r.getLong("id"), (Long) r.getObject("offer_id", Long.class), r.getString("offer_name"), provider,
                r.getString("provider"), r.getLong("learner_id"), r.getString("learner"), exchange == null ? null : exchange.longValue(),
                r.getString("exchange_name"), r.getString("message"), r.getString("status"),
                r.getObject("created_at", OffsetDateTime.class), r.getObject("decided_at", OffsetDateTime.class), incoming,
                incoming ? r.getLong("learner_id") : provider, incoming ? r.getString("learner") : r.getString("provider"),
                exchange != null, r.getLong("completed"), (Long) r.getObject("want_id", Long.class),
                (Long) r.getObject("group_id", Long.class));
    }

    public List<Request> requests(Account actor, Box box, String status, int page) {
        long college = college(actor);
        if (page < 0 || page > 1000 || (status != null && !Set.of("REQUESTED", "ACCEPTED", "REJECTED", "CANCELLED").contains(status)))
            throw ApiError.SKILL_INVALID.exception();
        var sql = new StringBuilder(REQUEST_SELECT);
        var args = new ArrayList<Object>(List.of(college, actor.id(), actor.id()));
        if (box == Box.INCOMING) { sql.append(" AND q.provider_id = ?"); args.add(actor.id()); }
        if (box == Box.OUTGOING) { sql.append(" AND q.learner_id = ?"); args.add(actor.id()); }
        if (status != null) { sql.append(" AND q.status = ?"); args.add(status); }
        sql.append(" ORDER BY q.id DESC LIMIT ? OFFSET ?");
        args.add(PAGE); args.add(page * PAGE);
        return db.query(sql.toString(), (r, i) -> request(r, actor.id()), args.toArray());
    }

    public Request request(Account actor, long id) {
        long college = college(actor);
        return db.query(REQUEST_SELECT + " AND q.id = ?", (r, i) -> request(r, actor.id()), college, actor.id(), actor.id(), id)
                .stream().findFirst().orElseThrow(ApiError.SKILL_NOT_FOUND::exception);
    }

    /** Asks to learn an active offer of a classmate; with an exchange offer, the learner teaches that skill back. */
    public Request createRequest(Account actor, RequestInput input) {
        long college = college(actor);
        if (input == null || input.offerId() == null) throw ApiError.SKILL_INVALID.exception();
        String message = text(input.message(), 500, false);
        Long division = division(college, actor.id());
        long provider = db.query("SELECT student_id FROM public.skill_offers WHERE college_id = ? AND id = ? AND status = 'ACTIVE'"
                        + " AND (audience = 'COLLEGE' OR division_id = ?)",
                (r, i) -> r.getLong(1), college, input.offerId(), division).stream().findFirst()
                .orElseThrow(ApiError.SKILL_NOT_FOUND::exception);
        if (provider == actor.id()) throw ApiError.SKILL_INVALID.exception();
        if (input.exchangeOfferId() != null && !Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.skill_offers WHERE college_id = ? AND id = ? AND student_id = ? AND status = 'ACTIVE')
                """, Boolean.class, college, input.exchangeOfferId(), actor.id()))) throw ApiError.SKILL_NOT_FOUND.exception();
        Long id;
        try {
            id = db.queryForObject("""
                    INSERT INTO public.skill_requests (college_id, offer_id, provider_id, learner_id, exchange_offer_id, message)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, college, input.offerId(), provider, actor.id(), input.exchangeOfferId(), message);
        } catch (DuplicateKeyException duplicate) {
            throw ApiError.SKILL_DUPLICATE.exception();
        }
        Request created = request(actor, id == null ? 0 : id);
        notice(college, provider, "REQUEST_NEW", "request:" + created.id() + ":new", "New learning request",
                created.learner() + " wants to learn " + created.offerName() + (created.exchange()
                        ? " and offers " + created.exchangeOfferName() + " in exchange" : ""), created.id(), null);
        return created;
    }

    /** action is "accept" or "reject" (the provider) or "cancel" (either partner, by the rules). */
    public Request decide(Account actor, long id, String action) {
        long college = college(actor);
        RequestRow row = requestRow(college, actor.id(), id, true);
        String next = nextRequestStatus(row, action, actor.id());
        db.update("""
                UPDATE public.skill_requests SET status = ?, decided_at = coalesce(decided_at, statement_timestamp()),
                    cancelled_by = ? WHERE college_id = ? AND id = ?
                """, next, next.equals("CANCELLED") ? actor.id() : null, college, id);
        if (next.equals("CANCELLED")) db.update("""
                UPDATE public.skill_sessions SET status = 'CANCELLED', cancelled_by = ?, cancel_reason = 'The request was cancelled'
                WHERE college_id = ? AND request_id = ? AND status IN ('REQUESTED', 'SCHEDULED')
                """, actor.id(), college, id);
        // Learning together: an accepted learner joins (or forms) the group for that skill; a cancelled one leaves it.
        if (next.equals("ACCEPTED")) groups.afterAccepted(college, id);
        if (next.equals("CANCELLED")) groups.afterCancelled(college, id);
        Request updated = request(actor, id);
        long other = row.providerId() == actor.id() ? row.learnerId() : row.providerId();
        String me = row.providerId() == actor.id() ? updated.provider() : updated.learner();
        switch (next) {
            case "ACCEPTED" -> notice(college, other, "REQUEST_ACCEPTED", "request:" + id + ":accepted", "Request accepted",
                    me + " accepted your request to learn " + updated.offerName(), id, null);
            case "REJECTED" -> notice(college, other, "REQUEST_REJECTED", "request:" + id + ":rejected", "Request declined",
                    me + " declined your request to learn " + updated.offerName(), id, null);
            default -> notice(college, other, "REQUEST_CANCELLED", "request:" + id + ":cancelled", "Request cancelled",
                    me + " cancelled the request for " + updated.offerName(), id, null);
        }
        return updated;
    }

    private RequestRow requestRow(long college, long me, long id, boolean lock) {
        return db.query("""
                SELECT id, provider_id, learner_id, offer_id, exchange_offer_id, status, want_id FROM public.skill_requests
                WHERE college_id = ? AND id = ? AND (provider_id = ? OR learner_id = ?)""" + (lock ? " FOR UPDATE" : " FOR SHARE"),
                (r, i) -> new RequestRow(r.getLong("id"), r.getLong("provider_id"), r.getLong("learner_id"),
                        (Long) r.getObject("offer_id", Long.class), (Long) r.getObject("exchange_offer_id", Long.class),
                        r.getString("status"), (Long) r.getObject("want_id", Long.class)),
                college, id, me, me).stream().findFirst().orElseThrow(ApiError.SKILL_NOT_FOUND::exception);
    }

    // ---- Sessions ----

    // A session from a post has no offer; its name is the post's title.
    private static final String SESSION_SELECT = """
            SELECT s.id, s.request_id, coalesce(o.name, w.title) skill, s.teacher_id, tu.full_name teacher, s.pupil_id,
                   pu.full_name pupil, s.starts_at, s.ends_at, s.mode, s.place, s.status, s.proposed_by, s.cancel_reason,
                   (SELECT r.rating FROM public.skill_reviews r WHERE r.college_id = s.college_id AND r.session_id = s.id
                      AND r.reviewer_id = ?) my_rating
            FROM public.skill_sessions s
            LEFT JOIN public.skill_offers o ON o.college_id = s.college_id AND o.id = s.offer_id
            JOIN public.skill_requests sq ON sq.college_id = s.college_id AND sq.id = s.request_id
            LEFT JOIN public.skill_wants w ON w.college_id = sq.college_id AND w.id = sq.want_id
            JOIN public.users tu ON tu.id = s.teacher_id
            JOIN public.users pu ON pu.id = s.pupil_id
            WHERE s.college_id = ? AND (s.teacher_id = ? OR s.pupil_id = ?)""";

    private Session session(ResultSet r, long me) throws SQLException {
        OffsetDateTime ends = r.getObject("ends_at", OffsetDateTime.class);
        String status = r.getString("status");
        boolean iTeach = r.getLong("teacher_id") == me;
        Number rating = (Number) r.getObject("my_rating");
        return new Session(r.getLong("id"), r.getLong("request_id"), r.getString("skill"), r.getLong("teacher_id"),
                r.getString("teacher"), r.getLong("pupil_id"), r.getString("pupil"), r.getObject("starts_at", OffsetDateTime.class),
                ends, r.getString("mode"), r.getString("place"), status, iTeach,
                iTeach ? r.getLong("pupil_id") : r.getLong("teacher_id"), iTeach ? r.getString("pupil") : r.getString("teacher"),
                status.equals("REQUESTED") && r.getLong("proposed_by") != me,
                status.equals("SCHEDULED") && !now().isBefore(ends), status.equals("COMPLETED") && rating == null,
                rating == null ? null : rating.intValue(), r.getString("cancel_reason"));
    }

    public List<Session> sessions(Account actor, When when) {
        long college = college(actor);
        String filter = when == When.HISTORY
                ? " AND (s.status IN ('COMPLETED', 'CANCELLED') OR s.ends_at < ?) ORDER BY s.starts_at DESC, s.id DESC LIMIT 50"
                : " AND s.status IN ('REQUESTED', 'SCHEDULED') AND s.ends_at >= ? ORDER BY s.starts_at, s.id LIMIT 50";
        return db.query(SESSION_SELECT + filter, (r, i) -> session(r, actor.id()), actor.id(), college, actor.id(), actor.id(), now());
    }

    public Session session(Account actor, long id) {
        long college = college(actor);
        return db.query(SESSION_SELECT + " AND s.id = ?", (r, i) -> session(r, actor.id()), actor.id(), college,
                actor.id(), actor.id(), id).stream().findFirst().orElseThrow(ApiError.SKILL_NOT_FOUND::exception);
    }

    /** Proposes a session for an accepted request; [teach] says whether the proposer teaches or learns. */
    public Session propose(Account actor, long requestId, SessionInput input) {
        long college = college(actor);
        SessionInput in = cleanSession(input, now());
        RequestRow q = requestRow(college, actor.id(), requestId, false);
        if (!q.status().equals("ACCEPTED")) throw ApiError.SKILL_STATE_CONFLICT.exception();
        long other = q.providerId() == actor.id() ? q.learnerId() : q.providerId();
        long teacher = in.teach() ? actor.id() : other;
        long pupil = teacher == actor.id() ? other : actor.id();
        Long offer = taughtOffer(q, teacher);
        Long id = db.queryForObject("""
                INSERT INTO public.skill_sessions (college_id, request_id, provider_id, learner_id, offer_id, teacher_id,
                    pupil_id, starts_at, ends_at, mode, place, proposed_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, college, requestId, q.providerId(), q.learnerId(), offer, teacher, pupil, in.startsAt(),
                in.endsAt(), in.mode(), in.place(), actor.id());
        Session created = session(actor, id == null ? 0 : id);
        notice(college, other, "SESSION_PROPOSED", "session:" + created.id() + ":proposed", "Session proposed",
                created.skill() + " with " + firstName(actor, created) + " — confirm or cancel", requestId, created.id());
        return created;
    }

    /** action is "confirm", "cancel" or "complete". */
    public Session act(Account actor, long id, String action, CancelInput cancel) {
        long college = college(actor);
        SessionRow s = db.query("""
                SELECT id, teacher_id, pupil_id, proposed_by, starts_at, ends_at, status FROM public.skill_sessions
                WHERE college_id = ? AND id = ? AND (teacher_id = ? OR pupil_id = ?) FOR UPDATE
                """, (r, i) -> new SessionRow(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4),
                r.getObject(5, OffsetDateTime.class), r.getObject(6, OffsetDateTime.class), r.getString(7)),
                college, id, actor.id(), actor.id()).stream().findFirst().orElseThrow(ApiError.SKILL_NOT_FOUND::exception);
        long other = s.teacherId() == actor.id() ? s.pupilId() : s.teacherId();
        OffsetDateTime now = now();
        switch (action) {
            case "confirm" -> {
                if (!s.status().equals("REQUESTED") || s.proposedBy() == actor.id() || !s.startsAt().isAfter(now))
                    throw ApiError.SKILL_STATE_CONFLICT.exception();
                db.update("UPDATE public.skill_sessions SET status = 'SCHEDULED', confirmed_at = statement_timestamp() WHERE college_id = ? AND id = ?",
                        college, id);
            }
            case "cancel" -> {
                if (!s.status().equals("REQUESTED") && !s.status().equals("SCHEDULED")) throw ApiError.SKILL_STATE_CONFLICT.exception();
                String reason = text(cancel == null ? null : cancel.reason(), 300, false);
                db.update("UPDATE public.skill_sessions SET status = 'CANCELLED', cancelled_by = ?, cancel_reason = ? WHERE college_id = ? AND id = ?",
                        actor.id(), reason.isEmpty() ? null : reason, college, id);
            }
            case "complete" -> {
                if (!s.status().equals("SCHEDULED") || now.isBefore(s.endsAt())) throw ApiError.SKILL_STATE_CONFLICT.exception();
                db.update("UPDATE public.skill_sessions SET status = 'COMPLETED', completed_at = statement_timestamp() WHERE college_id = ? AND id = ?",
                        college, id);
            }
            default -> throw ApiError.SKILL_INVALID.exception();
        }
        Session updated = session(actor, id);
        if (action.equals("confirm")) notice(college, other, "SESSION_SCHEDULED", "session:" + id + ":scheduled",
                "Session confirmed", firstName(actor, updated) + " confirmed your " + updated.skill() + " session", null, id);
        if (action.equals("cancel")) notice(college, other, "SESSION_CANCELLED", "session:" + id + ":cancelled",
                "Session cancelled", updated.skill() + " was cancelled" + (updated.cancelReason() == null ? "" : ": " + updated.cancelReason()), null, id);
        return updated;
    }

    private static String firstName(Account actor, Session s) {
        String name = s.teacherId() == actor.id() ? s.teacher() : s.pupil();
        return name == null ? "your partner" : name.split(" ")[0];
    }

    // ---- Reviews ----

    /** One review per person per completed session, about the other student of that session. */
    public Review review(Account actor, long sessionId, ReviewInput input) {
        long college = college(actor);
        int rating = cleanRating(input);
        String comment = text(input.comment(), 1000, false);
        SessionRow s = db.query("""
                SELECT id, teacher_id, pupil_id, proposed_by, starts_at, ends_at, status FROM public.skill_sessions
                WHERE college_id = ? AND id = ? AND (teacher_id = ? OR pupil_id = ?)
                """, (r, i) -> new SessionRow(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4),
                r.getObject(5, OffsetDateTime.class), r.getObject(6, OffsetDateTime.class), r.getString(7)),
                college, sessionId, actor.id(), actor.id()).stream().findFirst().orElseThrow(ApiError.SKILL_NOT_FOUND::exception);
        if (!s.status().equals("COMPLETED")) throw ApiError.SKILL_STATE_CONFLICT.exception();
        long reviewee = s.teacherId() == actor.id() ? s.pupilId() : s.teacherId();
        Long id;
        try {
            id = db.queryForObject("""
                    INSERT INTO public.skill_reviews (college_id, session_id, reviewer_id, reviewee_id, rating, comment)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, college, sessionId, actor.id(), reviewee, rating, comment);
        } catch (DuplicateKeyException duplicate) {
            throw ApiError.SKILL_REVIEW_EXISTS.exception();
        }
        notice(college, reviewee, "REVIEW_NEW", "review:" + id, "New review", "You received " + rating + " out of 5 for a session",
                null, sessionId);
        return reviews(actor, reviewee).stream().filter(r -> r.id() == (id == null ? 0 : id)).findFirst()
                .orElseThrow(ApiError.SKILL_NOT_FOUND::exception);
    }

    /** Reviews a classmate received; a student of another college is simply not found. */
    public List<Review> reviews(Account actor, long student) {
        long college = college(actor);
        if (!Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.student_profiles WHERE college_id = ? AND student_id = ?)
                """, Boolean.class, college, student))) throw ApiError.SKILL_NOT_FOUND.exception();
        return db.query("""
                SELECT r.id, r.rating, r.comment, split_part(u.full_name, ' ', 1), coalesce(o.name, w.title), r.created_at
                FROM public.skill_reviews r JOIN public.users u ON u.id = r.reviewer_id
                JOIN public.skill_sessions s ON s.college_id = r.college_id AND s.id = r.session_id
                LEFT JOIN public.skill_offers o ON o.college_id = s.college_id AND o.id = s.offer_id
                JOIN public.skill_requests sq ON sq.college_id = s.college_id AND sq.id = s.request_id
                LEFT JOIN public.skill_wants w ON w.college_id = sq.college_id AND w.id = sq.want_id
                WHERE r.college_id = ? AND r.reviewee_id = ? ORDER BY r.id DESC LIMIT 50
                """, (r, i) -> new Review(r.getLong(1), r.getInt(2), r.getString(3), r.getString(4), r.getString(5),
                r.getObject(6, OffsetDateTime.class)), college, student);
    }

    // ---- Notifications ----

    private void notice(long college, long student, String kind, String key, String title, String body, Long request, Long session) {
        notices.student(college, student, kind, key, title, body, SkillNotices.Links.request(request, session));
    }

    public List<Notice> notices(Account actor, int page) {
        long college = college(actor);
        if (page < 0 || page > 1000) throw ApiError.SKILL_INVALID.exception();
        return db.query("""
                SELECT n.id, n.kind, n.title, n.body, n.request_id, n.session_id,
                       coalesce(s.starts_at, gs.starts_at, fr.starts_at) starts_at, n.created_at, n.read_at,
                       n.want_id, n.group_id, n.group_session_id, n.faculty_request_id
                FROM public.skill_notifications n
                LEFT JOIN public.skill_sessions s ON s.college_id = n.college_id AND s.id = n.session_id
                LEFT JOIN public.skill_group_sessions gs ON gs.college_id = n.college_id AND gs.id = n.group_session_id
                LEFT JOIN public.skill_faculty_requests fr ON fr.college_id = n.college_id AND fr.id = n.faculty_request_id
                WHERE n.college_id = ? AND n.student_id = ? ORDER BY n.id DESC LIMIT ? OFFSET ?
                """, (r, i) -> new Notice(r.getLong(1), r.getString(2), r.getString(3), r.getString(4),
                (Long) r.getObject(5, Long.class), (Long) r.getObject(6, Long.class), r.getObject(7, OffsetDateTime.class),
                r.getObject(8, OffsetDateTime.class), r.getObject(9, OffsetDateTime.class), (Long) r.getObject(10, Long.class),
                (Long) r.getObject(11, Long.class), (Long) r.getObject(12, Long.class), (Long) r.getObject(13, Long.class)),
                college, actor.id(), PAGE, page * PAGE);
    }

    public long unread(Account actor) {
        long college = college(actor);
        Long count = db.queryForObject("SELECT count(*) FROM public.skill_notifications WHERE college_id = ? AND student_id = ? AND read_at IS NULL",
                Long.class, college, actor.id());
        return count == null ? 0 : count;
    }

    public void markRead(Account actor, long id) {
        long college = college(actor);
        int changed = db.update("""
                UPDATE public.skill_notifications SET read_at = statement_timestamp()
                WHERE college_id = ? AND student_id = ? AND id = ? AND read_at IS NULL
                """, college, actor.id(), id);
        if (changed == 0 && !Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.skill_notifications WHERE college_id = ? AND student_id = ? AND id = ?)
                """, Boolean.class, college, actor.id(), id))) throw ApiError.NOTIFICATION_NOT_FOUND.exception();
    }

    /**
     * Adds a reminder a day and an hour before every scheduled session: one-to-one sessions (both students), group
     * sessions (the teaching student and current learners) and accepted faculty sessions (the students who joined, and
     * the faculty member). Each reminder stays in the session's own college. Event keys make every reminder unique, so
     * running this often never duplicates one. Returns how many were added.
     */
    public int sendReminders() {
        int added = 0;
        // The day-before reminder is skipped for a session already within the hour; that one gets the hour reminder.
        for (String[] w : List.of(new String[]{"24:", "Session within a day", "1 hour", "24 hours"},
                new String[]{"1:", "Session within the hour", "0 minutes", "1 hour"})) {
            added += db.update("""
                    INSERT INTO public.skill_notifications (college_id, student_id, kind, event_key, title, body, session_id)
                    SELECT s.college_id, p.student_id, 'SESSION_REMINDER', CAST(? AS text) || s.id, ?, coalesce(o.name, wt.title), s.id
                    FROM public.skill_sessions s
                    LEFT JOIN public.skill_offers o ON o.college_id = s.college_id AND o.id = s.offer_id
                    JOIN public.skill_requests sq ON sq.college_id = s.college_id AND sq.id = s.request_id
                    LEFT JOIN public.skill_wants wt ON wt.college_id = sq.college_id AND wt.id = sq.want_id
                    CROSS JOIN LATERAL (VALUES (s.teacher_id), (s.pupil_id)) p(student_id)
                    WHERE s.status = 'SCHEDULED' AND s.starts_at > statement_timestamp() + CAST(? AS interval)
                      AND s.starts_at <= statement_timestamp() + CAST(? AS interval)
                    ON CONFLICT ON CONSTRAINT uq_skill_notification_event DO NOTHING
                    """, "reminder" + w[0], w[1], w[2], w[3]);
            added += db.update("""
                    INSERT INTO public.skill_notifications (college_id, student_id, kind, event_key, title, body, group_id, group_session_id)
                    SELECT gs.college_id, p.student_id, 'SESSION_REMINDER', CAST(? AS text) || gs.id, ?, g.name, g.id, gs.id
                    FROM public.skill_group_sessions gs
                    JOIN public.skill_groups g ON g.college_id = gs.college_id AND g.id = gs.group_id AND g.status = 'ACTIVE'
                    CROSS JOIN LATERAL (SELECT g.teacher_id UNION SELECT m.learner_id FROM public.skill_group_members m
                        WHERE m.college_id = g.college_id AND m.group_id = g.id AND m.left_at IS NULL) p(student_id)
                    WHERE gs.status = 'SCHEDULED' AND gs.starts_at > statement_timestamp() + CAST(? AS interval)
                      AND gs.starts_at <= statement_timestamp() + CAST(? AS interval)
                    ON CONFLICT ON CONSTRAINT uq_skill_notification_event DO NOTHING
                    """, "group-reminder" + w[0], w[1], w[2], w[3]);
            added += db.update("""
                    INSERT INTO public.skill_notifications (college_id, student_id, kind, event_key, title, body, faculty_request_id)
                    SELECT fr.college_id, m.student_id, 'SESSION_REMINDER', CAST(? AS text) || fr.id, ?, fr.topic, fr.id
                    FROM public.skill_faculty_requests fr
                    JOIN public.skill_faculty_request_members m ON m.college_id = fr.college_id AND m.request_id = fr.id
                    WHERE fr.status = 'ACCEPTED' AND fr.starts_at > statement_timestamp() + CAST(? AS interval)
                      AND fr.starts_at <= statement_timestamp() + CAST(? AS interval)
                    ON CONFLICT ON CONSTRAINT uq_skill_notification_event DO NOTHING
                    """, "faculty-reminder" + w[0], w[1], w[2], w[3]);
            added += db.update("""
                    INSERT INTO public.skill_faculty_notices (college_id, faculty_id, kind, event_key, title, body, request_id)
                    SELECT fr.college_id, fr.faculty_id, 'REMINDER', CAST(? AS text) || fr.id, ?, fr.topic, fr.id
                    FROM public.skill_faculty_requests fr
                    WHERE fr.status = 'ACCEPTED' AND fr.starts_at > statement_timestamp() + CAST(? AS interval)
                      AND fr.starts_at <= statement_timestamp() + CAST(? AS interval)
                    ON CONFLICT ON CONSTRAINT uq_skill_faculty_notice_event DO NOTHING
                    """, "reminder" + w[0], w[1], w[2], w[3]);
        }
        return added;
    }

    public Summary summary(Account actor) {
        long college = college(actor);
        Long division = division(college, actor.id());
        long[] c = db.queryForObject("""
                SELECT (SELECT count(*) FROM public.skill_offers WHERE college_id = ? AND student_id = ? AND status <> 'ARCHIVED'),
                       (SELECT count(*) FROM public.skill_requests WHERE college_id = ? AND provider_id = ? AND status = 'REQUESTED'),
                       (SELECT count(*) FROM public.skill_requests WHERE college_id = ? AND learner_id = ? AND status = 'REQUESTED'),
                       (SELECT count(*) FROM public.skill_sessions WHERE college_id = ? AND (teacher_id = ? OR pupil_id = ?)
                          AND status IN ('REQUESTED', 'SCHEDULED') AND ends_at >= ?),
                       (SELECT count(*) FROM public.skill_notifications WHERE college_id = ? AND student_id = ? AND read_at IS NULL),
                       (SELECT count(*) FROM public.skill_wants WHERE college_id = ? AND status = 'OPEN' AND student_id <> ?
                          AND (audience = 'COLLEGE' OR division_id = ?)),
                       (SELECT count(*) FROM public.skill_groups g WHERE g.college_id = ? AND g.status = 'ACTIVE' AND (g.teacher_id = ?
                          OR EXISTS (SELECT 1 FROM public.skill_group_members m WHERE m.college_id = g.college_id
                             AND m.group_id = g.id AND m.learner_id = ? AND m.left_at IS NULL))),
                       (SELECT count(*) FROM public.skill_faculty_requests fr WHERE fr.college_id = ? AND fr.status IN ('OPEN', 'ACCEPTED')
                          AND EXISTS (SELECT 1 FROM public.skill_faculty_request_members m WHERE m.college_id = fr.college_id
                             AND m.request_id = fr.id AND m.student_id = ?))
                """, (r, i) -> new long[]{r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5), r.getLong(6),
                        r.getLong(7), r.getLong(8)},
                college, actor.id(), college, actor.id(), college, actor.id(), college, actor.id(), actor.id(), now(),
                college, actor.id(), college, actor.id(), division, college, actor.id(), actor.id(), college, actor.id());
        List<Session> upcoming = sessions(actor, When.UPCOMING);
        return new Summary(c[0], c[1], c[2], c[3], upcoming.isEmpty() ? null : upcoming.get(0), c[4], c[5], c[6], c[7]);
    }
}