# Student Management and Enrollment live rollout

## Source inspection

The redesigned Student screens already use `StudentViewModel`, `StudentRepository` and the production `StudentApi` Retrofit service. The debug application resolves the API at `http://10.0.2.2:8080/`. The visual walkthrough substitutes an interceptor only in instrumentation. Its screenshots show the UI and request shapes, and live persistence remains unverified.

The backend verifies JWTs and reloads account status and effective roles. Student affiliation is available to eligible signed-in, unbound Student accounts. A college decision requires a current, active College Admin and explicit admission confirmation for approval. Approval records reviewer and timestamp and verifies and binds the account in the same transaction. Membership and enrollment operations derive college scope from the principal. The Student dashboard derives the student ID from the principal as well. Ending an enrollment sets `ended_at` and leaves the historical row available to the College Admin. Academic lookup results are selected by department, program and year, then validated again when saving.

| Workflow | Production endpoints |
| --- | --- |
| Register, sign in, refresh access | `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/auth/status` |
| Submit and check affiliation | `GET` and `POST /api/auth/student-affiliation` |
| Review affiliation | `GET /api/college/student-affiliation-requests`, `POST /api/college/student-affiliation-requests/{requestId}/decision` |
| Directory, profile and department | `GET /api/college/students`, `GET /api/college/students/{studentId}`, `PUT /api/college/students/{studentId}/department` |
| Enrollment and history | `GET` and `POST /api/college/students/{studentId}/enrollments`, `DELETE /api/college/students/{studentId}/enrollments/{enrollmentId}` |
| Student dashboard | `GET /api/student/enrollment` |
| Academic selection | `GET /api/college/departments`, `GET /api/college/departments/{departmentId}/programs`, `GET /api/college/academic-years`, `GET /api/college/programs/{programId}/academic-years/{yearId}/semesters` and `…/divisions` |

The existing app refreshes account status after the affiliation-status endpoint returns 403 for a newly bound applicant. Verify that this transitions a pending applicant to the Student dashboard in the real app. Enrollment validations are in the controller and service, and ownership and one-current-enrollment enforcement also have database constraints. PostgreSQL key validation, transaction atomicity under contention, exact live error responses, and the UI’s recovery from a real outage still need evidence. Hibernate validation covers mapped entities. Check the JDBC-managed Student tables explicitly as well.

## Migration inventory

The active migration directory contains V1–V8. V9–V13 are proposals outside the active Flyway location. The last recorded development baseline is V1–V7. The actual development history must be read after approval. V8 was verified on the earlier isolated rehearsal and must be treated as a separate pending dependency if the development history confirms V7.

| Version | Required schema |
| --- | --- |
| V8 | College registration requests, normalized user email uniqueness and updated audit action constraint. Source Flyway checksum `-1533777827`. |
| V9 | College-scoped academic programs and department ownership. |
| V10 | Academic years and program/year semesters, plus the program composite key. |
| V11 | Division and subject definitions, plus the semester key for subject ownership. |
| V12 | Faculty tables and the exact tenant-specific program, semester and division unique keys that V13 references. |
| V13 | Student affiliation requests, student profiles and enrollment history. Composite foreign keys protect the college and academic hierarchy. Partial unique indexes allow one pending affiliation and one current enrollment per student. |

The SQL chain supplies the referenced keys in order. No additional migration or REST endpoint was identified for the specified flow by source inspection. Apply V12 in sequence even though the Student interface does not use Faculty assignments. The proposals contain no fixture inserts. Hashes below are file SHA-256 digests. Flyway history checksums are a separate comparison.

| Proposal | SHA-256 |
| --- | --- |
| V9 | `a1be3f8d3b93ac53baac4337528a0c036755ddaed9f180dd18796fcbb0439381` |
| V10 | `67bb3166801be0491cf1a05d15e76bd91726a11d29e9ca171b23e8b5934620f0` |
| V11 | `d003d33ddb0c9e5ab9f09d07b6f2be06115ed2377f73a4220b6f66a7ed2dd0f5` |
| V12 | `36303ef5c85d5a496c8a9fbd0711f219ca2d283ea7d464385fa8c600a59d962a` |
| V13 | `816f0aca3cc81508c13070f3d489399132c9931c768ecc88890ee3e555b2c5f8` |

