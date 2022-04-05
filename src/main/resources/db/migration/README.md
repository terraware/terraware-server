# Database migrations

The server uses [Flyway](https://flywaydb.org) to create and modify the database schema. See the Flyway documentation for full details about how it works, but briefly:

* Numbered migrations, whose filenames are prefixed with `V`, are run in numeric order. Once run, a numbered migration is never run again.
* Replayable migrations, whose filenames are prefixed with `R`, are run whenever they are modified or created. They should be idempotent. They are run after numbered migrations.

The migrations are split up into subdirectories to allow finer control over how they're applied in different environments:

| Subdirectory | Description |
| --- | --- |
| `common` | Scripts that only use standards-compliant SQL syntax. The vast majority of migrations should live here. |
| `dev` | Scripts that are only executed in development environments. Typically these will insert dummy data for local testing. Scripts in here should all be replayable, not numbered. |
| `generic` | Standards-compliant versions of database-specific scripts. There should be one of these corresponding to each database-specific numbered script. |
| `postgres` | Scripts that use PostgreSQL-specific syntax. |

## Support for multiple database engines

The project's officially supported database is PostgreSQL, and it freely uses PostgreSQL-specific functionality as needed.

In anticipation of users wanting to run the code with other database engines, you should make reasonable attempts to segregate PostgreSQL-specific schema elements into separate migrations in the `postgres` subdirectory.

If there is a straightforward alternative using standards-compliant syntax (for example, using a text column instead of a JSON column), create a migration script with the same version number in the `generic` directory. If not, still create a script to keep the numbering consistent, but just include a comment describing what's missing and why.

## Migrations and the build process

Currently, the build process creates a temporary database and runs all the migrations from the beginning before examining the database schema to do code generation. That helps protect against people making manual schema changes and then unwittingly writing migrations that depend on those manual steps.

If the build system detects that the migration scripts haven't changed, it skips running migrations and doing code generation.
