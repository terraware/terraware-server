package com.terraformation.backend.db

import com.terraformation.backend.db.SchemaDocsGenerator.Slice.ALL
import com.terraformation.backend.db.SchemaDocsGenerator.Slice.CUSTOMER
import com.terraformation.backend.db.SchemaDocsGenerator.Slice.DEVICE
import com.terraformation.backend.db.SchemaDocsGenerator.Slice.NURSERY
import com.terraformation.backend.db.SchemaDocsGenerator.Slice.SEEDBANK
import com.terraformation.backend.db.SchemaDocsGenerator.Slice.SPECIES
import com.terraformation.backend.db.SchemaDocsGenerator.Slice.TRACKING
import com.terraformation.backend.log.perClassLogger
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.exists
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.opentest4j.TestAbortedException
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.utility.DockerImageName

/**
 * Generates a series of directories with self-contained static HTML database schema documentation.
 *
 * ## How to run the generator
 *
 * ```
 * mkdir -p docs/schema
 * SCHEMA_DOCS_DIR=docs/schema ./gradlew test --tests SchemaDocsGenerator
 * ```
 *
 * ## Slices
 *
 * This class defines a set of "slices" each of which is a subset of the full database schema. A
 * separate schema document is created for each slice. Aside from the special "ALL" slice which has
 * just about every table, the goal should be to keep each slice focused on a specific subject area:
 * someone who is looking at the data model for seeds probably doesn't care how timeseries values
 * are represented.
 *
 * Tables can, and do, appear in multiple topic-specific slices. For example, most slices have the
 * `projects` table because nearly all the data in our system is indirectly tied to a project. But
 * most slices don't include the `project_types` reference table, even though `projects` has a
 * foreign key reference to it, because the existence of that reference table is not relevant for
 * someone who's focused on seed banking or tree tracking.
 *
 * If you want to generate docs for a subset of the slices, set the `SCHEMA_DOCS_SLICES` environment
 * variable to a comma-delimited list of the ones you want.
 *
 * ## Implementation notes
 *
 * Doc generation isn't super fast, so we skip it by default and only enable it if an environment
 * variable is set.
 *
 * The actual doc generation is handled by [SchemaSpy](https://schemaspy.org/); this class invokes
 * SchemaSpy using its official Docker image.
 *
 * This is a JUnit test because it's a convenient way to make use of the existing infrastructure for
 * running our schema migrations and connecting to a database in the CI environment. And there is a
 * real test method here, too, that ensures we don't add tables to the schema and forget to
 * configure where they should appear in our docs.
 *
 * SchemaSpy populates each doc subdirectory with a bunch of minified JavaScript code, font files,
 * and other such things. These don't take up additional space in the git history because their
 * contents are identical across subdirectories.
 */
class SchemaDocsGenerator : DatabaseTest() {
  /** Docker image to use to generate docs. */
  private val schemaSpyDockerImage = DockerImageName.parse("schemaspy/schemaspy:6.1.0")

  /**
   * Name of environment variable specifying where the schema subdirectories should live. If not
   * set, doc generation is skipped.
   */
  private val docsDirEnvVar = "SCHEMA_DOCS_DIR"

  /**
   * Name of environment variable specifying the list of slices to generate. Value is a
   * comma-separated list of slice subdirectory names, e.g., `customer,device`. If not set, all
   * slices are generated.
   */
  private val slicesEnvVar = "SCHEMA_DOCS_SLICES"

  /**
   * How long to let doc generation run. This should be long enough to account for slow GitHub
   * Actions runners.
   */
  private val timeout = Duration.ofMinutes(10)!!

  private val log = perClassLogger()

  /**
   * Defines the set of schema slices. Each slice gets turned into a separate documentation
   * subdirectory. A given table can appear in multiple directories, or none at all.
   */
  enum class Slice(val subdirectory: String) {
    /**
     * The full schema. Doesn't include tables that are needed by libraries but not really part of
     * the application's data model, e.g., the Flyway migration history table. But everything else
     * should be in this slice.
     */
    ALL("all"),
    CUSTOMER("customer"),
    DEVICE("device"),
    NURSERY("nursery"),
    SEEDBANK("seedbank"),
    SPECIES("species"),
    TRACKING("tracking"),
  }

