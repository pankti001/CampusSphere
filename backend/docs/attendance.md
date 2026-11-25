# Manual Attendance

Attendance reuses the existing tenant, Faculty assignments and Student enrollments. V15 is an additive proposal in `backend/proposed-migrations/`. It requires V1–V14 and has not been applied. Neither the Faculty rehearsal database nor an existing application database is a target for this phase.

## Faculty

Start at **Faculty dashboard → Class attendance → Open lecture**. Choose one of your active subject and division assignments and enter a date, lecture number and optional start/end times. The server determines the roster at the lecture start in Asia/Kolkata. A current-day lecture without a start time uses the current instant. A past day without a start time uses its end, so enter a start time when enrollment could have changed during that day. Future lectures and dates before the selected assignment took effect are rejected.

The service snapshots eligible enrollments when the draft opens. It scopes the roster by college, department, program, academic year, semester and division. The snapshot stays attached to the original enrollment after that enrollment ends. Mark individuals or use **Mark all present**. A draft may be partial. Submission requires every roster member to have exactly one valid mark. Review the totals before submitting.

Only the assigned Faculty account can mark and submit. Active assignment is checked again for every Faculty write. Revisions prevent stale drafts or duplicate submissions. The lecture key is college, subject, division, date and lecture number, so separate lectures can occur on the same day.

## Student and College Admin

Students use **My attendance** for totals, subject summaries, date and subject filters, lecture history, and alerts. The API derives Student identity from the authenticated account. Drafts do not appear in summaries.

College Admins use **Attendance reports** for a college overview, filters for department, program, semester, division, subject, Faculty, Student and date range, and published lecture details. They can configure thresholds and the Faculty correction window, see active low-attendance alerts, inspect correction history, and lock sessions. A locked session can be corrected only by an authorized College Admin. Each correction records the previous and new mark, actor, time and reason. A database trigger preserves correction history.

Present counts as attended. Absent counts as missed. By default, Late counts as attended and Excused is excluded from eligible sessions. The calculation is total attended sessions divided by total eligible sessions, rather than an average of subject percentages. Zero eligible sessions produce no percentage and no low-attendance alert. By default, less than 65% is Critical, 65% to below 75% is Warning, and 75% or above is Normal. The server classifies using exact counts before rounding percentages for display. On submission, correction or policy update, it keeps one active alert per Student. It resolves the previous alert when the state changes and avoids a new alert when severity is unchanged. Alert state uses all of the Student's recorded lectures in that college. Reports and history filters narrow the displayed data.

## REST routes

| Role | Routes |
| --- | --- |
| Faculty | `GET /api/faculty/attendance/assignments`; `GET/POST /api/faculty/attendance/sessions`; `GET …/sessions/{id}`; `PUT …/sessions/{id}/draft`; `POST …/sessions/{id}/submit`; `POST …/sessions/{id}/records/{studentId}/corrections` |
| Student | `GET /api/student/attendance`; `GET /api/student/attendance/subjects` |
| College Admin | `GET /api/college/attendance/reports`, `/report-options`, `/alerts`, and `/policy`; `PUT …/policy`; `GET …/sessions/{id}`; `POST …/sessions/{id}/lock`; `POST …/sessions/{id}/records/{studentId}/corrections`; `GET …/sessions/{id}/audit` |

## Live acceptance gate

Finish the separate Faculty onboarding walkthrough. Then approve a dedicated Attendance PostgreSQL target, the reviewed V15 migration, and synthetic fixture creation. Use two colleges, at least one approved Faculty assignment and Student enrollment per college, and role-specific login on Pixel 6. Verify roster exclusion, draft and submission, duplicate prevention, a cross-college denial, Student totals and alerts, Admin reports and locked correction history, and persisted results after restart.
