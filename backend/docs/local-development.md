# Local backend development

Use Java 21 and PostgreSQL 19. The approved local database owner is the
existing postgres role. The development and test databases are separate.
The postgres login can access both, so use a separate launch configuration
for each and let the Flyway database guard check the actual destination.

## Database bootstrap

Connect to the postgres maintenance database using the existing password:

```powershell
psql -X -W -h 127.0.0.1 -p 5432 -U postgres -d postgres -v ON_ERROR_STOP=1
```

Verify the server version and check both names before creating either
database. Create each database only if its name is unused. Run CREATE DATABASE
outside a transaction.

```sql
SELECT current_setting('server_version'), inet_server_port();
SELECT datname FROM pg_database WHERE datname IN ('campus_sphere_dev', 'campus_sphere_test');

CREATE DATABASE campus_sphere_dev OWNER postgres TEMPLATE template0;
CREATE DATABASE campus_sphere_test OWNER postgres TEMPLATE template0;
```

## Launch settings

| Development variable | Test variable | Value |
| --- | --- | --- |
| DB_URL | TEST_DB_URL | JDBC URL for the corresponding database below |
| DB_USERNAME | TEST_DB_USERNAME | postgres |
| DB_PASSWORD | TEST_DB_PASSWORD | Existing database password |
| JWT_SECRET | TEST_JWT_SECRET | Separate Base64 signing keys, each at least 32 bytes after decoding |

The development URL is `jdbc:postgresql://127.0.0.1:5432/campus_sphere_dev`.
The test URL is `jdbc:postgresql://127.0.0.1:5432/campus_sphere_test`.
Supply secrets through the launch environment and keep them out of source files.

## Verify test before development

Confirm that Maven 3.10.0 and the required dependencies are available locally
before invoking the wrapper. The script-only wrapper can download Maven
before offline mode takes effect.

```powershell
.\mvnw.cmd -o dependency:tree "-Dincludes=org.springframework.boot:spring-boot-starter-flyway,org.flywaydb:*,org.postgresql:postgresql"
.\mvnw.cmd -o "-Dtest=CampusSphereBackendApplicationTests" test
```

Confirm Flyway 11.14.1, PostgreSQL 19, two successful migrations and one row
per role. Run the test again to verify that migration history and seed counts
stay stable. The repository test rolls back the user and its role assignment.
Only after test verification passes, start the development backend:

```powershell
.\mvnw.cmd -o spring-boot:run
```

Flyway creates the schema and role rows. Hibernate validates the mappings.
Creating the first Admin account is a separate operation.
