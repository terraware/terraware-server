# Database migrations

The server uses [Flyway](https://flywaydb.org) to create and modify the database schema. See the Flyway documentation for full details about how it works, but briefly:

* Subdirectories are recursively scanned for migration scripts.
* Numbered migrations, whose filenames are prefixed with `V`, are run in numeric order. Once run, a numbered migration is never run again.
* Replayable migrations, whose filenames are prefixed with `R`, are run whenever they are modified or created. They should be idempotent. They are run after numbered migrations.

## Migration subdirectories and naming

The migrations for production systems all live under this directory. They are numbered based on the dates and times they were added. There are two subdirectory levels for year and month, and then under that, the migrations should be named like `V<year><month><day>.<hour><minute>__<title>.sql`. For example, `2026/07/V20260711.1156__AddSomething.sql`.  The timestamp can be in your local time zone.

The timestamps are intended to be granular enough to make it unlikely that two people working in parallel would add migrations with exactly the same version number.

### Migration order

In the case where multiple people are adding migrations, it's possible for the migrations to be applied out of order. For example, if pull request #1 adds a `V20260711.1037` migration, pull request #2 adds `V20260712.1152`, and #2 is merged first, the later-numbered migration will have already been applied when pull request #1 is merged and deployed.

We configure Flyway to allow out-of-order migrations, and in the typical case where the two migrations make unrelated changes, there's no issue. But you should keep an eye out for situations where two migrations make changes that either conflict or produce functionally different results depending on what order they're applied. In that case, you may need to renumber one of the migrations to ensure they're applied in a consistent order.

## Database features

The server runs on PostgreSQL and there are no plans to support multiple database engines.

It is fine to use PostgreSQL-only syntax in migrations. However, note that the production environment uses a hosted database which is generally not the latest PostgreSQL version. If you are using PostgreSQL-specific features, make sure they exist in the production PostgreSQL version.

## Development-only migrations

There is a `dev` subdirectory that has migrations that should only be applied in dev environments (e.g., to insert dummy data for testing). Scripts in `dev` should be replayable, not numbered, so they don't pollute the migration number sequence.

## Migrations and the build process

Currently, the build process creates a temporary database and runs all the migrations from the beginning before examining the database schema to do code generation. That helps protect against people making manual schema changes and then unwittingly writing migrations that depend on those manual steps.

If the build system detects that the migration scripts haven't changed, it skips running migrations and doing code generation.

## Older migrations

We moved to timestamp-based migration numbering after there were already several hundred migrations in place. The older migrations used a sequential numbering scheme. They are applied in order before the timestamp-based migrations.
