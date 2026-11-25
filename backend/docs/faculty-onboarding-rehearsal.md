# Faculty onboarding PostgreSQL rehearsal

The rehearsal accepts only the new local database `campus_sphere_faculty_e2e`, owned by `postgres` on PostgreSQL 19.0 at `127.0.0.1:5432`. Database creation and migration require explicit approval. The migration runner assumes an existing empty database and does not create, clean, restore, or repair anything.

## 1. Offline verification

From the backend directory, run:

```powershell
.\mvnw.cmd -o '-Dcampussphere.faculty.rehearsal=offline' '-Dtest=FacultyRehearsalMigrationRunnerTest,FacultyOnboardingRehearsalIT,StudentE2eMigrationRunnerTest' test
```

The live test is intentionally skipped in this invocation. The runner tests check the exact target argument and the reviewed SHA-256 hashes of the fourteen source files. The V14 SHA-256 is `61e6adf043a75c237588b1743400791d9f08571ba85df859b5a3abee3e4b7a77`.

## 2. Empty schema migration after approval

Build the local classpath from the backend directory. Run the main class from a visible PowerShell console so the JDBC password stays in a masked prompt. Keep any output local and stop on the first refusal.

```powershell
.\mvnw.cmd -o -DskipTests test-compile dependency:build-classpath '-Dmdep.outputFile=target/faculty-rehearsal.classpath'
if ($LASTEXITCODE -ne 0) { throw 'Could not build the offline rehearsal classpath.' }
$facultyClasspath = 'target/test-classes;target/classes;' + (Get-Content -Raw target/faculty-rehearsal.classpath).Trim()
$facultyRunner = 'com.campussphere.backend.operator.FacultyRehearsalMigrationRunner'
& "$env:JAVA_HOME/bin/java.exe" -cp $facultyClasspath $facultyRunner verify-source
if ($LASTEXITCODE -ne 0) { throw 'Faculty migration source verification failed.' }
# Run only after the empty database and migrations are explicitly approved.
& "$env:JAVA_HOME/bin/java.exe" -cp $facultyClasspath $facultyRunner migrate campus_sphere_faculty_e2e
```

Each connection verifies database identity before use. The runner checks an advisory lock and empty catalog, uses only the reviewed V1–V14 bytes, checks pending versions, verifies installed checksums with Flyway, and confirms V14 constraints, indexes, seeded roles and the empty application baseline inside a read-only verification transaction. If failure occurs after migration begins, inspect the rehearsal database read-only before deciding what to do.

## 3. Rollback-only HTTP test after fixture approval

Use `FacultyOnboardingRehearsalIT` with `-Dcampussphere.faculty.rehearsal=approved` and a masked local mechanism supplying `DB_PASSWORD` and a generated process-local `JWT_SECRET`. The test binds the JDBC URL explicitly, disables Flyway and expiry scheduling, and keeps Hibernate schema validation enabled. MockMvc invokes the real Spring Security, controllers and services using signed JWTs and PostgreSQL. It runs on one transaction thread, rolls back fixture rows, and may advance sequence positions. It does not establish restart persistence or concurrent request behavior.

The fixture plan uses two synthetic colleges (`FAC-ALPHA` and `FAC-BETA`), two College Admins (`alpha-admin@example.invalid` and `beta-admin@example.invalid`), one legacy Admin (`legacy-admin@example.invalid`), and one public applicant (`faculty-applicant@example.invalid`). A matching Alpha department, program, year, semester, division and subject support one assignment. The test checks pending denial, cross-college and legacy denial, the employment confirmation requirement, repeat decision rejection, role and college binding, and the Faculty assignment response.

The separate live Pixel 6 check requires its own authorized, persistent synthetic records. Repeat the main approval and assignment flow from the normal Android app, verify denial for the wrong college, test rejection and reapplication, and confirm the correct assignments after a backend restart. Keep credentials and tokens out of screenshots and logs.
