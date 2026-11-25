# CampusSphere

CampusSphere is a multi-college academic management project. The Android client uses Kotlin, Jetpack Compose and Retrofit. The REST backend uses Java 21, Spring Boot, Spring Security, JWT, PostgreSQL and Flyway.

| Location | Contents |
| --- | --- |
| `app/` | Android application, JVM tests and Pixel 6 instrumentation tests |
| `backend/` | Spring Boot source, Maven wrapper, tests, migration SQL and operator documentation |

## Project status

| Module | Status |
| --- | --- |
| Authentication and role access (Super Admin, College Admin, Faculty, Student) | Implemented |
| College registration, review and College Admin invitations | Implemented |
| Departments, Programs, Academic Years, Semesters, Divisions and Subjects | Implemented |
| Faculty management, department placement and subject assignment | Implemented |
| Student affiliation, admission review and enrollment | Implemented and verified against PostgreSQL |
| Attendance | In progress |
| Study materials | In progress |
| Announcements, timetable, assignments, class chat, AI study planner | Planned |

## Build and demo

- Open the repository root in Android Studio. The debug variant uses the application ID `com.campussphere.app.debug` and reaches a locally running backend at `http://10.0.2.2:8080/` from an Android emulator.
- On Windows, run `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` from the repository root.
- In `backend/`, use the Maven wrapper and Java 21. Review [local backend setup](backend/docs/local-development.md) before running the backend or database-backed tests. Supply the database and JWT settings through the environment.
- The active Flyway directory contains V1–V8. The V9–V13 SQL in `backend/proposed-migrations/` was rehearsed and rolled out separately. Follow the [rollout record](STUDENT-MANAGEMENT-LIVE-ROLLOUT.md) before preparing a fresh database for those modules. V14–V16 (college-scoped Faculty requests, attendance and study materials) are proposals that have not been applied to a database yet.
- See the [demonstration guide](CAMPUSSPHERE-FINAL-DEMONSTRATION-GUIDE.md) for the Pixel 6 walkthrough. Its one-click demo uses labeled synthetic responses. The separate Student Management verification used the real backend and PostgreSQL with synthetic accounts in an isolated database.

Generated builds, local credentials, database dumps and signing keys are excluded from this submission.
