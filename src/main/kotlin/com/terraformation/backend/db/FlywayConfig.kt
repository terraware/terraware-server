package com.terraformation.backend.db

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.CoreErrorCode
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class FlywayConfig {
  /**
   * Version numbers of migrations that have been edited after they were originally applied in
   * production.
   *
   * Numbered migrations, as a rule, should never be edited after they're already applied. But in
   * some cases, existing migrations that were applied successfully can become invalid after the
   * fact, e.g., if they use SQL syntax that stops being supported in newer database versions.
   *
   * In cases like that, we still need to run the migrations on empty databases as part of the build
   * process, so they need to be edited to be valid. But we don't want Flyway to flag the modified
   * migration scripts as validation errors in environments where the migrations were already
   * applied; it should continue to treat them as if they're already complete.
   */
  private val editedMigrationVersions =
      setOf(
          "134",
      )

  /**
   * Configures Flyway to accept edits to the migrations in [editedMigrationVersions] when it
   * validates all the migrations at server start time.
   */
  @Bean
  fun repairChecksumsOfEditedMigrations(): FlywayMigrationStrategy {
    return FlywayMigrationStrategy { flyway: Flyway ->
      val checksumErrors =
          flyway.validateWithResult().invalidMigrations.filter {
            it.errorDetails.errorCode == CoreErrorCode.CHECKSUM_MISMATCH
          }
      if (
          checksumErrors.isNotEmpty() &&
              checksumErrors.all { it.version in editedMigrationVersions }
      ) {
        flyway.repair()
      }

      flyway.migrate()
    }
  }
}