## Approval gates

### 1. Read-only development preflight

After approval, authenticate through the local masked PostgreSQL prompt to `127.0.0.1:5432/campus_sphere_dev` with the authorized existing local role. Use `psql -X -W -h 127.0.0.1 -p 5432 -d campus_sphere_dev -U postgres -v ON_ERROR_STOP=1` if `postgres` remains the approved operator. Start a `REPEATABLE READ READ ONLY` transaction. Confirm the exact database, loopback server address and port, PostgreSQL 19.0, role, public schema and read-only mode before reading application metadata. End with `ROLLBACK`.

Check successful history and compare V1–V7 against `57123013, -2074273504, 278880907, 1437205848, 1629970249, 1981309137, -1832066092` in order. If V8 exists, verify `-1533777827` and its catalog objects. Unexpected history, objects or checksums require review before continuing. Collect relevant domain-table counts, role-grant counts, database size and V7 audit-trigger state. If V8 is pending, count normalized email collision groups using `lower(btrim(email))`, check its audit-constraint prerequisite and assess its index-build lock window. Report aggregate figures and never query credential fields. No backup creation is included in this gate.

### 2. Backup and isolated verification

Approve backup creation and any isolated PostgreSQL database separately. Create a new timestamped and uniquely suffixed custom-format file under `D:\OLD Pankti\CampusSphereBackend\backups`, then inspect it with `pg_restore --list`, verify the source header is `campus_sphere_dev`, and calculate SHA-256. Use PostgreSQL 19 client tools and a masked password prompt. Restrict access to backups. Keep the verified archive and never overwrite existing backups.

```powershell
$bin = 'D:\Development\Database\PostgreSQL\19\bin'
$backup = Join-Path 'D:\OLD Pankti\CampusSphereBackend\backups' (
    'campus_sphere_dev-before-student-v13-{0}-{1}.dump' -f (Get-Date -Format 'yyyyMMdd-HHmmss'),
    ([guid]::NewGuid().ToString('N').Substring(0, 8)))
if (Test-Path -LiteralPath $backup) { throw 'Backup destination already exists.' }
& "$bin\pg_dump.exe" -W -h 127.0.0.1 -p 5432 -U postgres -d campus_sphere_dev -Fc -f $backup
if ($LASTEXITCODE -ne 0) { throw 'Backup creation failed.' }
$toc = & "$bin\pg_restore.exe" --list $backup
if ($LASTEXITCODE -ne 0 -or -not ($toc -match '^;\s+dbname:\s+campus_sphere_dev\s*$')) {
    throw 'Backup readability or source check failed.'
}
Get-FileHash -Algorithm SHA256 -LiteralPath $backup
```

Separately approve creation of `campus_sphere_student_e2e` as a disposable loopback PostgreSQL target for synthetic fixtures and the first real integration run. This is distinct from the Phase 10 rehearsal and existing databases. Rehearse the migration chain from the confirmed development baseline and validate PostgreSQL schema and actual API execution. A backup restore drill, if approved, uses a different explicitly authorized destination. Listing the backup establishes readability and source identity.

### 3. Development migration

After isolated success and separate rollout approval, confirm development identity and backup SHA-256 again in a maintenance window with application writes paused. Prepare a single-purpose operator runner or reviewed equivalent. The current startup guard rejects pending development migrations V4 and higher, and the existing Phase 9 runner targets a different database. The operator path must pin the JDBC URL to `jdbc:postgresql://127.0.0.1:5432/campus_sphere_dev`, verify server identity and Flyway history, accept only the approved pending set through V13, check source hashes, and acquire a migration lock before executing. Keep the normal guard and Flyway history protection in place. If V8 is pending, include it only after its checks and explicit approval.

