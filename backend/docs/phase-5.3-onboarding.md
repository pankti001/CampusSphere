# Phase 5.3 migration and operator notes

## Test migration preflight

After approval, target only `campus_sphere_test`. Verify the effective local host, port, database name, schema, and successful V1–V5 history. Verify existing college codes match the V6 uppercase format and names contain a non-whitespace character. Report incompatible rows and stop before applying V6. Take a separate backup, list the archive, and record its checksum. Apply V6 and V7 in order with the development override disabled. Validate the schema and compare migration history and record counts with the baseline.

The Phase 5.3 integration class disables Flyway and expects V1–V7 already present. Run it only after verifying that the effective datasource connects exclusively to the approved test database. Its fixtures are synthetic and transactional. Rollback discards the rows, although PostgreSQL identity sequences can advance. Disable Flyway explicitly when running the older database-backed suites as post-migration regression tests. Separate approval is required for any concurrency rehearsal that needs committed fixtures or a disposable database.

## First Super Admin

After explicit provisioning approval and a separately approved development rollout, run `com.campussphere.backend.operator.FirstSuperAdminProvisioning` as a standalone Java entry point using the built application classes and runtime dependencies, with `campus_sphere_dev` as its only argument. Use an interactive local console. The command expects PostgreSQL 19 on IPv4 loopback port 5432 and successful V1–V7 history. It starts no Spring context and has no migration path. Verify the selected institutional email independently before entering it. Check the account, single role grant, and corresponding audit event after success. Never include console secrets or passwords in tickets, command lines, or log captures.

The command uses the verified local PostgreSQL role (`postgres`) and prompts for its password once with masked input. It opens one JDBC connection, clears the database password array, and uses that connection to verify the database and role, lock and check Flyway history, check for existing accounts and grants, and run the provisioning transaction.

The first Super Admin operator command requires a nonblank password with at least 10 Java characters and at most 72 UTF-8 bytes. Confirmation must match exactly. The command accepts uppercase and lowercase letters, digits, and special characters and hashes the password with BCrypt.

### Provisioning diagnostics

Retain only the Java process exit code and non-sensitive read-only verification results in the operator report. Invoke Java directly in the interactive console and read PowerShell's `$LASTEXITCODE` immediately afterward. Leave the console input and output out of the report. The exit codes map to these fixed diagnostic identifiers.

| Exit | Diagnostic |
| --- | --- |
| 0 | Command reported success. Confirm with read-only checks. |
| 2 | `PROVISION_INVALID_INVOCATION` |
| 10 | `PASSWORD_MISSING` |
| 11 | `PASSWORD_CONFIRMATION_MISMATCH` |
| 12 | `PROVISION_DATABASE_CONNECTION` |
| 13 | `PROVISION_DATABASE_SAFETY` |
| 14 | `PROVISION_ALREADY_PROVISIONED` |
| 15 | `PROVISION_DUPLICATE_EMAIL` |
| 16 | `PROVISION_TRANSACTION_FAILURE` |
| 17 | `PROVISION_INTERNAL_FAILURE` |
| 18 | `PROVISION_INVALID_IDENTITY_INPUT` |
| 19 | `PASSWORD_EMPTY` |
| 20 | `PASSWORD_TOO_SHORT` |
| 21 | `PASSWORD_TOO_LONG` |
| 22 | `PROVISION_DATABASE_AUTHENTICATION` |
| 23 | `PROVISION_DATABASE_CONNECTION_REJECTED` |
| 24 | `PROVISION_DATABASE_TRANSPORT` |
| 25 | `PROVISION_DATABASE_PASSWORD_MISSING` |

`PASSWORD_VALID` is a console status after the password check and BCrypt hashing. Account creation still requires subsequent database verification.

Verify the user, single role grant, and matching provisioning audit event before any retry. A transaction or connection cleanup failure can leave the outcome uncertain to the caller.

## Deployment gates

Keep invitation issue and acceptance behind HTTPS. Configure the ingress to rate-limit redemption before exposing it publicly. Keep body capture disabled for issuance and redemption in proxies, application logs, tracing, and analytics. Use a verified institutional address and a secure manual delivery channel. A lost raw code requires revocation and reissue.

Use a separate migration/owner role and a least-privilege application role for deployment. The application role needs audit INSERT and sequence usage for audit writes. Grant audit reads only to an authorized audit or operator role. Keep audit update, delete, truncate, DDL, and trigger management outside the runtime role. This phase creates no database roles. The append-only trigger rejects normal UPDATE, DELETE, and TRUNCATE statements, but schema owners and superusers can change database protections.
