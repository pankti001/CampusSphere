package com.campussphere.backend.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes Skill Exchange notifications. Every notification names its college and one person of that college; the
 * database's college-scoped foreign keys refuse anything else. A unique event key per person makes a retried event
 * add nothing twice.
 */
@Component
class SkillNotices {
    /** What a student notification links to (any may be null). */
    record Links(Long request, Long session, Long want, Long group, Long groupSession, Long facultyRequest) {
        static Links request(Long request, Long session) { return new Links(request, session, null, null, null, null); }
        static Links want(long want) { return new Links(null, null, want, null, null, null); }
        static Links group(long group, Long groupSession) { return new Links(null, null, null, group, groupSession, null); }
        static Links faculty(long request) { return new Links(null, null, null, null, null, request); }
    }

    private final JdbcTemplate db;

    SkillNotices(JdbcTemplate db) {
        this.db = db;
    }

    private static String fit(String text, int max) {
        String clean = text == null || text.isBlank() ? "-" : text.strip();
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    void student(long college, long student, String kind, String key, String title, String body, Links links) {
        db.update("""
                INSERT INTO public.skill_notifications (college_id, student_id, kind, event_key, title, body, request_id,
                    session_id, want_id, group_id, group_session_id, faculty_request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT ON CONSTRAINT uq_skill_notification_event DO NOTHING
                """, college, student, kind, fit(key, 100), fit(title, 200), fit(body, 600), links.request(), links.session(),
                links.want(), links.group(), links.groupSession(), links.facultyRequest());
    }

    void faculty(long college, long faculty, String kind, String key, String title, String body, long request) {
        db.update("""
                INSERT INTO public.skill_faculty_notices (college_id, faculty_id, kind, event_key, title, body, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT ON CONSTRAINT uq_skill_faculty_notice_event DO NOTHING
                """, college, faculty, kind, fit(key, 100), fit(title, 200), fit(body, 600), request);
    }
}