Validate history and catalog after execution. Compare all old checksums and application counts, verify tenant foreign keys and partial unique indexes, and confirm audit protection. Recalculate the backup hash. Start the correct compiled backend on `127.0.0.1:8080` with explicitly pinned database identity, `spring.flyway.enabled=false`, `spring.jpa.hibernate.ddl-auto=validate` and the registration expiry job off for the rehearsal. Stop on any failure. Inspect committed history and the catalog read-only before considering a further action. Restore, repair and destructive rollback require separate approval.

### 4. Live workflow and persistence

Use two synthetic colleges on the approved integration target. Proposed fixture identities are `Student Rollout Alpha` (`SME-ALPHA`) and `Student Rollout Beta` (`SME-BETA`), College Admins `sme-alpha-admin@example.invalid` and `sme-beta-admin@example.invalid`, and Student accounts `sme-alpha-student@example.invalid` and `sme-beta-student@example.invalid`. Choose passwords privately. Create one department, program, academic year, semester and division per college. Approve fixture creation separately with an exact setup method and counts. Prefer the disposable integration database for fixture writes. A live pass against development with new fixture records requires a separate grant. Endings performed in the flow affect only approved synthetic records. Leave them as evidence unless separate cleanup is approved.

1. Register and sign in as each synthetic Student using the normal API. Submit an affiliation and verify it appears only in the chosen college queue. Verify pending Student enrollment access is denied and an invalid college code creates no request.
2. Sign in as each College Admin. Verify wrong-college lookups return 404 and unauthorized roles and malformed tokens receive the expected 403 or 401. Attempt approval without admission confirmation and verify it fails without binding the student. Confirm the record and approve from the correct tenant. Verify the account is verified, bound to that college, and still has only the Student role. Check the pending app refresh path after approval.
3. Assign the matching department. Try foreign department, year, semester or division IDs and an unexpected ownership field. Use the real Pixel 6 UI to enroll the student and check that the correct academic details appear on the Student dashboard. Attempt duplicate enrollment via the authenticated backend as well as the UI. Race duplicate requests on the disposable target and verify exactly one current row.
4. Compare safe PostgreSQL counts and matched IDs before and after. Restart the scoped backend and restart the debug app without clearing storage. Verify the Student dashboard and College Admin history retrieve the same row. End enrollment, restart, and verify the Student dashboard is empty while the College Admin still sees the ended row. Confirm Beta cannot read or mutate Alpha history.
5. Stop only the identified integration backend process to test connection failure and protect unrelated services. Verify the app handles the outage without showing protected stale data. Restart the same backend and verify recovery. Keep normal security checks enabled throughout.
6. Run the focused backend and Android tests and the Pixel 6 walkthrough. Keep the current synthetic visual suite as regression evidence. Capture a separate live screenshot set using approved generic fixture data, with credentials and tokens excluded. Update the demonstration guide only after the real pass.

## Isolated verification result

Gate 1 verified the V1–V7 development baseline read-only. The approved backup was restored to `campus_sphere_student_e2e`, and V8–V13 passed there under the separately approved standalone runner. The live Student workflow and the resumed outage and history walkthroughs used PostgreSQL 19.0 on that isolated database.

The first run verified enrollment and restart persistence, then timed out waiting for the Android outage message. The resumed test used fresh credentials for only the two approved synthetic Alpha accounts. A comparison within the password reset transaction verified all affiliation, enrollment, and audit records were identical before and after it. The corrected test observed the live outage, hid the stale enrollment, and refreshed successfully after restart. Ending enrollment retained the same record ID. After another backend and Android restart, College Admin history still showed the ended record and the Student current-enrollment endpoint returned an empty list. Final read-only counts showed two approved affiliations, one student profile and one historical enrollment, with no current enrollment. V1–V13 and the original Super Admin and audit record were verified again.

Development rollout has a separate approval gate. Attendance remains outside this phase.
