# CampusSphere development and submission checklist

Target submission is 26 November 2026. Use this inventory to select a demonstrable first release. Cite direct test evidence and label each screenshot with its data source.

## Phase review gate

After each module, build and install the latest debug APK on Pixel 6 and walk through its screens, available actions, and applicable UI states. Use an authorized live backend when available. Label synthetic responses and save screenshots in a phase-specific directory. Fix issues found in the walkthrough, rebuild, and repeat the affected steps. Share the demonstration and stop for review before beginning the next module.

| Priority | Module | Current implementation | Next deliverable |
|---|---|---|---|
| 1 | Authentication and role access | Kotlin login, registration, JWT session handling, effective role routing, separately guarded backend routes. Faculty verification and legacy review exist. | Preserve flows and capture a sanitized role-access test matrix. |
| 2 | Super Admin colleges and onboarding | Platform dashboard, colleges, invitations, public application, and review screens use real REST contracts. PostgreSQL V8 schema was verified in the earlier rehearsal. Live onboarding is paused. | Retain the existing code and evidence. Resume the separate live gates when approved. |
| 3 | College Admin academics | Departments, Programs, Academic Years, Semesters, Divisions and Subjects have authenticated list, search, add and edit screens. Divisions use the selected program and academic year. Subjects use the selected program and semester. | Review staged V9–V11 and authorize persistence verification on the local development target. |
| 4 | Faculty management and dashboard | College Admin directory, profiles, department placement and subject assignment are implemented. Faculty dashboard lists only the signed-in member's active classes. | Review V12, verify college-bound Faculty eligibility and authorize live persistence tests. |
| 5 | Student management and enrollment | Student affiliation request and review, College Admin directory and profiles, department placement, enrollment history and current Student dashboard. | Review V13 and authorize live persistence and admission workflow verification. |
| 6–8 | Attendance, materials, announcements, timetable, assignments | No domain controllers or V1–V8 tables for these modules were found in this inspection. | Deliver one academic feature at a time after approved schema design and before representing it as implemented. |
| 9 | Class groups/chat | No class-group chat implementation was found. | Define enrollment-based student-only membership and enforce it on every server request. |
| 10 | AI study planner/tutor | No backend AI integration was found. | Plan a backend-mediated integration, credential protection, and limits after the core academic modules. |

## Review order for submission

- [ ] Screenshot the tested CampusSphere paths with no secrets or personal data.
- [ ] Label mock emulator evidence as synthetic and distinguish it from authenticated live workflow evidence.
- [ ] Freeze the tested APK and record application ID, SDK levels, build variant, and commands.
- [ ] Keep the Android and backend directories intact for separate GitHub review. Check the review scope and secret handling before any future upload.
- [ ] Preserve the approved Phase 10 rehearsal plan, tests, and source for later execution.
- [ ] Obtain explicit approval for any database migration or fixture action. A persistent Programs/Semesters module needs new tenant-scoped database entities and constraints.

## Latest Department Management evidence

- Android debug and instrumentation APKs built successfully. Android JVM tests passed 20/20, including three Department API tests using intercepted Retrofit requests.
- The backend's offline department tenant-scope suite passed 2/2.
- Pixel 6 passed the synthetic list → add → edit walkthrough, 1/1, with no live API or database connection. The first run identified a stale form-state update. The corrected run verified both request bodies and rendered the updated list.
- The screenshots are in the Android project's app/build/reports/department-pixel6 directory. Each is labelled synthetic. The main app uses the existing authenticated REST endpoints, and live department persistence remains to be verified on an approved target.

## Final-year report outline

