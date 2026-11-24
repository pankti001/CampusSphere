# Phase 10 College Onboarding live rehearsal

## Gate 1 — Backend launch

Approve the isolated backend launch separately from fixture creation. Keep the backend on loopback, with Flyway and scheduled expiry disabled, Hibernate validation enabled, and the SQL initializer disabled.

## Gate 2 — Exact fixture creation

Approve exactly the four fixture rows below. The last verified rehearsal state was V1–V8 successful with five seeded roles and zero application rows. The fixture helper reconfirms those counts and V1–V8 names and checksums inside its transaction and refuses a partial baseline, a nonempty application table, or a different database.

| Fixture | Exact values | Committed row impact |
|---|---|---|
| Reviewer | Phase 10 Synthetic Reviewer, `reviewer.p10@example.invalid`, verified and active, no college, only `SUPER_ADMIN`, no phone | +1 user, +1 user-role link |
| Foreign tenant | Phase 10 Beta Institute, `P10-BETA`, active | +1 college |
| Foreign department | Beta Rehearsal Department, `P10-BETA-DEPT`, owned by the Beta college | +1 department |

The test-only Phase10RehearsalFixtures helper uses the exact literal rehearsal URL and verifies database name, address, port, PostgreSQL 19.0, current/session role, and schema. It prompts privately for the database password and a unique synthetic reviewer password. It BCrypt-hashes the reviewer password, obtains the existing SUPER_ADMIN role ID by name, and transactionally inserts only the four approved rows. Direct fixture inserts produce zero privileged audit events. Obtain fixture IDs from a read-only check. This helper is separate from the development provisioner.

## Gate 3 — Workflow execution approval

All applicant full names below are synthetic. Use position `Registrar`, omit the website, and use privately chosen fixture passwords that meet the app's minimum 12 characters and maximum 72 UTF-8 bytes. Never put fixture passwords, password hashes, or JWTs in command arguments, output, or retained evidence.

| Purpose | College name / code | Administrator name / email |
|---|---|---|
| Pixel workflow | Phase 10 Alpha Institute / `P10-ALPHA` | Phase 10 Alpha Applicant / `alpha.p10@example.invalid` |
| Reject | Phase 10 Reject Institute / `P10-REJECT` | Phase 10 Rejection Applicant / `reject.p10@example.invalid` |
| Expire | Phase 10 Expiry Institute / `P10-EXPIRE` | Phase 10 Expiry Applicant / `expiry.p10@example.invalid` |
| Decision race | Phase 10 Race Institute / `P10-RACE` | Phase 10 Race Applicant / `race.p10@example.invalid` |
| Rollback | Phase 10 Rollback Institute / `P10-ROLLBACK` | Phase 10 Rollback Applicant / `rollback.p10@example.invalid` |

Use `OFFICIAL_CALLBACK` with all three verification flags for approval cases. This represents a synthetic verification step. No external communication is required. Reject with `Synthetic rehearsal rejection`.

| Operation | Expected durable impact |
|---|---|
| Submit five distinct applications | +5 pending requests, each with hash presence verified as a boolean. No user, role link, college, or audit delta. Check duplicate Alpha submission while pending returns generic 202 without increasing count. |
| Approve Alpha | Existing request becomes APPROVED and clears its hash. +1 active college, +1 active verified user bound to Alpha, +1 COLLEGE_ADMIN link, +3 audits (COLLEGE_CREATED, COLLEGE_ACTIVATED, COLLEGE_REGISTRATION_APPROVED). |
| Alpha department | Create `Alpha Rehearsal Department`, code `P10-ALPHA-DEPT`, using the approved Alpha session. +1 department. |
| Reject | Existing request becomes REJECTED with reviewer, decision time and reason. Hash cleared, +1 COLLEGE_REGISTRATION_REJECTED audit. |
| Expiry | Move only the synthetic expiry request's submitted/deadline timestamps together in the test harness, maintaining the 720-hour relationship. Read endpoints should project EXPIRED before cleanup and approval should return 409. Explicitly invoke the expiry service in the test harness. Existing request becomes EXPIRED, hash cleared, zero audit delta. A second sweep changes zero rows. Verify pending reservations are released from the row status and index predicates. |
| Race | Concurrent approve and reject use independent HTTP requests and DB transactions. Exactly one commits and the loser receives 409. Approval winner adds +1 user, +1 college, +1 grant, +3 audits. Rejection winner adds +1 rejection audit only. The request becomes terminal and clears its hash once. |
| Audit failure | In a test-only context with the same pinned datasource, use a scoped JdbcTemplate spy or decorator to fail only the final COLLEGE_REGISTRATION_APPROVED insert for P10-ROLLBACK. Earlier writes run normally. Verify failure and, from a different transaction, zero committed changes. The request remains PENDING and its original hash remains present. No trigger or DDL changes are required. |
| Login, role checks and access denials | Zero application-row changes. |

Expected totals after both gates, with no other writes:

| Table | Race approved | Race rejected |
|---|---:|---:|
| users | 3 | 2 |
| user_roles | 3 | 2 |
| colleges | 3 | 2 |
| departments | 2 | 2 |
| college_registration_requests | 5 | 5 |
| privileged_audit_events | 7 | 5 |
| faculty_access_requests | 0 | 0 |
| college_admin_invitations | 0 | 0 |
| roles | 5 | 5 |
| flyway_schema_history | 8 | 8 |

Keep the rows for reconciliation. Any cleanup has a separate approval and must preserve append-only audit history.