  /**
   * Which slices each table should appear in. System tables such as the Flyway migration history
   * table don't appear in our schema docs since they aren't part of our application's data model.
   */
  private val tableSlices =
      mapOf(
          "nursery" to
              mapOf(
                  "batches" to setOf(ALL, NURSERY),
                  "batch_quantity_history" to setOf(ALL, NURSERY),
                  "batch_quantity_history_types" to setOf(ALL, NURSERY),
                  "batch_withdrawals" to setOf(ALL, NURSERY),
                  "withdrawal_photos" to setOf(ALL, NURSERY),
                  "withdrawal_purposes" to setOf(ALL, NURSERY),
                  "withdrawals" to setOf(ALL, NURSERY),
              ),
          "public" to
              mapOf(
                  "app_versions" to setOf(ALL, CUSTOMER),
                  "automations" to setOf(ALL, DEVICE),
                  "countries" to setOf(ALL, CUSTOMER),
                  "country_subdivisions" to setOf(ALL, CUSTOMER),
                  "devices" to setOf(ALL, DEVICE),
                  "device_managers" to setOf(ALL, CUSTOMER, DEVICE),
                  "device_template_categories" to setOf(ALL, DEVICE),
                  "device_templates" to setOf(ALL, DEVICE),
                  "ecosystem_types" to setOf(ALL, SPECIES),
                  "facilities" to setOf(ALL, CUSTOMER, DEVICE, SEEDBANK),
                  "facility_connection_states" to setOf(ALL, CUSTOMER, DEVICE),
                  "facility_types" to setOf(ALL, CUSTOMER),
                  "files" to setOf(ALL, CUSTOMER, NURSERY, SEEDBANK),
                  "flyway_schema_history" to emptySet(),
                  "gbif_distributions" to setOf(SPECIES),
                  "gbif_names" to setOf(SPECIES),
                  "gbif_name_words" to setOf(SPECIES),
                  "gbif_taxa" to setOf(SPECIES),
                  "gbif_vernacular_names" to setOf(SPECIES),
                  "growth_forms" to setOf(ALL, SEEDBANK),
                  "identifier_sequences" to setOf(ALL, SEEDBANK, NURSERY),
                  "internal_tags" to setOf(ALL, CUSTOMER),
                  "notification_criticalities" to setOf(ALL, CUSTOMER),
                  "notification_types" to setOf(ALL, CUSTOMER),
                  "notifications" to setOf(ALL, CUSTOMER),
                  "organization_internal_tags" to setOf(ALL, CUSTOMER),
                  "organization_users" to setOf(ALL, CUSTOMER),
                  "organizations" to setOf(ALL, CUSTOMER, DEVICE, SEEDBANK, SPECIES),
                  "report_files" to setOf(ALL, CUSTOMER),
                  "report_photos" to setOf(ALL, CUSTOMER),
                  "report_statuses" to setOf(ALL, CUSTOMER),
                  "reports" to setOf(ALL, CUSTOMER),
                  "roles" to setOf(ALL, CUSTOMER),
                  "seed_storage_behaviors" to setOf(ALL, SEEDBANK),
                  "spatial_ref_sys" to emptySet(),
                  "species" to setOf(ALL, SEEDBANK, SPECIES),
                  "species_ecosystem_types" to setOf(ALL, SPECIES),
                  "species_problem_fields" to setOf(ALL, SPECIES),
                  "species_problem_types" to setOf(ALL, SPECIES),
                  "species_problems" to setOf(ALL, SPECIES),
                  "spring_session" to emptySet(),
                  "spring_session_attributes" to emptySet(),
                  "test_clock" to setOf(ALL),
                  "thumbnails" to setOf(ALL, SEEDBANK),
                  "timeseries" to setOf(ALL, DEVICE),
                  "timeseries_types" to setOf(ALL, DEVICE),
                  "timeseries_values" to setOf(ALL, DEVICE),
                  "time_zones" to setOf(ALL),
                  "uploads" to setOf(ALL, CUSTOMER),
                  "upload_problems" to setOf(ALL, CUSTOMER),
                  "upload_problem_types" to setOf(ALL, CUSTOMER),
                  "upload_statuses" to setOf(ALL, CUSTOMER),
                  "upload_types" to setOf(ALL, CUSTOMER),
                  "user_preferences" to setOf(ALL, CUSTOMER),
                  "user_types" to setOf(ALL, CUSTOMER),
                  "users" to setOf(ALL, CUSTOMER),
              ),
          "seedbank" to
              mapOf(
                  "accession_collectors" to setOf(ALL, SEEDBANK),
                  "accession_photos" to setOf(ALL, SEEDBANK),
                  "accession_quantity_history" to setOf(ALL, SEEDBANK),
                  "accession_quantity_history_types" to setOf(ALL, SEEDBANK),
                  "accession_state_history" to setOf(ALL, SEEDBANK),
                  "accession_states" to setOf(ALL, SEEDBANK),
                  "accessions" to setOf(ALL, SEEDBANK),
                  "bags" to setOf(ALL, SEEDBANK),
                  "collection_sources" to setOf(ALL, SEEDBANK),
                  "data_sources" to setOf(ALL, SEEDBANK),
                  "geolocations" to setOf(ALL, SEEDBANK),
                  "seed_quantity_units" to setOf(ALL, SEEDBANK),
                  "storage_locations" to setOf(ALL, SEEDBANK),
                  "viability_test_results" to setOf(ALL, SEEDBANK),
                  "viability_test_seed_types" to setOf(ALL, SEEDBANK),
                  "viability_test_substrates" to setOf(ALL, SEEDBANK),
                  "viability_test_treatments" to setOf(ALL, SEEDBANK),
                  "viability_test_types" to setOf(ALL, SEEDBANK),
                  "viability_tests" to setOf(ALL, SEEDBANK),
                  "withdrawal_purposes" to setOf(ALL, SEEDBANK),
                  "withdrawals" to setOf(ALL, SEEDBANK),
              ),
          "tracking" to
              mapOf(
                  "deliveries" to setOf(ALL, TRACKING),
                  "monitoring_plots" to setOf(ALL, TRACKING),
                  "observable_conditions" to setOf(ALL, TRACKING),
                  "observation_photo_positions" to setOf(ALL, TRACKING),
                  "observation_photos" to setOf(ALL, TRACKING),
                  "observation_plot_conditions" to setOf(ALL, TRACKING),
                  "observation_plots" to setOf(ALL, TRACKING),
                  "observation_states" to setOf(ALL, TRACKING),
                  "observations" to setOf(ALL, TRACKING),
                  "observed_plot_species_totals" to setOf(ALL, TRACKING),
                  "observed_site_species_totals" to setOf(ALL, TRACKING),
                  "observed_zone_species_totals" to setOf(ALL, TRACKING),
                  "planting_types" to setOf(ALL, TRACKING),
                  "planting_site_populations" to setOf(ALL, TRACKING),
                  "planting_sites" to setOf(ALL, TRACKING),
                  "planting_zone_populations" to setOf(ALL, TRACKING),
                  "planting_zones" to setOf(ALL, TRACKING),
                  "planting_subzone_populations" to setOf(ALL, TRACKING),
                  "planting_subzones" to setOf(ALL, TRACKING),
                  "plantings" to setOf(ALL, TRACKING),
                  "recorded_plant_statuses" to setOf(ALL, TRACKING),
                  "recorded_plants" to setOf(ALL, TRACKING),
                  "recorded_species_certainties" to setOf(ALL, TRACKING),
              ),
      )

