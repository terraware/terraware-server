# Database migrations

The server uses [Flyway](https://flywaydb.org) to create and modify the database schema. See the Flyway documentation for full details about how it works, but briefly:

* Numbered migrations, whose filenames are prefixed with `V`, are run in numeric order. Once run, a numbered migration is never run again.
* Replayable migrations, whose filenames are prefixed with `R`, are run whenever they are modified or created. They should be idempotent. They are run after numbered migrations.

The migrations for production systems all live in this directory.

The server runs on PostgreSQL and there are no plans to support multiple database engines. It is fine to use PostgreSQL-only syntax in migrations.

## Development-only migrations

There is a `dev` subdirectory that has migrations that should only be applied in dev environments (e.g., to insert dummy data for testing). Scripts in `dev` should be replayable, not numbered, so they don't pollute the migration number sequence.

## Migrations and the build process

Currently, the build process creates a temporary database and runs all the migrations from the beginning before examining the database schema to do code generation. That helps protect against people making manual schema changes and then unwittingly writing migrations that depend on those manual steps.

If the build system detects that the migration scripts haven't changed, it skips running migrations and doing code generation.