## Sequence and acceptance checks

1. Start the pinned backend after Gate 1 approval. Verify the target and version before fixture writes. Confirm Hibernate validation and Flyway disabled in startup evidence.
2. Seed the four approved rows and verify reviewer role, reviewer college ID null, Beta ownership and counts.
3. After Gate 3 approval, use the Pixel debug APK. Sign out of any existing debug session. Open Register Your College. Submit Alpha and verify its generic receipt, pending row and login denial. A pending account should receive HTTP 401 from /api/auth/login.
4. Sign in privately as the reviewer. Open Pending College Requests, choose Alpha, select the synthetic verification method, check all three confirmations, and approve. Verify success, request removal from the pending list, account and tenant binding, hash clearing and three audits.
5. Sign out. Sign in with the original Alpha credentials. Verify /api/auth/status reports only COLLEGE_ADMIN. Check the college dashboard and then create the Alpha department through the API. Refresh the dashboard and verify the count. The current app shows a count or empty state, so inspect the actual list via the API as well.
6. Use the Alpha session to check that GET /api/college/departments returns Alpha only. Verify GET and PATCH for the known Beta department ID return 404 and leave Beta unchanged. Check platform overview and all four registration review operations return 403 for Alpha, including decision requests with valid payloads. Check another GET returns only Alpha afterward.
7. Run the separate rejection, expiry, race and audit rollback fixtures. Check denied login after rejection and expiry. Also check GET review calls produce no persistent updates before the explicit expiry sweep.
8. Sign out, stop the isolated backend, and reconcile final counts and Flyway V1–V8 history through a read-only transaction. Report test evidence without sensitive inputs or headers.

## Commands

Run from the Android project root. Offline inspection mode does not start Java:

    powershell.exe -NoProfile -File .\Start-Phase10RehearsalBackend.ps1 -Describe

When Gate 1 is approved, refresh the local backend classpath if needed:

    Set-Location -LiteralPath 'D:\OLD Pankti\CampusSphereBackend\campus-sphere-backend'
    .\mvnw.cmd -o "-Dtest=Phase9RehearsalRunnerTest,FlywayDatabaseGuardTest" test

The above tests are offline. Use the launcher from an interactive PowerShell console and confirm the target when prompted:

    Set-Location -LiteralPath 'D:\OLD Pankti\CampusSphere'
    powershell.exe -NoProfile -File .\Start-Phase10RehearsalBackend.ps1

The launcher supplies a fixed JDBC URL and a Hikari identity query on each new pooled connection. It filters inherited Spring, JDBC-related and JVM override environment variables, loads the backend's packaged application properties, pins safety flags, and passes credentials and an ephemeral JWT secret only to the child process. Stop the backend if startup fails or port 8080 belongs to another process. Its stored argument file contains only non-secret JVM and Spring settings.

For the approved Pixel verification, use the existing debug APK and existing emulator. Confirm the serial and AVD first. Only reinstall the debug variant if the emulator has an older build:

    $adb = 'D:\OLD Pankti\Android\SDK\platform-tools\adb.exe'
    & $adb devices
    & $adb -s emulator-5554 emu avd name
    & $adb -s emulator-5554 install -r 'D:\OLD Pankti\CampusSphere\app\build\outputs\apk\debug\app-debug.apk'
    & $adb -s emulator-5554 shell am start -W -n com.campussphere.app.debug/com.campussphere.app.MainActivity

The debug URL is already http://10.0.2.2:8080/. Sign in without Remember Me for rehearsal sessions.

After Gate 2 approval, run the fixture helper once in an interactive console from the backend root. The offline fixture-helper test emits the classpath with test classes included:

    .\mvnw.cmd -o "-Dtest=Phase10RehearsalFixturesTest" test
    $phase10Report = [xml](Get-Content -Raw -LiteralPath 'target\surefire-reports\TEST-com.campussphere.backend.operator.Phase10RehearsalFixturesTest.xml')
    $phase10ClassPath = ($phase10Report.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
    $phase10Java = 'D:\Development\Tools\JDK21\bin\java.exe'
    & $phase10Java -cp $phase10ClassPath com.campussphere.backend.operator.Phase10RehearsalFixtures campus_sphere_phase9_rehearsal
    if ($LASTEXITCODE) { throw 'Fixture helper stopped. Inspect read-only before any further attempt.' }

At Gate 3, execute the separate backend scenarios one method at a time after private process-scoped setup of DB_PASSWORD and JWT_SECRET. Report only status codes and row counts. Stop if any invocation fails.

    $phase10Cases = @(
        'rejectsAndClearsTheHash'
        'readProjectsOverdueStatusAndExplicitExpiryClearsHashOnce'
        'simultaneousDecisionsHaveExactlyOneWinner'
        'failureAtFinalAuditRollsBackWholeApproval'
    )
    foreach ($phase10Case in $phase10Cases) {
        .\mvnw.cmd -o "-Dtest=Phase10CollegeRegistrationRehearsalIT#$phase10Case" "-Dcampussphere.phase10.rehearsal=approved" test
        if ($LASTEXITCODE) { throw 'Rehearsal stopped. Inspect read-only before any further attempt.' }
    }

The integration harness uses real Spring HTTP dispatch, database-backed authorization, and PostgreSQL. Only the final audit call is replaced in the fault-injection test. The expected absence of that audit and the rollback of preceding inserts are checked from outside the approval transaction. This harness has been prepared for Gate 3 and requires live execution to produce PostgreSQL evidence.