  @EnumSource(Slice::class)
  @ParameterizedTest
  fun generateDocs(slice: Slice) {
    val docsDirValue =
        System.getenv(docsDirEnvVar)
            ?: throw TestAbortedException("Skipping because $docsDirEnvVar is not set")
    val docsDir = Path(docsDirValue).toAbsolutePath()

    assertTrue(docsDir.exists(), "$docsDir doesn't exist")

    val slicesList = System.getenv(slicesEnvVar)
    assumeTrue(
        slicesList == null || slice.subdirectory in slicesList.split(','),
        "Skipping slice $slice because it is not in $slicesEnvVar")

    // SchemaSpy matches table names against a regex. Construct one that has all the tables in the
    // current slice. Table names should never include regex special characters.
    val tableRegex =
        tableSlices
            .flatMap { (_, tables) ->
              tables.filterValues { slice in it }.map { (tableName, _) -> tableName }
            }
            .joinToString("|")

    val sliceDir = docsDir.resolve(slice.subdirectory)

    // The SchemaSpy Docker container runs as a different user than the CI build, so we need to
    // allow it to create files in the slice directories. Set the permissions explicitly rather than
    // as part of directory creation so they aren't altered by the current umask.
    Files.createDirectories(sliceDir)
    Files.setPosixFilePermissions(sliceDir, PosixFilePermissions.fromString("rwxrwxrwx"))

    try {
      log.info("Writing output to $sliceDir")

      val container =
          GenericContainer(schemaSpyDockerImage)
              .withCommand(
                  // Use the driver for Postgres 11 and up
                  "-t",
                  "pgsql11",
                  "-host",
                  postgresContainer.networkAliases.first(),
                  "-port",
                  PostgreSQLContainer.POSTGRESQL_PORT.toString(),
                  "-u",
                  postgresContainer.username,
                  "-p",
                  postgresContainer.password,
                  "-db",
                  postgresContainer.databaseName,
                  // Output all diagrams as SVG (default is PNG which generates huge image files)
                  "-imageformat",
                  "svg",
                  // Disable a useless feature that warns when tables have similar column names
                  "-noimplied",
                  // Restrict the doc to the tables that should be shown in this slice
                  "-i",
                  tableRegex,
                  "-schemas",
                  tableSlices.keys.joinToString(","),
              )
              .withFileSystemBind(sliceDir.toString(), "/output", BindMode.READ_WRITE)
              // Log stdout as INFO and stderr as ERROR
              .withLogConsumer(Slf4jLogConsumer(log).withSeparateOutputStreams())
              .withNetwork(postgresContainer.network)
              // Wait for the container to exit with exit code 0 when it's started
              .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(timeout))

      // This will wait for the command to finish, thanks to OneShotStartupCheckStrategy, and will
      // throw an exception if the command exits with nonzero status.
      container.start()
    } finally {
      // Don't leave a world-writable directory sitting around.
      Files.setPosixFilePermissions(sliceDir, PosixFilePermissions.fromString("rwxr-xr-x"))
    }
  }

  @Test
  fun `all tables should be included in tableSlices configuration`() {
    tableSlices.forEach { (schemaName, tables) ->
      val tablesFromDb = mutableSetOf<String>()

      forEachTable(schemaName) { metadata ->
        val tableName = metadata.getString(TABLE_NAME)
        tablesFromDb.add(tableName)
      }

      // Diff the configuration and the list of tables from the DB in two assertions so the failure
      // message is easier to read; assertEquals() would require hunting through a big list of
      // tables
      // to spot the differences.

      assertEquals(
          emptySet<String>(),
          tablesFromDb - tables.keys,
          "Tables not listed in $schemaName schema doc configuration")
      assertEquals(
          emptySet<String>(),
          tables.keys - tablesFromDb,
          "Nonexistent tables listed in $schemaName schema doc configuration")
    }
  }

  @Test
  fun `tables that are included in docs should have comments`() {
    val tablesWithoutComments = mutableListOf<String>()

    tableSlices.forEach { (schemaName, tables) ->
      forEachTable(schemaName) { metadata ->
        val tableName = metadata.getString(TABLE_NAME)
        if (!tables[tableName].isNullOrEmpty() && metadata.getString(TABLE_COMMENT) == null) {
          tablesWithoutComments.add("$schemaName.$tableName")
        }
      }
    }

    assertEquals(
        emptyList<String>(),
        tablesWithoutComments.sorted(),
        "Tables without comments that are included in docs; please add them to R__Comments.sql")
  }

  /**
   * Queries the list of tables in the "public" schema from the database and passes the metadata for
   * each one to a callback function. The metadata is in the form of a JDBC [ResultSet]; see
   * [DatabaseMetaData.getTables] for a list of the available columns.
   */
  private fun forEachTable(schemaName: String, func: (resultSet: ResultSet) -> Unit) {
    dslContext.connection { conn ->
      conn.metaData.getTables(null, schemaName, null, arrayOf("TABLE")).use { metadata ->
        while (metadata.next()) {
          func(metadata)
        }
      }
    }
  }

  companion object {
    /** Name of JDBC table metadata column that holds the table name. */
    private const val TABLE_NAME = "TABLE_NAME"

    /** Name of JDBC table metadata column that holds the table comment. */
    private const val TABLE_COMMENT = "REMARKS"
  }
}