1. **Introduction and objectives.** Define CampusSphere as a multi-college mobile platform. Identify the main users and goals of secure onboarding and college-scoped administration. State the submission scope.
2. **Problem statement.** Describe manual college onboarding, fragmented academic administration, and the risk of cross-college data access. Tie each verified objective to a user workflow.
3. **Architecture.** Diagram Android Compose, ViewModels, repositories and Retrofit, the Spring REST API and Spring Security, services and persistence, and PostgreSQL. Show the JWT request path and the trust boundary where the backend resolves college ownership.
4. **Technology.** Document Kotlin, Compose Material 3, Retrofit, Android Keystore session storage, Java 21, Spring Boot, JWT, BCrypt, JPA/JDBC, PostgreSQL 19 and Flyway. List exact version information from the projects and label the debug emulator setup.
5. **Database design.** Draw the implemented ER diagram. Include users, roles, grants, colleges, departments, Faculty requests, invitations, registration requests and append-only privileged audit events. Explain tenant keys, normalized uniqueness, pending/terminal state constraints, and V1–V8 history from verified evidence.
6. **Role-based access.** Document public applicant, Student, Faculty, legacy Admin, College Admin and Super Admin. Include a route-to-role matrix. Describe authorization by current database-backed account state, department scope and cross-college denial.
7. **Backend APIs.** List existing authentication, public registration, protected review, college management, invitations and department endpoints. Document request fields, success statuses, and safe structured error codes. Give synthetic examples only.
8. **Android modules.** Describe public navigation, authentication and session recovery, Super Admin college/onboarding screens, and College Admin department list, search and forms. Identify which paths are real Retrofit integrations and which are visual previews or mock sessions.
9. **Implemented features and limitations.** Record only evidenced behavior. Distinguish checked source, offline tests, synthetic emulator evidence, and live PostgreSQL evidence. Identify the paused live onboarding rehearsal.
10. **Testing and results.** Include exact test commands and counts, build output, emulator/device details, test failure and recovery evidence, and the extent of database verification. Document negative authorization and input validation tests.
11. **Screenshots and demonstration.** Include Login, Register Your College, approval and rejection, the College Admin dashboard, and the department list and forms when available. Caption source of data and result shown. Include APK path and demo steps.
12. **Future scope.** Explain tenant-scoped Programs and enrollment, Faculty/Student academic screens, attendance, timetable, materials, announcements, assignments, student-only chat and backend AI. Separate schema, privacy, and deployment prerequisites.

## Programs implementation and migration review

Program codes are unique across a college, including across different departments. Separate colleges may use the same code. The backend validates an ASCII code of 1–30 characters, canonicalizes it to uppercase, and trims the program name. The tenant always comes from the authenticated College Admin account. The backend checks the department against that tenant, and PATCH scopes the program to both tenant and department.

| Method | Route | Result |
|---|---|---|
| GET | /api/college/departments/{departmentId}/programs | 200 with an ordered array of id, code and name. Search filters the loaded department list in Android. |
| POST | Same collection route | 201 with the program. Body requires code and name. |
| PATCH | /api/college/departments/{departmentId}/programs/{programId} | 200 with the updated program. Body can supply either or both fields. |

The request body rejects ownership IDs and other unknown fields. Anonymous calls receive 401, other roles or inactive college scope receive 403, and inaccessible department/program IDs return 404. Invalid values return 400. A duplicate code in the same college returns 409 with PROGRAM_CODE_CONFLICT. Creation handles concurrent duplicates through the unique constraint.

Review backend proposed-migrations/V9__create_academic_programs.sql. The table has an identity primary key and a composite foreign key from college ID and department ID to the existing V5 department key. It uses ON DELETE RESTRICT, non-null fields, code format and nonblank name checks, a college-wide unique code constraint, and a college/department/code index for scoped listing. V9 creates no fixtures or program rows.

The requested live development target is exactly 127.0.0.1:5432/campus_sphere_dev. V9 is staged outside Flyway discovery pending approval. Before moving it into the active migration directory or running it, confirm the authenticated target and current history read-only, and verify a new approved pre-rollout backup. The last verified development baseline was V1–V7. If V8 is still pending, it needs separate rollout approval and verification before V9. Use a separately approved target-pinned runner rather than the broad development override. Verify migration history and schema and then prove tenant isolation with separately approved synthetic fixtures. The Phase 10 rehearsal, test and legacy databases are excluded.

Offline verification passed 12 backend tests and 22 Android JVM tests. The final Pixel 6 run passed both the expanded Programs walkthrough and the existing Departments regression with intercepted Retrofit responses. The 14 screenshots and contact sheet are in the Android project's app/build/reports/programs-demo-pixel6 directory. The V9 file SHA-256 is a1be3f8d3b93ac53baac4337528a0c036755ddaed9f180dd18796fcbb0439381. The phase is paused for review.

