# Phase 9C isolated rehearsal runner

The runner is a standalone Java entry point. It does not start Spring. Review Phase9RehearsalRunner.java and Phase9RehearsalRunnerTest.java in the backend operator packages and inspect the reviewed V1–V8 SQL before granting execution approval.

Run the offline checks from the backend root:

    .\mvnw.cmd -o "-Dtest=Phase9RehearsalRunnerTest,FlywayDatabaseGuardTest" test

The tests use mocks for orchestration and read the actual SQL files for digest verification. They do not open a database connection or call Flyway.

## Commands for separate operator approvals

Creating the empty database requires its own approved procedure. This runner expects it to already exist. Before invoking either stage, confirm the intended local PostgreSQL 19.0 instance, the literal rehearsal database, the public schema, and exclusive use of the rehearsal database. Stage A and Stage B require separate execution approvals and separate local console invocations.

After a fresh successful offline run, use its Surefire report to construct the locally resolved classpath. Start from the backend project in an interactive PowerShell console:

    Set-Location -LiteralPath 'D:\OLD Pankti\CampusSphereBackend\campus-sphere-backend'
    $phase9Report = [xml](Get-Content -Raw -LiteralPath 'target\surefire-reports\TEST-com.campussphere.backend.operator.Phase9RehearsalRunnerTest.xml')
    $phase9ClassPath = (($phase9Report.testsuite.properties.property |
        Where-Object name -eq 'java.class.path').value -split ';' |
        Where-Object { $_ -notmatch '[\\/]target[\\/]test-classes[\\/]?$' }) -join ';'
    if (-not $phase9ClassPath) { throw 'Offline test classpath missing' }
    $phase9Java = 'D:\Development\Tools\JDK21\bin\java.exe'
    $phase9Main = 'com.campussphere.backend.operator.Phase9RehearsalRunner'

Stage A only, after approval:

    & $phase9Java -cp $phase9ClassPath $phase9Main baseline campus_sphere_phase9_rehearsal
    if ($LASTEXITCODE) { throw 'Baseline stopped' }

Confirm with APPLY BASELINE campus_sphere_phase9_rehearsal, then enter the PostgreSQL password at the masked prompt. The runner verifies every authenticated JDBC connection against the fixed database, address, port, version and schema. It checks for an empty public schema, exposes only V1–V7 to Flyway, verifies their approved source checksums, and checks successful history, five seeded roles and empty application tables after migration. Record the successful Stage A completion before seeking Stage B approval.

Stage B only, after separate approval:

    & $phase9Java -cp $phase9ClassPath $phase9Main v8 campus_sphere_phase9_rehearsal
    if ($LASTEXITCODE) { throw 'V8 rehearsal stopped' }

Confirm with APPLY V8 campus_sphere_phase9_rehearsal. The runner rechecks the complete Stage A database state on its own invocation and requires exactly V8 pending. It verifies the V8 SHA-256 before opening JDBC, applies V8, and checks Flyway validation, history, empty data, key indexes, constraints and the audit trigger. A session advisory lock prevents two copies of this runner from executing concurrently. It does not coordinate with unrelated tools, so keep other writers and migration tools off the rehearsal database.

Failure returns a nonzero exit and a sanitized diagnostic. A post-migration validation failure can happen after SQL has committed. Inspect the rehearsal state read-only before considering another action. The runner performs no automatic retry, repair, restore or database cleanup. It removes only its temporary SQL snapshots. Further database testing, fixture creation and cleanup each require separately approved scope.

## Failure diagnostics

The runner prints the path of a local temporary diagnostic file before prompting. If the console closes after a failure, look in the process temporary directory for the newest campussphere-phase9-diagnostic-*.log. Successful runs leave the reserved file empty. The report shows the checkpoint, JDBC connection substep, cause chain, suppressed and linked SQL exceptions, and SQLState/vendor codes. It retains exact runner-authored refusal messages. External messages are redacted because JDBC and Flyway can embed SQL, values or connection details in them.

migrateInvoked means the runner entered Flyway's migrate call. migrationScriptReached means Flyway emitted its before-statement callback. It does not establish that a statement ran or committed. jdbcRollbackAttempted tracks calls through the runner's JDBC connections. It does not account for server-side rollback after a disconnect. Once migrateInvoked is true, inspect the rehearsal database read-only after a failure even if a rollback was observed.

If Java cannot launch or load the entry point at all, the runner cannot create a report. Keep the interactive PowerShell console open and check its launcher error and exit code. When launching through a wrapper, retain the console or redirect only Java stderr to a private local file, leaving stdin and stdout attached for System.console(). Do not repeat a failed invocation to obtain its diagnostic.
