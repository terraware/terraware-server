package com.terraformation.backend.db

import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.SchemaDocsGenerator.Slice.ACCELERATOR
import com.terraformation.backend.db.SchemaDocsGenerator.Slice.ALL
import com.terraformation.backend.db.SchemaDocsGenerator.Slice.CUSTOMER
import com.terraformation.backend.db.SchemaDocsGenerator.Slice.DEVICE
import com.terraformation.backend.db.SchemaDocsGenerator.Slice.DOCPROD
import com.terraformation.backend.db.SchemaDocsGenerator.Slice.FUNDER
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
  private val schemaSpyDockerImage = DockerImageName.parse("schemaspy/schemaspy:6.2.4")

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
    ACCELERATOR("accelerator"),
    /**
     * The full schema. Doesn't include tables that are needed by libraries but not really part of
     * the application's data model, e.g., the Flyway migration history table. But everything else
     * should be in this slice.
     */
    ALL("all"),
    CUSTOMER("customer"),
    DEVICE("device"),
    DOCPROD("docprod"),
    FUNDER("funder"),
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
          "accelerator" to
              mapOf(
                  "activities" to setOf(ALL, ACCELERATOR),
                  "activity_media_files" to setOf(ALL, ACCELERATOR),
                  "activity_media_types" to setOf(ALL, ACCELERATOR),
                  "activity_statuses" to setOf(ALL, ACCELERATOR),
                  "activity_types" to setOf(ALL, ACCELERATOR),
                  "application_histories" to setOf(ALL, ACCELERATOR),
                  "application_module_statuses" to setOf(ALL, ACCELERATOR),
                  "application_modules" to setOf(ALL, ACCELERATOR),
                  "application_statuses" to setOf(ALL, ACCELERATOR),
                  "applications" to setOf(ALL, ACCELERATOR),
                  "cohorts" to setOf(ALL, ACCELERATOR),
                  "cohort_modules" to setOf(ALL, ACCELERATOR),
                  "cohort_phases" to setOf(ALL, ACCELERATOR),
                  "deal_stages" to setOf(ALL, ACCELERATOR),
                  "default_voters" to setOf(ALL, ACCELERATOR),
                  "deliverable_categories" to setOf(ALL, ACCELERATOR),
                  "deliverable_cohort_due_dates" to setOf(ALL, ACCELERATOR),
                  "deliverable_documents" to setOf(ALL, ACCELERATOR),
                  "deliverable_project_due_dates" to setOf(ALL, ACCELERATOR),
                  "deliverable_types" to setOf(ALL, ACCELERATOR),
                  "deliverable_variables" to setOf(ALL, ACCELERATOR),
                  "deliverables" to setOf(ALL, ACCELERATOR),
                  "document_stores" to setOf(ALL, ACCELERATOR),
                  "events" to setOf(ALL, ACCELERATOR),
                  "event_projects" to setOf(ALL, ACCELERATOR),
                  "event_statuses" to setOf(ALL, ACCELERATOR),
                  "event_types" to setOf(ALL, ACCELERATOR),
                  "hubspot_token" to setOf(ALL, ACCELERATOR),
                  "internal_interests" to setOf(ALL, ACCELERATOR),
                  "metric_components" to setOf(ALL, ACCELERATOR),
                  "metric_types" to setOf(ALL, ACCELERATOR),
                  "modules" to setOf(ALL, ACCELERATOR),
                  "participants" to setOf(ALL, ACCELERATOR),
                  "pipelines" to setOf(ALL, ACCELERATOR),
                  "participant_project_species" to setOf(ALL, ACCELERATOR),
                  "project_accelerator_details" to setOf(ALL, ACCELERATOR),
                  "project_metrics" to setOf(ALL, ACCELERATOR),
                  "project_overall_scores" to setOf(ALL, ACCELERATOR),
                  "project_report_configs" to setOf(ALL, ACCELERATOR),
                  "project_scores" to setOf(ALL, ACCELERATOR),
                  "project_votes" to setOf(ALL, ACCELERATOR),
                  "project_vote_decisions" to setOf(ALL, ACCELERATOR),
                  "report_achievements" to setOf(ALL, ACCELERATOR),
                  "report_challenges" to setOf(ALL, ACCELERATOR),
                  "report_frequencies" to setOf(ALL, ACCELERATOR),
                  "report_metric_statuses" to setOf(ALL, ACCELERATOR),
                  "report_standard_metrics" to setOf(ALL, ACCELERATOR),
                  "report_system_metrics" to setOf(ALL, ACCELERATOR),
                  "report_photos" to setOf(ALL, ACCELERATOR),
                  "report_project_metrics" to setOf(ALL, ACCELERATOR),
                  "report_quarters" to setOf(ALL, ACCELERATOR),
                  "report_statuses" to setOf(ALL, ACCELERATOR),
                  "reports" to setOf(ALL, ACCELERATOR),
                  "score_categories" to setOf(ALL, ACCELERATOR),
                  "standard_metrics" to setOf(ALL, ACCELERATOR),
                  "submission_documents" to setOf(ALL, ACCELERATOR),
                  "submission_snapshots" to setOf(ALL, ACCELERATOR),
                  "submission_statuses" to setOf(ALL, ACCELERATOR),
                  "submissions" to setOf(ALL, ACCELERATOR),
                  "system_metrics" to setOf(ALL, ACCELERATOR),
                  "user_internal_interests" to setOf(ALL, ACCELERATOR),
                  "vote_options" to setOf(ALL, ACCELERATOR),
              ),
          "docprod" to
              mapOf(
                  "dependency_conditions" to setOf(ALL, DOCPROD),
                  "document_saved_versions" to setOf(ALL, DOCPROD),
                  "document_statuses" to setOf(ALL, DOCPROD),
                  "document_templates" to setOf(ALL, DOCPROD),
                  "documents" to setOf(ALL, DOCPROD),
                  "variable_image_values" to setOf(ALL, DOCPROD),
                  "variable_injection_display_styles" to setOf(ALL, DOCPROD),
                  "variable_link_values" to setOf(ALL, DOCPROD),
                  "variable_manifest_entries" to setOf(ALL, DOCPROD),
                  "variable_manifests" to setOf(ALL, DOCPROD),
                  "variable_numbers" to setOf(ALL, DOCPROD),
                  "variable_owners" to setOf(ALL, DOCPROD),
                  "variable_section_default_values" to setOf(ALL, DOCPROD),
                  "variable_section_recommendations" to setOf(ALL, DOCPROD),
                  "variable_section_values" to setOf(ALL, DOCPROD),
                  "variable_sections" to setOf(ALL, DOCPROD),
                  "variable_select_option_values" to setOf(ALL, DOCPROD),
                  "variable_select_options" to setOf(ALL, DOCPROD),
                  "variable_selects" to setOf(ALL, DOCPROD),
                  "variable_table_columns" to setOf(ALL, DOCPROD),
                  "variable_table_styles" to setOf(ALL, DOCPROD),
                  "variable_tables" to setOf(ALL, DOCPROD),
                  "variable_text_types" to setOf(ALL, DOCPROD),
                  "variable_texts" to setOf(ALL, DOCPROD),
                  "variable_types" to setOf(ALL, DOCPROD),
                  "variable_usage_types" to setOf(ALL, DOCPROD),
                  "variable_value_table_rows" to setOf(ALL, DOCPROD),
                  "variable_values" to setOf(ALL, DOCPROD),
                  "variable_workflow_history" to setOf(ALL, DOCPROD),
                  "variable_workflow_statuses" to setOf(ALL, DOCPROD),
                  "variables" to setOf(ALL, DOCPROD),
              ),
          "funder" to
              mapOf(
                  "funding_entities" to setOf(ALL, FUNDER),
                  "funding_entity_users" to setOf(ALL, FUNDER),
                  "funding_entity_projects" to setOf(ALL, FUNDER),
                  "published_activities" to setOf(ALL, FUNDER),
                  "published_activity_media_files" to setOf(ALL, FUNDER),
                  "published_project_carbon_certs" to setOf(ALL, FUNDER),
                  "published_project_land_use" to setOf(ALL, FUNDER),
                  "published_project_sdg" to setOf(ALL, FUNDER),
                  "published_project_details" to setOf(ALL, FUNDER),
                  "published_report_achievements" to setOf(ALL, FUNDER),
                  "published_report_challenges" to setOf(ALL, FUNDER),
                  "published_report_photos" to setOf(ALL, FUNDER),
                  "published_report_project_metrics" to setOf(ALL, FUNDER),
                  "published_report_standard_metrics" to setOf(ALL, FUNDER),
                  "published_report_system_metrics" to setOf(ALL, FUNDER),
                  "published_reports" to setOf(ALL, FUNDER),
              ),
          "nursery" to
              mapOf(
                  "batches" to setOf(ALL, NURSERY),
                  "batch_details_history" to setOf(ALL, NURSERY),
                  "batch_details_history_sub_locations" to setOf(ALL, NURSERY),
                  "batch_photos" to setOf(ALL, NURSERY),
                  "batch_quantity_history" to setOf(ALL, NURSERY),
                  "batch_quantity_history_types" to setOf(ALL, NURSERY),
                  "batch_sub_locations" to setOf(ALL, NURSERY),
                  "batch_substrates" to setOf(ALL, NURSERY),
                  "batch_withdrawals" to setOf(ALL, NURSERY),
                  "withdrawal_photos" to setOf(ALL, NURSERY),
                  "withdrawal_purposes" to setOf(ALL, NURSERY),
                  "withdrawals" to setOf(ALL, NURSERY),
              ),
          "public" to
              mapOf(
                  "app_versions" to setOf(ALL, CUSTOMER),
                  "automations" to setOf(ALL, DEVICE),
                  "chat_memory_conversations" to setOf(ALL, ACCELERATOR),
                  "chat_memory_message_types" to setOf(ALL, ACCELERATOR),
                  "chat_memory_messages" to setOf(ALL, ACCELERATOR),
                  "conservation_categories" to setOf(ALL, SPECIES),
                  "countries" to setOf(ALL, CUSTOMER),
                  "country_subdivisions" to setOf(ALL, CUSTOMER),
                  "devices" to setOf(ALL, DEVICE),
                  "device_managers" to setOf(ALL, CUSTOMER, DEVICE),
                  "device_template_categories" to setOf(ALL, DEVICE),
                  "device_templates" to setOf(ALL, DEVICE),
                  "disclaimers" to setOf(ALL, CUSTOMER),
                  "ecosystem_types" to setOf(ALL, SPECIES),
                  "event_log" to setOf(ALL),
                  "facilities" to setOf(ALL, CUSTOMER, DEVICE, SEEDBANK),
                  "facility_connection_states" to setOf(ALL, CUSTOMER, DEVICE),
                  "facility_types" to setOf(ALL, CUSTOMER),
                  "file_access_tokens" to setOf(ALL),
                  "files" to setOf(ALL, ACCELERATOR, CUSTOMER, NURSERY, SEEDBANK),
                  "gbif_distributions" to setOf(SPECIES),
                  "gbif_names" to setOf(SPECIES),
                  "gbif_name_words" to setOf(SPECIES),
                  "gbif_taxa" to setOf(SPECIES),
                  "gbif_vernacular_names" to setOf(SPECIES),
                  "global_roles" to setOf(ALL, CUSTOMER),
                  "growth_forms" to setOf(ALL, SEEDBANK),
                  "identifier_sequences" to setOf(ALL, SEEDBANK, NURSERY),
                  "internal_tags" to setOf(ALL, CUSTOMER),
                  "land_use_model_types" to setOf(ALL, ACCELERATOR, CUSTOMER),
                  "managed_location_types" to setOf(ALL, CUSTOMER),
                  "mux_asset_statuses" to setOf(ALL),
                  "mux_assets" to setOf(ALL),
                  "species_native_categories" to setOf(ALL, SPECIES),
                  "notification_criticalities" to setOf(ALL, CUSTOMER),
                  "notification_types" to setOf(ALL, CUSTOMER),
                  "notifications" to setOf(ALL, CUSTOMER),
                  "organization_internal_tags" to setOf(ALL, CUSTOMER),
                  "organization_managed_location_types" to setOf(ALL, CUSTOMER),
                  "organization_report_settings" to setOf(ALL, CUSTOMER),
                  "organization_types" to setOf(ALL, CUSTOMER),
                  "organization_users" to setOf(ALL, CUSTOMER),
                  "organizations" to setOf(ALL, CUSTOMER, DEVICE, SEEDBANK, SPECIES),
                  "plant_material_sourcing_methods" to setOf(ALL, SPECIES),
                  "project_internal_users" to setOf(ALL, CUSTOMER),
                  "project_land_use_model_types" to setOf(ALL, ACCELERATOR, CUSTOMER),
                  "project_report_settings" to setOf(ALL, CUSTOMER),
                  "project_internal_roles" to setOf(ALL, CUSTOMER),
                  "projects" to setOf(ALL, ACCELERATOR, CUSTOMER),
                  "rate_limited_events" to setOf(ALL),
                  "regions" to setOf(ALL, CUSTOMER),
                  "roles" to setOf(ALL, CUSTOMER),
                  "seed_fund_report_files" to setOf(ALL, CUSTOMER),
                  "seed_fund_report_photos" to setOf(ALL, CUSTOMER),
                  "seed_fund_report_statuses" to setOf(ALL, CUSTOMER),
                  "seed_fund_reports" to setOf(ALL, CUSTOMER),
                  "seed_storage_behaviors" to setOf(ALL, SEEDBANK),
                  "seed_treatments" to setOf(ALL, NURSERY, SEEDBANK),
                  "species" to setOf(ALL, SEEDBANK, SPECIES),
                  "species_ecosystem_types" to setOf(ALL, SPECIES),
                  "species_growth_forms" to setOf(ALL, SPECIES),
                  "species_plant_material_sourcing_methods" to setOf(ALL, SPECIES),
                  "species_problem_fields" to setOf(ALL, SPECIES),
                  "species_problem_types" to setOf(ALL, SPECIES),
                  "species_problems" to setOf(ALL, SPECIES),
                  "species_successional_groups" to setOf(ALL, SPECIES),
                  "sub_locations" to setOf(ALL, SEEDBANK),
                  "successional_groups" to setOf(ALL, SPECIES),
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
                  "user_global_roles" to setOf(ALL, CUSTOMER),
                  "user_preferences" to setOf(ALL, CUSTOMER),
                  "user_types" to setOf(ALL, CUSTOMER),
                  "users" to setOf(ALL, CUSTOMER),
                  "user_disclaimers" to setOf(ALL, CUSTOMER),
                  "vector_store" to setOf(ALL, ACCELERATOR),
                  "wood_density_levels" to setOf(ALL, SPECIES),
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
                  "viability_test_results" to setOf(ALL, SEEDBANK),
                  "viability_test_seed_types" to setOf(ALL, SEEDBANK),
                  "viability_test_substrates" to setOf(ALL, SEEDBANK),
                  "viability_test_types" to setOf(ALL, SEEDBANK),
                  "viability_tests" to setOf(ALL, SEEDBANK),
                  "withdrawal_purposes" to setOf(ALL, SEEDBANK),
                  "withdrawals" to setOf(ALL, SEEDBANK),
              ),
          "tracking" to
              mapOf(
                  "biomass_forest_types" to setOf(ALL, TRACKING),
                  "deliveries" to setOf(ALL, TRACKING),
                  "draft_planting_sites" to setOf(ALL, TRACKING),
                  "mangrove_tides" to setOf(ALL, TRACKING),
                  "monitoring_plot_histories" to setOf(ALL, TRACKING),
                  "monitoring_plot_overlaps" to setOf(ALL, TRACKING),
                  "monitoring_plots" to setOf(ALL, TRACKING),
                  "observation_biomass_details" to setOf(ALL, TRACKING),
                  "observation_biomass_quadrat_details" to setOf(ALL, TRACKING),
                  "observation_biomass_quadrat_species" to setOf(ALL, TRACKING),
                  "observation_biomass_species" to setOf(ALL, TRACKING),
                  "observable_conditions" to setOf(ALL, TRACKING),
                  "observation_photos" to setOf(ALL, TRACKING),
                  "observation_photo_types" to setOf(ALL, TRACKING),
                  "observation_plot_conditions" to setOf(ALL, TRACKING),
                  "observation_plot_positions" to setOf(ALL, TRACKING),
                  "observation_plot_statuses" to setOf(ALL, TRACKING),
                  "observation_plots" to setOf(ALL, TRACKING),
                  "observation_requested_subzones" to setOf(ALL, TRACKING),
                  "observation_states" to setOf(ALL, TRACKING),
                  "observation_types" to setOf(ALL, TRACKING),
                  "observations" to setOf(ALL, TRACKING),
                  "observed_plot_coordinates" to setOf(ALL, TRACKING),
                  "observed_plot_species_totals" to setOf(ALL, TRACKING),
                  "observed_site_species_totals" to setOf(ALL, TRACKING),
                  "observed_subzone_species_totals" to setOf(ALL, TRACKING),
                  "observed_zone_species_totals" to setOf(ALL, TRACKING),
                  "planting_types" to setOf(ALL, TRACKING),
                  "planting_seasons" to setOf(ALL, TRACKING),
                  "planting_site_histories" to setOf(ALL, TRACKING),
                  "planting_site_notifications" to setOf(ALL, TRACKING),
                  "planting_site_populations" to setOf(ALL, TRACKING),
                  "planting_sites" to setOf(ALL, TRACKING),
                  "planting_subzone_histories" to setOf(ALL, TRACKING),
                  "planting_subzone_populations" to setOf(ALL, TRACKING),
                  "planting_subzones" to setOf(ALL, TRACKING),
                  "planting_zone_histories" to setOf(ALL, TRACKING),
                  "planting_zone_populations" to setOf(ALL, TRACKING),
                  "planting_zone_t0_temp_densities" to setOf(ALL, TRACKING),
                  "planting_zones" to setOf(ALL, TRACKING),
                  "plantings" to setOf(ALL, TRACKING),
                  "plot_t0_densities" to setOf(ALL, TRACKING),
                  "plot_t0_observations" to setOf(ALL, TRACKING),
                  "recorded_plant_statuses" to setOf(ALL, TRACKING),
                  "recorded_plants" to setOf(ALL, TRACKING),
                  "recorded_species_certainties" to setOf(ALL, TRACKING),
                  "recorded_trees" to setOf(ALL, TRACKING),
                  "tree_growth_forms" to setOf(ALL, TRACKING),
              ),
      )

  /**
   * Regex patterns of table names to exclude. This is broken out into separate expressions rather
   * than a single combined expression for ease of maintenance.
   */
  private val excludeTables: List<Regex> =
      listOf(
          Regex("public\\.flyway_schema_history"),
          Regex("public\\.jobrunr_.*"),
          Regex("public\\.spatial_ref_sys"),
          Regex("public\\.spring_.*"),
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
        "Skipping slice $slice because it is not in $slicesEnvVar",
    )

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
      // tables to spot the differences.

      assertSetEquals(
          emptySet<String>(),
          tablesFromDb - tables.keys,
          "Tables not listed in $schemaName schema doc configuration",
      )
      assertSetEquals(
          emptySet<String>(),
          tables.keys - tablesFromDb,
          "Nonexistent tables listed in $schemaName schema doc configuration",
      )
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
        "Tables without comments that are included in docs; please add them to R__Comments.sql",
    )
  }

  /**
   * Queries the list of tables in a schema from the database and passes the metadata for each one
   * to a callback function, minus any tables whose names match an entry in [excludeTables]. The
   * metadata is in the form of a JDBC [ResultSet]; see [DatabaseMetaData.getTables] for a list of
   * the available columns.
   */
  private fun forEachTable(schemaName: String, func: (resultSet: ResultSet) -> Unit) {
    dslContext.connection { conn ->
      conn.metaData.getTables(null, schemaName, null, arrayOf("TABLE")).use { metadata ->
        while (metadata.next()) {
          val qualifiedTableName = "$schemaName.${metadata.getString(TABLE_NAME)}"
          if (excludeTables.none { it.matches(qualifiedTableName) }) {
            func(metadata)
          }
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