## Academic Years and Semesters

An academic year uses a consecutive four-digit range such as 2026-2027, unique within a college. A semester number is an integer from 1 through 99, unique in its college, program and year. Both records also have a display name. Android search filters the loaded list by code or name.

| Methods | Route |
|---|---|
| GET, POST | /api/college/academic-years |
| PATCH | /api/college/academic-years/{id} |
| GET, POST | /api/college/programs/{programId}/academic-years/{yearId}/semesters |
| PATCH | /api/college/programs/{programId}/academic-years/{yearId}/semesters/{id} |

The response fields are id, code and name. For a semester, code is the number represented as a string. POST requires both editable fields and returns 201. PATCH accepts either or both and returns 200. Ownership is derived from the College Admin principal. Nested routes verify both parents belong to the same tenant. Server errors retain the invalid-input, authorization, missing-parent and duplicate conventions.

V10 remains in backend proposed-migrations/V10__create_academic_years_and_semesters.sql. It adds the college/program composite key to V9's table, creates the two period tables, and enforces tenant-matched program and year foreign keys with restricted deletion, unique business keys, range checks and nonblank names. The scoped semester unique index supports listing by number, with a second index for year lookups. V10 has no fixture inserts. Live rollout requires separately approved history verification, prerequisite migrations, a backup, V10 review and target-pinned execution against campus_sphere_dev, then a separately authorized API persistence test. Preserve historical enrollment when designing any future link to these periods.

## Divisions and Subjects

- College Admin routes support GET and POST on `/api/college/programs/{programId}/academic-years/{yearId}/divisions` and `/api/college/programs/{programId}/semesters/{semesterId}/subjects`, and PATCH with `/{id}` appended. Requests carry a code and name. The backend derives the college from the authenticated principal and checks the nested parent ownership.
- V11 remains in backend proposed-migrations/V11__create_divisions_and_subjects.sql. Division codes are unique within each college, program and academic year. Subject codes are unique within each college, program and semester. Composite foreign keys enforce matching tenant and parent identifiers.
- The Pixel 6 launcher option `-Phase DivisionsSubjects` exercises the Retrofit contracts with clearly labeled synthetic responses. Live persistence requires verifying the earlier migration prerequisites and separate approval to apply V11 and run end-to-end API tests against campus_sphere_dev.

## Faculty Management and Subject Assignment

- College Admins manage eligible, college-bound Faculty profiles under `/api/college/faculty`, including department placement and creation or ending of subject assignments. `GET /api/faculty/assignments` gives Faculty only their own active assignments using the authenticated account ID and college.
- The V12 proposal adds faculty profiles keyed by tenant and user and an independent assignment table. Composite foreign keys enforce the department/program, program/year/semester, division and subject relationships. A partial unique index prevents duplicate active faculty/division/subject assignments. Ending an assignment preserves its history. A department change requires ending active assignments first.
- V12 requires V9–V11 plus the existing identity and department foundation. Any live rollout requires separate permission to inspect the approved development target, verify prerequisites and take a backup before applying staged migrations. Legacy Faculty approval remains college-unassigned. An approved mechanism to bind those accounts to a verified college is a separate prerequisite for their live assignment.

## Student Management and Enrollment

- Authenticated unassigned Student accounts request a college via `/api/auth/student-affiliation`. The College Admin queue at `/api/college/student-affiliation-requests` uses the reviewer's college. A pending request grants no academic access. Manual admission confirmation is required to bind and verify the account in one transaction. Rejection preserves request history and permits a new request.
- College Admin APIs under `/api/college/students` operate on verified students in the principal's college. The Student dashboard uses `GET /api/student/enrollment`, which derives both student and college identity from the authenticated account.
- The V13 proposal depends on V9–V12 plus the existing college, department, and identity foundation. It adds composite tenant foreign keys for the program, semester and division hierarchy, one pending affiliation per student, and one current enrollment per student. Ending an enrollment preserves its academic links in history and permits a later enrollment. The proposal includes no fixture inserts. A live rollout requires approved prerequisite verification, a development backup, applying the approved proposals, and separately authorized API persistence tests.
