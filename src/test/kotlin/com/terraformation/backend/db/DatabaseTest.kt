package com.terraformation.backend.db

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.api.ArbitraryJsonObject
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DealStage
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.accelerator.ScoreCategory
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.tables.daos.CohortModulesDao
import com.terraformation.backend.db.accelerator.tables.daos.CohortsDao
import com.terraformation.backend.db.accelerator.tables.daos.DefaultVotersDao
import com.terraformation.backend.db.accelerator.tables.daos.DeliverableDocumentsDao
import com.terraformation.backend.db.accelerator.tables.daos.DeliverablesDao
import com.terraformation.backend.db.accelerator.tables.daos.ModulesDao
import com.terraformation.backend.db.accelerator.tables.daos.ParticipantsDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectAcceleratorDetailsDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectScoresDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectVoteDecisionsDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectVotesDao
import com.terraformation.backend.db.accelerator.tables.daos.SubmissionDocumentsDao
import com.terraformation.backend.db.accelerator.tables.daos.SubmissionsDao
import com.terraformation.backend.db.accelerator.tables.pojos.CohortModulesRow
import com.terraformation.backend.db.accelerator.tables.pojos.CohortsRow
import com.terraformation.backend.db.accelerator.tables.pojos.DefaultVotersRow
import com.terraformation.backend.db.accelerator.tables.pojos.DeliverableDocumentsRow
import com.terraformation.backend.db.accelerator.tables.pojos.DeliverablesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ModulesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectAcceleratorDetailsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectScoresRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectVoteDecisionsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectVotesRow
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionDocumentsRow
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionsRow
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.EcosystemType
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.ReportStatus
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.daos.AutomationsDao
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.db.default_schema.tables.daos.CountrySubdivisionsDao
import com.terraformation.backend.db.default_schema.tables.daos.DeviceManagersDao
import com.terraformation.backend.db.default_schema.tables.daos.DeviceTemplatesDao
import com.terraformation.backend.db.default_schema.tables.daos.DevicesDao
import com.terraformation.backend.db.default_schema.tables.daos.FacilitiesDao
import com.terraformation.backend.db.default_schema.tables.daos.FilesDao
import com.terraformation.backend.db.default_schema.tables.daos.InternalTagsDao
import com.terraformation.backend.db.default_schema.tables.daos.NotificationsDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationInternalTagsDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationManagedLocationTypesDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationReportSettingsDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationUsersDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.db.default_schema.tables.daos.ProjectLandUseModelTypesDao
import com.terraformation.backend.db.default_schema.tables.daos.ProjectReportSettingsDao
import com.terraformation.backend.db.default_schema.tables.daos.ProjectsDao
import com.terraformation.backend.db.default_schema.tables.daos.ReportFilesDao
import com.terraformation.backend.db.default_schema.tables.daos.ReportPhotosDao
import com.terraformation.backend.db.default_schema.tables.daos.ReportsDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesEcosystemTypesDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesProblemsDao
import com.terraformation.backend.db.default_schema.tables.daos.SubLocationsDao
import com.terraformation.backend.db.default_schema.tables.daos.ThumbnailsDao
import com.terraformation.backend.db.default_schema.tables.daos.TimeZonesDao
import com.terraformation.backend.db.default_schema.tables.daos.TimeseriesDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadProblemsDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadsDao
import com.terraformation.backend.db.default_schema.tables.daos.UserGlobalRolesDao
import com.terraformation.backend.db.default_schema.tables.daos.UsersDao
import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationInternalTagsRow
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationReportSettingsRow
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectLandUseModelTypesRow
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectReportSettingsRow
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectsRow
import com.terraformation.backend.db.default_schema.tables.pojos.ReportsRow
import com.terraformation.backend.db.default_schema.tables.pojos.TimeZonesRow
import com.terraformation.backend.db.default_schema.tables.pojos.UserGlobalRolesRow
import com.terraformation.backend.db.default_schema.tables.references.AUTOMATIONS
import com.terraformation.backend.db.default_schema.tables.references.DEVICES
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.NOTIFICATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_ECOSYSTEM_TYPES
import com.terraformation.backend.db.default_schema.tables.references.SUB_LOCATIONS
import com.terraformation.backend.db.default_schema.tables.references.UPLOADS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.daos.BatchDetailsHistoryDao
import com.terraformation.backend.db.nursery.tables.daos.BatchDetailsHistorySubLocationsDao
import com.terraformation.backend.db.nursery.tables.daos.BatchPhotosDao
import com.terraformation.backend.db.nursery.tables.daos.BatchQuantityHistoryDao
import com.terraformation.backend.db.nursery.tables.daos.BatchSubLocationsDao
import com.terraformation.backend.db.nursery.tables.daos.BatchWithdrawalsDao
import com.terraformation.backend.db.nursery.tables.daos.BatchesDao
import com.terraformation.backend.db.nursery.tables.daos.WithdrawalPhotosDao
import com.terraformation.backend.db.nursery.tables.pojos.BatchSubLocationsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchWithdrawalsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalsRow
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.tables.daos.AccessionCollectorsDao
import com.terraformation.backend.db.seedbank.tables.daos.AccessionPhotosDao
import com.terraformation.backend.db.seedbank.tables.daos.AccessionQuantityHistoryDao
import com.terraformation.backend.db.seedbank.tables.daos.AccessionsDao
import com.terraformation.backend.db.seedbank.tables.daos.BagsDao
import com.terraformation.backend.db.seedbank.tables.daos.GeolocationsDao
import com.terraformation.backend.db.seedbank.tables.daos.ViabilityTestResultsDao
import com.terraformation.backend.db.seedbank.tables.daos.ViabilityTestsDao
import com.terraformation.backend.db.seedbank.tables.daos.WithdrawalsDao
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservedPlotCoordinatesId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSiteNotificationId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.daos.DeliveriesDao
import com.terraformation.backend.db.tracking.tables.daos.DraftPlantingSitesDao
import com.terraformation.backend.db.tracking.tables.daos.MonitoringPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPhotosDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotConditionsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservedPlotCoordinatesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSeasonsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSiteNotificationsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitePopulationsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSubzonePopulationsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSubzonesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingZonePopulationsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingZonesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingsDao
import com.terraformation.backend.db.tracking.tables.daos.RecordedPlantsDao
import com.terraformation.backend.db.tracking.tables.pojos.DeliveriesRow
import com.terraformation.backend.db.tracking.tables.pojos.DraftPlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPhotosRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotCoordinatesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSeasonsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSiteNotificationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzonePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzonesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_POPULATIONS
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.polygon
import com.terraformation.backend.toBigDecimal
import com.terraformation.backend.tracking.db.PlantingSiteImporter
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import com.terraformation.backend.util.Turtle
import com.terraformation.backend.util.toInstant
import jakarta.ws.rs.NotFoundException
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSupertypeOf
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DAOImpl
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.BeforeEach
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jooq.JooqTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.support.TestPropertySourceUtils
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * Base class for database-backed tests. Subclass this to get a fully-configured database with a
 * [DSLContext] and a set of jOOQ DAO objects ready to use. The database is run in a Docker
 * container which is torn down after all tests have finished. This cuts down on the chance that
 * tests will behave differently from one development environment to the next.
 *
 * In general, you should only use this for testing database-centric code! Do not put SQL queries in
 * the middle of business logic. If you want to test code that _uses_ data from the database, pull
 * the queries out into data-access classes (the code base uses the suffix "Store" for these
 * classes) and stub out the stores. Then test the store classes separately. Database-backed tests
 * are slower and are usually not as easy to read or maintain.
 *
 * Some things to be aware of:
 * - Each test method is run in a transaction which is rolled back afterwards, so no need to worry
 *   about test methods polluting the database for each other if they're writing values.
 * - But that means test methods can't use data written by previous methods. If your test method
 *   needs sample data, either put it in a migration (migrations are run before any tests) or in a
 *   helper method.
 * - Sequences, including the ones used to generate auto-increment primary keys, are normally not
 *   reset when transactions are rolled back. But it is useful to have a predictable set of IDs to
 *   compare against. So subclasses can override [tablesToResetSequences] with a list of tables
 *   whose primary key sequences should be reset before each test method.
 */
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(initializers = [DatabaseTest.DockerPostgresDataSourceInitializer::class])
@EnableConfigurationProperties(TerrawareServerConfig::class)
@JooqTest
@Testcontainers
@ComponentScan(basePackageClasses = [UsersDao::class])
abstract class DatabaseTest {
  @Autowired lateinit var dslContext: DSLContext

  /**
   * List of tables from which sequences are to be reset before each test method. Sequences used
   * here belong to the primary key in the table.
   */
  protected val tablesToResetSequences: List<Table<out Record>>
    get() = emptyList()

  // ID values inserted by insertSiteData(). These are used in most database-backed tests. They are
  // marked as final so they can be referenced in constructor-initialized properties in subclasses.
  protected final val organizationId: OrganizationId = OrganizationId(1)
  protected final val facilityId: FacilityId = FacilityId(100)

  /** IDs of entities that have been inserted using the `insert` helper methods during this test. */
  protected val inserted = Inserted()

  @BeforeEach
  fun resetSequencesForTables() {
    tablesToResetSequences
        .flatMap { table -> getSerialSequenceNames(table) }
        .toSet()
        .forEach { sequenceName ->
          dslContext.alterSequence(DSL.unquotedName(sequenceName)).restart().execute()
        }
  }

  private fun getSerialSequenceNames(table: Table<out Record>): List<String> =
      table.primaryKey!!.fields.map { field ->
        val schemaName = table.schema?.name
        val qualifiedName =
            if (schemaName.isNullOrEmpty()) table.name else "$schemaName.${table.name}"
        dslContext
            .select(
                DSL.function(
                    PG_GET_SERIAL_SEQUENCE,
                    SQLDataType.VARCHAR,
                    DSL.value(qualifiedName),
                    DSL.value(field.name)))
            .fetchOne()!!
            .value1()
      }

  /**
   * Turns a value into a type-safe ID wrapper.
   *
   * This allows us to call `insertOrganization(1)` or `insertOrganization(OrganizationId(1))` when
   * setting up test data.
   *
   * @receiver A value to convert to a wrapped ID. Can be a number in either raw or string form (in
   *   which case it is turned into a Long and passed to [wrapperConstructor] or an ID of the
   *   desired type (in which case it is returned to the caller).
   */
  protected final inline fun <R : Any, reified T : Any> R.toIdWrapper(
      wrapperConstructor: (Long) -> T
  ): T {
    return when (this) {
      is T -> this
      is Number -> wrapperConstructor(toLong())
      is String -> wrapperConstructor(toLong())
      else -> throw IllegalArgumentException("Unsupported ID type ${javaClass.name}")
    }
  }

  /**
   * Creates a lazily-instantiated jOOQ DAO object. In most cases, type inference will figure out
   * which DAO class to instantiate.
   */
  private final inline fun <reified T : DAOImpl<*, *, *>> lazyDao(): Lazy<T> {
    return lazy {
      val singleArgConstructor =
          T::class.constructors.first {
            it.parameters.size == 1 &&
                it.parameters[0].type.isSupertypeOf(Configuration::class.createType())
          }

      singleArgConstructor.call(dslContext.configuration())
    }
  }

  protected val accessionCollectorsDao: AccessionCollectorsDao by lazyDao()
  protected val accessionPhotosDao: AccessionPhotosDao by lazyDao()
  protected val accessionQuantityHistoryDao: AccessionQuantityHistoryDao by lazyDao()
  protected val accessionsDao: AccessionsDao by lazyDao()
  protected val automationsDao: AutomationsDao by lazyDao()
  protected val bagsDao: BagsDao by lazyDao()
  protected val batchDetailsHistoryDao: BatchDetailsHistoryDao by lazyDao()
  protected val batchDetailsHistorySubLocationsDao: BatchDetailsHistorySubLocationsDao by lazyDao()
  protected val batchesDao: BatchesDao by lazyDao()
  protected val batchPhotosDao: BatchPhotosDao by lazyDao()
  protected val batchQuantityHistoryDao: BatchQuantityHistoryDao by lazyDao()
  protected val batchSubLocationsDao: BatchSubLocationsDao by lazyDao()
  protected val batchWithdrawalsDao: BatchWithdrawalsDao by lazyDao()
  protected val cohortModulesDao: CohortModulesDao by lazyDao()
  protected val cohortsDao: CohortsDao by lazyDao()
  protected val countriesDao: CountriesDao by lazyDao()
  protected val countrySubdivisionsDao: CountrySubdivisionsDao by lazyDao()
  protected val defaultVotersDao: DefaultVotersDao by lazyDao()
  protected val deliverableDocumentsDao: DeliverableDocumentsDao by lazyDao()
  protected val deliverablesDao: DeliverablesDao by lazyDao()
  protected val deliveriesDao: DeliveriesDao by lazyDao()
  protected val deviceManagersDao: DeviceManagersDao by lazyDao()
  protected val devicesDao: DevicesDao by lazyDao()
  protected val deviceTemplatesDao: DeviceTemplatesDao by lazyDao()
  protected val draftPlantingSitesDao: DraftPlantingSitesDao by lazyDao()
  protected val facilitiesDao: FacilitiesDao by lazyDao()
  protected val filesDao: FilesDao by lazyDao()
  protected val geolocationsDao: GeolocationsDao by lazyDao()
  protected val internalTagsDao: InternalTagsDao by lazyDao()
  protected val modulesDao: ModulesDao by lazyDao()
  protected val monitoringPlotsDao: MonitoringPlotsDao by lazyDao()
  protected val notificationsDao: NotificationsDao by lazyDao()
  protected val nurseryWithdrawalsDao:
      com.terraformation.backend.db.nursery.tables.daos.WithdrawalsDao by
      lazyDao()
  protected val observationPhotosDao: ObservationPhotosDao by lazyDao()
  protected val observationPlotConditionsDao: ObservationPlotConditionsDao by lazyDao()
  protected val observationPlotsDao: ObservationPlotsDao by lazyDao()
  protected val observationsDao: ObservationsDao by lazyDao()
  protected val observedPlotCoordinatesDao: ObservedPlotCoordinatesDao by lazyDao()
  protected val organizationInternalTagsDao: OrganizationInternalTagsDao by lazyDao()
  protected val organizationManagedLocationTypesDao: OrganizationManagedLocationTypesDao by
      lazyDao()
  protected val organizationReportSettingsDao: OrganizationReportSettingsDao by lazyDao()
  protected val organizationsDao: OrganizationsDao by lazyDao()
  protected val organizationUsersDao: OrganizationUsersDao by lazyDao()
  protected val participantsDao: ParticipantsDao by lazyDao()
  protected val plantingsDao: PlantingsDao by lazyDao()
  protected val plantingSeasonsDao: PlantingSeasonsDao by lazyDao()
  protected val plantingSiteNotificationsDao: PlantingSiteNotificationsDao by lazyDao()
  protected val plantingSitePopulationsDao: PlantingSitePopulationsDao by lazyDao()
  protected val plantingSitesDao: PlantingSitesDao by lazyDao()
  protected val plantingSubzonePopulationsDao: PlantingSubzonePopulationsDao by lazyDao()
  protected val plantingSubzonesDao: PlantingSubzonesDao by lazyDao()
  protected val plantingZonePopulationsDao: PlantingZonePopulationsDao by lazyDao()
  protected val plantingZonesDao: PlantingZonesDao by lazyDao()
  protected val projectAcceleratorDetailsDao: ProjectAcceleratorDetailsDao by lazyDao()
  protected val projectLandUseModelTypesDao: ProjectLandUseModelTypesDao by lazyDao()
  protected val projectReportSettingsDao: ProjectReportSettingsDao by lazyDao()
  protected val projectScoresDao: ProjectScoresDao by lazyDao()
  protected val projectsDao: ProjectsDao by lazyDao()
  protected val projectVoteDecisionDao: ProjectVoteDecisionsDao by lazyDao()
  protected val projectVotesDao: ProjectVotesDao by lazyDao()
  protected val recordedPlantsDao: RecordedPlantsDao by lazyDao()
  protected val reportFilesDao: ReportFilesDao by lazyDao()
  protected val reportPhotosDao: ReportPhotosDao by lazyDao()
  protected val reportsDao: ReportsDao by lazyDao()
  protected val speciesDao: SpeciesDao by lazyDao()
  protected val speciesEcosystemTypesDao: SpeciesEcosystemTypesDao by lazyDao()
  protected val speciesProblemsDao: SpeciesProblemsDao by lazyDao()
  protected val subLocationsDao: SubLocationsDao by lazyDao()
  protected val submissionsDao: SubmissionsDao by lazyDao()
  protected val submissionDocumentsDao: SubmissionDocumentsDao by lazyDao()
  protected val thumbnailsDao: ThumbnailsDao by lazyDao()
  protected val timeseriesDao: TimeseriesDao by lazyDao()
  protected val timeZonesDao: TimeZonesDao by lazyDao()
  protected val uploadProblemsDao: UploadProblemsDao by lazyDao()
  protected val uploadsDao: UploadsDao by lazyDao()
  protected val userGlobalRolesDao: UserGlobalRolesDao by lazyDao()
  protected val usersDao: UsersDao by lazyDao()
  protected val viabilityTestResultsDao: ViabilityTestResultsDao by lazyDao()
  protected val viabilityTestsDao: ViabilityTestsDao by lazyDao()
  protected val withdrawalPhotosDao: WithdrawalPhotosDao by lazyDao()
  protected val withdrawalsDao: WithdrawalsDao by lazyDao()

  /**
   * Creates a user, organization, project, site, and facility that can be referenced by various
   * tests.
   */
  fun insertSiteData() {
    insertUser()
    insertOrganization()
    insertFacility()
  }

  protected fun insertOrganization(
      id: Any? = this.organizationId,
      name: String = "Organization $id",
      countryCode: String? = null,
      countrySubdivisionCode: String? = null,
      createdBy: UserId = currentUser().userId,
      timeZone: ZoneId? = null,
  ): OrganizationId {
    return with(ORGANIZATIONS) {
      dslContext
          .insertInto(ORGANIZATIONS)
          .set(COUNTRY_CODE, countryCode)
          .set(COUNTRY_SUBDIVISION_CODE, countrySubdivisionCode)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, Instant.EPOCH)
          .apply { if (id != null) set(ID, id.toIdWrapper { OrganizationId(it) }) }
          .set(NAME, name)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(TIME_ZONE, timeZone)
          .returning(ID)
          .fetchOne(ID)!!
          .also { inserted.organizationIds.add(it) }
    }
  }

  private var nextFacilityNumber = 1

  protected fun insertFacility(
      id: Any = this.facilityId,
      organizationId: Any = this.organizationId,
      name: String = "Facility $id",
      description: String? = "Description $id",
      createdBy: UserId = currentUser().userId,
      type: FacilityType = FacilityType.SeedBank,
      maxIdleMinutes: Int = 30,
      lastTimeseriesTime: Instant? = null,
      idleAfterTime: Instant? = null,
      idleSinceTime: Instant? = null,
      lastNotificationDate: LocalDate? = null,
      nextNotificationTime: Instant = Instant.EPOCH,
      timeZone: ZoneId? = null,
      buildStartedDate: LocalDate? = null,
      buildCompletedDate: LocalDate? = null,
      operationStartedDate: LocalDate? = null,
      capacity: Int? = null,
      facilityNumber: Int = nextFacilityNumber++,
  ): FacilityId {
    return with(FACILITIES) {
      val insertedId = id.toIdWrapper { FacilityId(it) }

      dslContext
          .insertInto(FACILITIES)
          .set(BUILD_COMPLETED_DATE, buildCompletedDate)
          .set(BUILD_STARTED_DATE, buildStartedDate)
          .set(CAPACITY, capacity)
          .set(CONNECTION_STATE_ID, FacilityConnectionState.NotConnected)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(DESCRIPTION, description)
          .set(FACILITY_NUMBER, facilityNumber)
          .set(ID, insertedId)
          .set(IDLE_AFTER_TIME, idleAfterTime)
          .set(IDLE_SINCE_TIME, idleSinceTime)
          .set(LAST_NOTIFICATION_DATE, lastNotificationDate)
          .set(LAST_TIMESERIES_TIME, lastTimeseriesTime)
          .set(MAX_IDLE_MINUTES, maxIdleMinutes)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(NAME, name)
          .set(NEXT_NOTIFICATION_TIME, nextNotificationTime)
          .set(OPERATION_STARTED_DATE, operationStartedDate)
          .set(ORGANIZATION_ID, organizationId.toIdWrapper { OrganizationId(it) })
          .set(TIME_ZONE, timeZone)
          .set(TYPE_ID, type)
          .returning(ID)
          .fetchOne(ID)!!
          .also { inserted.facilityIds.add(it) }
    }
  }

  protected fun getFacilityById(facilityId: FacilityId): FacilitiesRow {
    return facilitiesDao.fetchOneById(facilityId) ?: throw NotFoundException()
  }

  private var nextProjectNumber = 1

  protected fun insertProject(
      id: Any? = null,
      organizationId: Any = this.organizationId,
      name: String = if (id != null) "Project $id" else "Project ${nextProjectNumber++}",
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      description: String? = null,
      participantId: Any? = null,
      countryCode: String? = null,
  ): ProjectId {
    val row =
        ProjectsRow(
            countryCode = countryCode,
            createdBy = createdBy,
            createdTime = createdTime,
            description = description,
            id = id?.toIdWrapper { ProjectId(it) },
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            name = name,
            organizationId = organizationId.toIdWrapper { OrganizationId(it) },
            participantId = participantId?.toIdWrapper { ParticipantId(it) },
        )

    projectsDao.insert(row)

    return row.id!!.also { inserted.projectIds.add(it) }
  }

  protected fun insertProjectAcceleratorDetails(
      row: ProjectAcceleratorDetailsRow = ProjectAcceleratorDetailsRow(),
      applicationReforestableLand: Number? = row.applicationReforestableLand,
      confirmedReforestableLand: Number? = row.confirmedReforestableLand,
      dealDescription: String? = row.dealDescription,
      dealStage: DealStage? = row.dealStageId,
      dropboxFolderPath: String? = row.dropboxFolderPath,
      failureRisk: String? = row.failureRisk,
      fileNaming: String? = row.fileNaming,
      googleFolderUrl: Any? = row.googleFolderUrl,
      investmentThesis: String? = row.investmentThesis,
      maxCarbonAccumulation: Number? = row.maxCarbonAccumulation,
      minCarbonAccumulation: Number? = row.minCarbonAccumulation,
      numCommunities: Int? = row.numCommunities,
      numNativeSpecies: Int? = row.numNativeSpecies,
      perHectareBudget: Number? = row.perHectareBudget,
      pipeline: Pipeline? = row.pipelineId,
      projectId: Any = row.projectId ?: inserted.projectId,
      projectLead: String? = row.projectLead,
      totalExpansionPotential: Number? = row.totalExpansionPotential,
      whatNeedsToBeTrue: String? = row.whatNeedsToBeTrue,
  ): ProjectAcceleratorDetailsRow {
    val rowWithDefaults =
        ProjectAcceleratorDetailsRow(
            applicationReforestableLand = applicationReforestableLand?.toBigDecimal(),
            confirmedReforestableLand = confirmedReforestableLand?.toBigDecimal(),
            dealDescription = dealDescription,
            dealStageId = dealStage,
            dropboxFolderPath = dropboxFolderPath,
            failureRisk = failureRisk,
            fileNaming = fileNaming,
            googleFolderUrl = googleFolderUrl?.let { URI("$it") },
            investmentThesis = investmentThesis,
            maxCarbonAccumulation = maxCarbonAccumulation?.toBigDecimal(),
            minCarbonAccumulation = minCarbonAccumulation?.toBigDecimal(),
            numCommunities = numCommunities,
            numNativeSpecies = numNativeSpecies,
            perHectareBudget = perHectareBudget?.toBigDecimal(),
            pipelineId = pipeline,
            projectId = projectId.toIdWrapper { ProjectId(it) },
            projectLead = projectLead,
            totalExpansionPotential = totalExpansionPotential?.toBigDecimal(),
            whatNeedsToBeTrue = whatNeedsToBeTrue,
        )

    projectAcceleratorDetailsDao.insert(rowWithDefaults)

    return rowWithDefaults
  }

  protected fun insertProjectLandUseModelType(
      projectId: Any = inserted.projectId,
      landUseModelType: LandUseModelType = LandUseModelType.OtherLandUseModel,
  ) {
    projectLandUseModelTypesDao.insert(
        ProjectLandUseModelTypesRow(
            landUseModelTypeId = landUseModelType,
            projectId = projectId.toIdWrapper { ProjectId(it) }))
  }

  protected fun insertProjectScore(
      projectId: Any = inserted.projectId,
      phase: CohortPhase = CohortPhase.Phase0DueDiligence,
      category: ScoreCategory = ScoreCategory.Legal,
      score: Int? = null,
      qualitative: String? = null,
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
  ) {
    val row =
        ProjectScoresRow(
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            phaseId = phase,
            projectId = projectId.toIdWrapper { ProjectId(it) },
            qualitative = qualitative,
            score = score,
            scoreCategoryId = category,
        )

    projectScoresDao.insert(row)
  }

  protected fun insertDefaultVoter(userId: UserId) {
    val row = DefaultVotersRow(userId)
    defaultVotersDao.insert(row)
  }

  private var nextDeliverableNumber: Int = 1

  protected fun insertDeliverable(
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      deliverableCategoryId: DeliverableCategory = DeliverableCategory.FinancialViability,
      deliverableTypeId: DeliverableType = DeliverableType.Document,
      descriptionHtml: String? = "Description $nextDeliverableNumber",
      id: Any? = null,
      isSensitive: Boolean = false,
      isRequired: Boolean = false,
      moduleId: Any? = inserted.moduleId,
      name: String = "Deliverable $nextDeliverableNumber",
      position: Int = nextDeliverableNumber,
      subtitle: String? = null,
  ): DeliverableId {
    nextDeliverableNumber++

    val row =
        DeliverablesRow(
            createdBy = createdBy,
            createdTime = createdTime,
            deliverableCategoryId = deliverableCategoryId,
            deliverableTypeId = deliverableTypeId,
            descriptionHtml = descriptionHtml,
            id = id?.toIdWrapper { DeliverableId(it) },
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            moduleId = moduleId?.toIdWrapper { ModuleId(it) },
            name = name,
            position = position,
            isSensitive = isSensitive,
            isRequired = isRequired,
        )

    deliverablesDao.insert(row)

    return row.id!!.also { inserted.deliverableIds.add(it) }
  }

  protected fun insertDeliverableDocument(
      deliverableId: Any = inserted.deliverableId,
      templateUrl: Any? = null,
  ) {
    val row =
        DeliverableDocumentsRow(
            deliverableId = deliverableId.toIdWrapper { DeliverableId(it) },
            deliverableTypeId = DeliverableType.Document,
            templateUrl = templateUrl?.let { URI.create("$it") },
        )

    deliverableDocumentsDao.insert(row)
  }

  protected fun insertDevice(
      id: Any,
      facilityId: Any = this.facilityId,
      name: String = "device $id",
      createdBy: UserId = currentUser().userId,
      type: String = "type"
  ) {
    with(DEVICES) {
      val insertedId = id.toIdWrapper { DeviceId(it) }

      dslContext
          .insertInto(DEVICES)
          .set(ADDRESS, "address")
          .set(CREATED_BY, createdBy)
          .set(DEVICE_TYPE, type)
          .set(FACILITY_ID, facilityId.toIdWrapper { FacilityId(it) })
          .set(ID, insertedId)
          .set(MAKE, "make")
          .set(MODEL, "model")
          .set(MODIFIED_BY, createdBy)
          .set(NAME, name)
          .set(PROTOCOL, "protocol")
          .execute()

      inserted.deviceIds.add(insertedId)
    }
  }

  protected fun insertAutomation(
      id: Any,
      facilityId: Any = this.facilityId,
      name: String = "automation $id",
      type: String = AutomationModel.SENSOR_BOUNDS_TYPE,
      deviceId: Any? = "$id".toLong(),
      timeseriesName: String? = "timeseries",
      lowerThreshold: Double? = 10.0,
      upperThreshold: Double? = 20.0,
      createdBy: UserId = currentUser().userId,
      objectMapper: ObjectMapper = jacksonObjectMapper(),
  ) {
    with(AUTOMATIONS) {
      val insertedId = id.toIdWrapper { AutomationId(it) }

      dslContext
          .insertInto(AUTOMATIONS)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(DEVICE_ID, deviceId?.toIdWrapper { DeviceId(it) })
          .set(FACILITY_ID, facilityId.toIdWrapper { FacilityId(it) })
          .set(ID, insertedId)
          .set(LOWER_THRESHOLD, lowerThreshold)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(NAME, name)
          .set(TIMESERIES_NAME, timeseriesName)
          .set(TYPE, type)
          .set(UPPER_THRESHOLD, upperThreshold)
          .execute()

      inserted.automationIds.add(insertedId)
    }
  }

  private var nextSpeciesNumber = 1

  protected fun insertSpecies(
      speciesId: Any? = null,
      scientificName: String =
          if (speciesId != null) "Species $speciesId" else "Species ${nextSpeciesNumber++}",
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      modifiedTime: Instant = Instant.EPOCH,
      organizationId: Any = this.organizationId,
      deletedTime: Instant? = null,
      checkedTime: Instant? = null,
      initialScientificName: String = scientificName,
      commonName: String? = null,
      ecosystemTypes: Set<EcosystemType> = emptySet(),
      growthForm: GrowthForm? = null,
  ): SpeciesId {
    val speciesIdWrapper = speciesId?.toIdWrapper { SpeciesId(it) }
    val organizationIdWrapper = organizationId.toIdWrapper { OrganizationId(it) }

    val actualSpeciesId =
        with(SPECIES) {
          dslContext
              .insertInto(SPECIES)
              .set(CHECKED_TIME, checkedTime)
              .set(COMMON_NAME, commonName)
              .set(CREATED_BY, createdBy)
              .set(CREATED_TIME, createdTime)
              .set(DELETED_BY, if (deletedTime != null) createdBy else null)
              .set(DELETED_TIME, deletedTime)
              .set(GROWTH_FORM_ID, growthForm)
              .apply { speciesIdWrapper?.let { set(ID, it) } }
              .set(INITIAL_SCIENTIFIC_NAME, initialScientificName)
              .set(MODIFIED_BY, createdBy)
              .set(MODIFIED_TIME, modifiedTime)
              .set(ORGANIZATION_ID, organizationIdWrapper)
              .set(SCIENTIFIC_NAME, scientificName)
              .returning(ID)
              .fetchOne(ID)!!
        }

    ecosystemTypes.forEach { ecosystemType ->
      dslContext
          .insertInto(SPECIES_ECOSYSTEM_TYPES)
          .set(SPECIES_ECOSYSTEM_TYPES.SPECIES_ID, actualSpeciesId)
          .set(SPECIES_ECOSYSTEM_TYPES.ECOSYSTEM_TYPE_ID, ecosystemType)
          .execute()
    }

    return actualSpeciesId.also { inserted.speciesIds.add(it) }
  }

  fun insertSubmission(
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      deliverableId: Any? = inserted.deliverableId,
      feedback: String? = null,
      id: Any? = null,
      internalComment: String? = null,
      projectId: Any? = inserted.projectId,
      submissionStatus: SubmissionStatus = SubmissionStatus.NotSubmitted,
  ): SubmissionId {
    val row =
        SubmissionsRow(
            createdBy = createdBy,
            createdTime = createdTime,
            deliverableId = deliverableId?.toIdWrapper { DeliverableId(it) },
            feedback = feedback,
            id = id?.toIdWrapper { SubmissionId(it) },
            internalComment = internalComment,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            projectId = projectId?.toIdWrapper { ProjectId(it) },
            submissionStatusId = submissionStatus,
        )

    submissionsDao.insert(row)

    return row.id!!.also { inserted.submissionIds.add(it) }
  }

  private var nextSubmissionNumber = 1

  fun insertSubmissionDocument(
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      description: String? = null,
      documentStore: DocumentStore = DocumentStore.Google,
      id: Any? = null,
      location: String = "Location $nextSubmissionNumber",
      name: String = "Submission Document $nextSubmissionNumber",
      originalName: String? = "Original Name $nextSubmissionNumber",
      projectId: Any? = inserted.projectId,
      submissionId: Any? = inserted.submissionId,
  ): SubmissionDocumentId {
    nextSubmissionNumber++

    val row =
        SubmissionDocumentsRow(
            createdBy = createdBy,
            createdTime = createdTime,
            description = description,
            documentStoreId = documentStore,
            id = id?.toIdWrapper { SubmissionDocumentId(it) },
            location = location,
            name = name,
            originalName = originalName,
            projectId = projectId?.toIdWrapper { ProjectId(it) },
            submissionId = submissionId?.toIdWrapper { SubmissionId(it) },
        )

    submissionDocumentsDao.insert(row)

    return row.id!!.also { inserted.submissionDocumentIds.add(it) }
  }

  /** Creates a user that can be referenced by various tests. */
  fun insertUser(
      userId: Any? = currentUser().userId,
      authId: String? = "$userId",
      email: String = "$userId@terraformation.com",
      firstName: String? = "First",
      lastName: String? = "Last",
      type: UserType = UserType.Individual,
      emailNotificationsEnabled: Boolean = false,
      timeZone: ZoneId? = null,
      locale: Locale? = null,
  ): UserId {
    val userIdWrapper = userId?.toIdWrapper { UserId(it) }

    val insertedId =
        with(USERS) {
          dslContext
              .insertInto(USERS)
              .set(AUTH_ID, authId)
              .set(CREATED_TIME, Instant.EPOCH)
              .set(EMAIL, email)
              .set(EMAIL_NOTIFICATIONS_ENABLED, emailNotificationsEnabled)
              .apply { userIdWrapper?.let { set(ID, it) } }
              .set(FIRST_NAME, firstName)
              .set(LAST_NAME, lastName)
              .set(LOCALE, locale)
              .set(MODIFIED_TIME, Instant.EPOCH)
              .set(TIME_ZONE, timeZone)
              .set(USER_TYPE_ID, type)
              .returning(ID)
              .fetchOne(ID)!!
        }

    return insertedId.also { inserted.userIds.add(it) }
  }

  fun insertUserGlobalRole(
      userId: Any = currentUser().userId,
      role: GlobalRole,
  ) {
    userGlobalRolesDao.insert(
        UserGlobalRolesRow(globalRoleId = role, userId = userId.toIdWrapper { UserId(it) }))
  }

  /** Adds a user to an organization. */
  fun insertOrganizationUser(
      userId: Any = currentUser().userId,
      organizationId: Any = this.organizationId,
      role: Role = Role.Contributor,
      createdBy: UserId = currentUser().userId,
  ) {
    with(ORGANIZATION_USERS) {
      dslContext
          .insertInto(ORGANIZATION_USERS)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(ORGANIZATION_ID, organizationId.toIdWrapper { OrganizationId(it) })
          .set(ROLE_ID, role)
          .set(USER_ID, userId.toIdWrapper { UserId(it) })
          .execute()
    }
  }

  private var nextSubLocationNumber = 1

  /** Adds a sub-location to a facility. */
  fun insertSubLocation(
      id: Any? = null,
      facilityId: Any = this.facilityId,
      name: String = id?.let { "Location $it" } ?: "Location ${nextSubLocationNumber++}",
      createdBy: UserId = currentUser().userId,
  ): SubLocationId {
    val idWrapper = id?.toIdWrapper { SubLocationId(it) }

    val insertedId =
        with(SUB_LOCATIONS) {
          dslContext
              .insertInto(SUB_LOCATIONS)
              .set(CREATED_BY, createdBy)
              .set(CREATED_TIME, Instant.EPOCH)
              .set(FACILITY_ID, facilityId.toIdWrapper { FacilityId(it) })
              .apply { idWrapper?.let { set(ID, it) } }
              .set(MODIFIED_BY, createdBy)
              .set(MODIFIED_TIME, Instant.EPOCH)
              .set(NAME, name)
              .returning(ID)
              .fetchOne(ID)!!
        }

    return insertedId.also { inserted.subLocationIds.add(it) }
  }

  fun insertFile(
      row: FilesRow = FilesRow(),
      id: Any? = row.id,
      fileName: String = row.fileName ?: "fileName",
      contentType: String = row.contentType ?: "image/jpeg",
      size: Long = row.size ?: 1,
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      storageUrl: Any = row.storageUrl ?: "http://dummy",
  ): FileId {
    val rowWithDefaults =
        row.copy(
            contentType = contentType,
            createdBy = createdBy,
            createdTime = createdTime,
            fileName = fileName,
            id = id?.toIdWrapper { FileId(it) },
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            size = size,
            storageUrl = URI("$storageUrl"),
        )

    filesDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.fileIds.add(it) }
  }

  fun insertUpload(
      id: Any,
      type: UploadType = UploadType.SpeciesCSV,
      fileName: String = "$id.csv",
      storageUrl: URI = URI.create("file:///$id.csv"),
      contentType: String = "text/csv",
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      status: UploadStatus = UploadStatus.Receiving,
      organizationId: OrganizationId? = null,
      facilityId: FacilityId? = null,
      locale: Locale = Locale.ENGLISH,
  ) {
    with(UPLOADS) {
      val insertedId = id.toIdWrapper { UploadId(it) }

      dslContext
          .insertInto(UPLOADS)
          .set(CONTENT_TYPE, contentType)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, createdTime)
          .set(FACILITY_ID, facilityId)
          .set(FILENAME, fileName)
          .set(ID, insertedId)
          .set(LOCALE, locale)
          .set(ORGANIZATION_ID, organizationId)
          .set(STATUS_ID, status)
          .set(STORAGE_URL, storageUrl)
          .set(TYPE_ID, type)
          .execute()

      inserted.uploadIds.add(insertedId)
    }
  }

  fun insertNotification(
      id: NotificationId,
      userId: UserId = currentUser().userId,
      type: NotificationType = NotificationType.FacilityIdle,
      organizationId: OrganizationId? = null,
      title: String = "",
      body: String = "",
      localUrl: URI = URI.create(""),
      createdTime: Instant = Instant.EPOCH,
      isRead: Boolean = false,
  ) {
    with(NOTIFICATIONS) {
      dslContext
          .insertInto(NOTIFICATIONS)
          .set(ID, id)
          .set(USER_ID, userId)
          .set(NOTIFICATION_TYPE_ID, type)
          .set(ORGANIZATION_ID, organizationId)
          .set(TITLE, title)
          .set(BODY, body)
          .set(LOCAL_URL, localUrl)
          .set(CREATED_TIME, createdTime)
          .set(IS_READ, isRead)
          .execute()

      inserted.notificationIds.add(id)
    }
  }

  /**
   * Inserts a new accession with reasonable defaults for required fields.
   *
   * Since accessions have a ton of fields, this works a little differently than the other insert
   * helper methods in that it takes an optional [row] argument. Any fields in [row] that are not
   * overridden by other parameters to this function are retained as-is. This approach means we
   * don't have to have a parameter here for every single accession field, just the ones that are
   * used in more than one or two places in the test suite.
   */
  fun insertAccession(
      row: AccessionsRow = AccessionsRow(),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      dataSourceId: DataSource = row.dataSourceId ?: DataSource.Web,
      facilityId: Any = row.facilityId ?: this.facilityId,
      id: Any? = row.id,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      number: String? = row.number ?: id?.let { "$it" },
      receivedDate: LocalDate? = row.receivedDate,
      stateId: AccessionState = row.stateId ?: AccessionState.Processing,
      treesCollectedFrom: Int? = row.treesCollectedFrom,
  ): AccessionId {
    val rowWithDefaults =
        row.copy(
            createdBy = createdBy,
            createdTime = createdTime,
            dataSourceId = dataSourceId,
            facilityId = facilityId.toIdWrapper { FacilityId(it) },
            id = id?.toIdWrapper { AccessionId(it) },
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            number = number,
            receivedDate = receivedDate,
            stateId = stateId,
            treesCollectedFrom = treesCollectedFrom,
        )

    accessionsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.accessionIds.add(it) }
  }

  private var nextBatchNuber: Int = 1

  fun insertBatch(
      row: BatchesRow = BatchesRow(),
      addedDate: LocalDate = row.addedDate ?: LocalDate.EPOCH,
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      facilityId: Any = row.facilityId ?: this.facilityId,
      germinatingQuantity: Int = row.germinatingQuantity ?: 0,
      id: Any? = row.id,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      notReadyQuantity: Int = row.notReadyQuantity ?: 0,
      organizationId: Any = row.organizationId ?: this.organizationId,
      projectId: Any? = row.projectId,
      readyQuantity: Int = row.readyQuantity ?: 0,
      readyByDate: LocalDate? = row.readyByDate,
      speciesId: Any = row.speciesId ?: inserted.speciesId,
      version: Int = row.version ?: 1,
      batchNumber: String = row.batchNumber ?: id?.toString() ?: "${nextBatchNuber++}",
      germinationRate: Int? = row.germinationRate,
      totalGerminated: Int? = row.totalGerminated,
      totalGerminationCandidates: Int? = row.totalGerminationCandidates,
      lossRate: Int? = row.lossRate ?: if (notReadyQuantity > 0 || readyQuantity > 0) 0 else null,
      totalLost: Int? = row.totalLost ?: if (notReadyQuantity > 0 || readyQuantity > 0) 0 else null,
      totalLossCandidates: Int? =
          row.totalLossCandidates
              ?: if (notReadyQuantity > 0 || readyQuantity > 0) notReadyQuantity + readyQuantity
              else null,
  ): BatchId {
    val effectiveGerminationRate =
        germinationRate
            ?: if (totalGerminated != null && totalGerminationCandidates != null) {
              ((100.0 * totalGerminated) / totalGerminationCandidates).roundToInt()
            } else {
              null
            }
    val effectiveLossRate =
        lossRate
            ?: if (totalLost != null && totalLossCandidates != null) {
              ((100.0 * totalLost) / totalLossCandidates).roundToInt()
            } else {
              null
            }

    val rowWithDefaults =
        row.copy(
            addedDate = addedDate,
            batchNumber = batchNumber,
            createdBy = createdBy,
            createdTime = createdTime,
            facilityId = facilityId.toIdWrapper { FacilityId(it) },
            germinatingQuantity = germinatingQuantity,
            germinationRate = effectiveGerminationRate,
            totalGerminated = totalGerminated,
            totalGerminationCandidates = totalGerminationCandidates,
            id = id?.toIdWrapper { BatchId(it) },
            latestObservedGerminatingQuantity = germinatingQuantity,
            latestObservedNotReadyQuantity = notReadyQuantity,
            latestObservedReadyQuantity = readyQuantity,
            latestObservedTime = createdTime,
            lossRate = effectiveLossRate,
            totalLost = totalLost,
            totalLossCandidates = totalLossCandidates,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            notReadyQuantity = notReadyQuantity,
            organizationId = organizationId.toIdWrapper { OrganizationId(it) },
            projectId = projectId?.toIdWrapper { ProjectId(it) },
            readyQuantity = readyQuantity,
            readyByDate = readyByDate,
            speciesId = speciesId.toIdWrapper { SpeciesId(it) },
            version = version,
        )

    batchesDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.batchIds.add(it) }
  }

  fun insertBatchSubLocation(
      batchId: Any = inserted.batchId,
      subLocationId: Any = inserted.subLocationId,
      facilityId: Any? = null,
  ) {
    val subLocationIdWrapper = subLocationId.toIdWrapper { SubLocationId(it) }
    val effectiveFacilityId =
        facilityId?.toIdWrapper { FacilityId(it) }
            ?: subLocationsDao.fetchOneById(subLocationIdWrapper)!!.facilityId!!

    val row =
        BatchSubLocationsRow(
            batchId = batchId.toIdWrapper { BatchId(it) },
            facilityId = effectiveFacilityId,
            subLocationId = subLocationIdWrapper,
        )

    batchSubLocationsDao.insert(row)
  }

  fun insertWithdrawal(
      row: WithdrawalsRow = WithdrawalsRow(),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      destinationFacilityId: Any? = row.destinationFacilityId,
      facilityId: Any = row.facilityId ?: this.facilityId,
      id: Any? = row.id,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      purpose: WithdrawalPurpose = WithdrawalPurpose.Other,
      withdrawnDate: LocalDate = row.withdrawnDate ?: LocalDate.EPOCH,
  ): WithdrawalId {
    val rowWithDefaults =
        row.copy(
            createdBy = createdBy,
            createdTime = createdTime,
            destinationFacilityId = destinationFacilityId?.toIdWrapper { FacilityId(it) },
            facilityId = facilityId.toIdWrapper { FacilityId(it) },
            id = id?.toIdWrapper { WithdrawalId(it) },
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            purposeId = purpose,
            withdrawnDate = withdrawnDate,
        )

    nurseryWithdrawalsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.withdrawalIds.add(it) }
  }

  fun insertBatchWithdrawal(
      row: BatchWithdrawalsRow = BatchWithdrawalsRow(),
      batchId: Any = row.batchId ?: inserted.batchId,
      destinationBatchId: Any? = row.destinationBatchId,
      germinatingQuantityWithdrawn: Int = row.germinatingQuantityWithdrawn ?: 0,
      notReadyQuantityWithdrawn: Int = row.notReadyQuantityWithdrawn ?: 0,
      readyQuantityWithdrawn: Int = row.readyQuantityWithdrawn ?: 0,
      withdrawalId: Any = row.withdrawalId ?: inserted.withdrawalId
  ) {
    val rowWithDefaults =
        row.copy(
            batchId = batchId.toIdWrapper { BatchId(it) },
            destinationBatchId = destinationBatchId?.toIdWrapper { BatchId(it) },
            germinatingQuantityWithdrawn = germinatingQuantityWithdrawn,
            notReadyQuantityWithdrawn = notReadyQuantityWithdrawn,
            readyQuantityWithdrawn = readyQuantityWithdrawn,
            withdrawalId = withdrawalId.toIdWrapper { WithdrawalId(it) },
        )

    batchWithdrawalsDao.insert(rowWithDefaults)
  }

  var nextPlantingSiteNumber: Int = 1

  fun insertPlantingSite(
      row: PlantingSitesRow = PlantingSitesRow(),
      areaHa: BigDecimal? = row.areaHa,
      x: Number? = null,
      y: Number? = null,
      width: Number = 3,
      height: Number = 2,
      boundary: Geometry? = row.boundary,
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      exclusion: Geometry? = row.exclusion,
      gridOrigin: Geometry? = row.gridOrigin,
      id: Any? = row.id,
      organizationId: Any = row.organizationId ?: this.organizationId,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      name: String = row.name ?: id?.let { "Site $id" } ?: "Site ${nextPlantingSiteNumber++}",
      timeZone: ZoneId? = row.timeZone,
      projectId: Any? = row.projectId,
  ): PlantingSiteId {
    val effectiveBoundary =
        when {
          boundary != null -> boundary
          x != null && y != null ->
              multiPolygon(
                  polygon(x, y, x.toDouble() + width.toDouble(), y.toDouble() + height.toDouble()))
          else -> null
        }

    val rowWithDefaults =
        row.copy(
            areaHa = areaHa,
            boundary = effectiveBoundary,
            createdBy = createdBy,
            createdTime = createdTime,
            exclusion = exclusion,
            gridOrigin = gridOrigin,
            id = id?.toIdWrapper { PlantingSiteId(it) },
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            organizationId = organizationId.toIdWrapper { OrganizationId(it) },
            projectId = projectId?.toIdWrapper { ProjectId(it) },
            timeZone = timeZone,
        )

    plantingSitesDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.plantingSiteIds.add(it) }
  }

  fun insertPlantingSeason(
      timeZone: ZoneId = ZoneOffset.UTC,
      endDate: LocalDate = LocalDate.EPOCH.plusDays(1),
      endTime: Instant = endDate.plusDays(1).toInstant(timeZone),
      id: Any? = null,
      isActive: Boolean = false,
      plantingSiteId: Any = inserted.plantingSiteId,
      startDate: LocalDate = LocalDate.EPOCH,
      startTime: Instant = startDate.toInstant(timeZone),
  ): PlantingSeasonId {
    val row =
        PlantingSeasonsRow(
            endDate = endDate,
            endTime = endTime,
            id = id?.toIdWrapper { PlantingSeasonId(it) },
            isActive = isActive,
            plantingSiteId = plantingSiteId.toIdWrapper { PlantingSiteId(it) },
            startDate = startDate,
            startTime = startTime,
        )

    plantingSeasonsDao.insert(row)

    return row.id!!.also { inserted.plantingSeasonIds.add(it) }
  }

  fun insertPlantingSiteNotification(
      row: PlantingSiteNotificationsRow = PlantingSiteNotificationsRow(),
      id: Any? = row.id,
      number: Int = row.notificationNumber ?: 1,
      plantingSiteId: Any = row.plantingSiteId ?: inserted.plantingSiteId,
      sentTime: Instant = row.sentTime ?: Instant.EPOCH,
      type: NotificationType,
  ): PlantingSiteNotificationId {
    val rowWithDefaults =
        row.copy(
            id = id?.toIdWrapper { PlantingSiteNotificationId(it) },
            notificationNumber = number,
            notificationTypeId = type,
            plantingSiteId = plantingSiteId.toIdWrapper { PlantingSiteId(it) },
            sentTime = sentTime,
        )

    plantingSiteNotificationsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.plantingSiteNotificationIds.add(it) }
  }

  private var nextPlantingZoneNumber: Int = 1

  fun insertPlantingZone(
      row: PlantingZonesRow = PlantingZonesRow(),
      areaHa: BigDecimal = row.areaHa ?: BigDecimal.TEN,
      x: Number = 0,
      y: Number = 0,
      width: Number = 3,
      height: Number = 2,
      boundary: Geometry =
          row.boundary
              ?: Turtle(point(0)).makeMultiPolygon {
                north(y.toDouble() * MONITORING_PLOT_SIZE)
                east(x.toDouble() * MONITORING_PLOT_SIZE)
                rectangle(
                    width.toDouble() * MONITORING_PLOT_SIZE,
                    height.toDouble() * MONITORING_PLOT_SIZE)
              },
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      errorMargin: BigDecimal = row.errorMargin ?: PlantingSiteImporter.DEFAULT_ERROR_MARGIN,
      extraPermanentClusters: Int = row.extraPermanentClusters ?: 0,
      id: Any? = row.id,
      plantingSiteId: Any = row.plantingSiteId ?: inserted.plantingSiteId,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      name: String = row.name ?: id?.let { "Z$id" } ?: "Z${nextPlantingZoneNumber++}",
      numPermanentClusters: Int =
          row.numPermanentClusters ?: PlantingSiteImporter.DEFAULT_NUM_PERMANENT_CLUSTERS,
      numTemporaryPlots: Int =
          row.numTemporaryPlots ?: PlantingSiteImporter.DEFAULT_NUM_TEMPORARY_PLOTS,
      studentsT: BigDecimal = row.studentsT ?: PlantingSiteImporter.DEFAULT_STUDENTS_T,
      targetPlantingDensity: BigDecimal? = row.targetPlantingDensity,
      variance: BigDecimal = row.variance ?: PlantingSiteImporter.DEFAULT_VARIANCE,
  ): PlantingZoneId {
    val rowWithDefaults =
        row.copy(
            areaHa = areaHa,
            boundary = boundary,
            createdBy = createdBy,
            createdTime = createdTime,
            errorMargin = errorMargin,
            extraPermanentClusters = extraPermanentClusters,
            id = id?.toIdWrapper { PlantingZoneId(it) },
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            numPermanentClusters = numPermanentClusters,
            numTemporaryPlots = numTemporaryPlots,
            plantingSiteId = plantingSiteId.toIdWrapper { PlantingSiteId(it) },
            studentsT = studentsT,
            targetPlantingDensity = targetPlantingDensity,
            variance = variance,
        )

    plantingZonesDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.plantingZoneIds.add(it) }
  }

  private var nextPlantingSubzoneNumber: Int = 1
  private var nextMonitoringPlotNumber: Int = 1

  fun insertPlantingSubzone(
      row: PlantingSubzonesRow = PlantingSubzonesRow(),
      areaHa: BigDecimal = row.areaHa ?: BigDecimal.ONE,
      x: Number = 0,
      y: Number = 0,
      width: Number = 3,
      height: Number = 2,
      boundary: Geometry =
          row.boundary
              ?: Turtle(point(0)).makeMultiPolygon {
                north(y.toDouble() * MONITORING_PLOT_SIZE)
                east(x.toDouble() * MONITORING_PLOT_SIZE)
                rectangle(
                    width.toDouble() * MONITORING_PLOT_SIZE,
                    height.toDouble() * MONITORING_PLOT_SIZE)
              },
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      id: Any? = row.id,
      plantingCompletedTime: Instant? = row.plantingCompletedTime,
      plantingSiteId: Any = row.plantingSiteId ?: inserted.plantingSiteId,
      plantingZoneId: Any = row.plantingZoneId ?: inserted.plantingZoneId,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      name: String = row.name ?: id?.let { "$id" } ?: "${nextPlantingSubzoneNumber++}",
      fullName: String = "Z1-$name",
  ): PlantingSubzoneId {
    val plantingZoneIdWrapper = plantingZoneId.toIdWrapper { PlantingZoneId(it) }
    val plantingSiteIdWrapper = plantingSiteId.toIdWrapper { PlantingSiteId(it) }

    val rowWithDefaults =
        row.copy(
            areaHa = areaHa,
            boundary = boundary,
            createdBy = createdBy,
            createdTime = createdTime,
            fullName = fullName,
            id = id?.toIdWrapper { PlantingSubzoneId(it) },
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            plantingCompletedTime = plantingCompletedTime,
            plantingSiteId = plantingSiteIdWrapper,
            plantingZoneId = plantingZoneIdWrapper,
        )

    plantingSubzonesDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.plantingSubzoneIds.add(it) }
  }

  private var nextModuleNumber: Int = 1

  fun insertModule(
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      id: Any? = null,
      name: String = "Module $nextModuleNumber",
  ): ModuleId {
    nextModuleNumber++

    val row =
        ModulesRow(
            createdBy = createdBy,
            createdTime = createdTime,
            id = id?.toIdWrapper { ModuleId(it) },
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            name = name,
        )

    modulesDao.insert(row)

    return row.id!!.also { inserted.moduleIds.add(it) }
  }

  fun insertMonitoringPlot(
      row: MonitoringPlotsRow = MonitoringPlotsRow(),
      x: Number = 0,
      y: Number = 0,
      boundary: Polygon =
          (row.boundary as? Polygon)
              ?: Turtle(point(0)).makePolygon {
                north(y.toDouble() * MONITORING_PLOT_SIZE)
                east(x.toDouble() * MONITORING_PLOT_SIZE)
                square(MONITORING_PLOT_SIZE)
              },
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      id: Any? = row.id,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      name: String = row.name ?: id?.let { "$id" } ?: "${nextMonitoringPlotNumber++}",
      fullName: String = "Z1-1-$name",
      permanentCluster: Int? = row.permanentCluster,
      permanentClusterSubplot: Int? =
          row.permanentClusterSubplot ?: if (permanentCluster != null) 1 else null,
      plantingSubzoneId: Any = row.plantingSubzoneId ?: inserted.plantingSubzoneId,
  ): MonitoringPlotId {
    val plantingSubzoneIdWrapper = plantingSubzoneId.toIdWrapper { PlantingSubzoneId(it) }

    val rowWithDefaults =
        row.copy(
            boundary = boundary,
            createdBy = createdBy,
            createdTime = createdTime,
            fullName = fullName,
            id = id?.toIdWrapper { MonitoringPlotId(it) },
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            permanentCluster = permanentCluster,
            permanentClusterSubplot = permanentClusterSubplot,
            plantingSubzoneId = plantingSubzoneIdWrapper,
        )

    monitoringPlotsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.monitoringPlotIds.add(it) }
  }

  private var nextDraftPlantingSiteNumber = 1

  fun insertDraftPlantingSite(
      row: DraftPlantingSitesRow = DraftPlantingSitesRow(),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      data: ArbitraryJsonObject = row.data ?: JSONB.valueOf("{}"),
      description: String? = row.description,
      id: Any? = row.id,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      name: String = row.name ?: "Draft site ${nextDraftPlantingSiteNumber++}",
      numPlantingSubzones: Int? = row.numPlantingSubzones,
      numPlantingZones: Int? = row.numPlantingZones,
      organizationId: Any = row.organizationId ?: this.organizationId,
      projectId: Any? = row.projectId,
      timeZone: ZoneId? = row.timeZone,
  ): DraftPlantingSiteId {
    val rowWithDefaults =
        row.copy(
            createdBy = createdBy,
            createdTime = createdTime,
            data = data,
            description = description,
            id = id?.toIdWrapper { DraftPlantingSiteId(it) },
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            numPlantingSubzones = numPlantingSubzones,
            numPlantingZones = numPlantingZones,
            organizationId = organizationId.toIdWrapper { OrganizationId(it) },
            projectId = projectId?.toIdWrapper { ProjectId(it) },
            timeZone = timeZone,
        )

    draftPlantingSitesDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.draftPlantingSiteIds.add(it) }
  }

  fun insertDelivery(
      row: DeliveriesRow = DeliveriesRow(),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      id: Any? = row.id,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      plantingSiteId: Any = row.plantingSiteId ?: inserted.plantingSiteId,
      withdrawalId: Any = row.withdrawalId ?: inserted.withdrawalId,
  ): DeliveryId {
    val rowWithDetails =
        row.copy(
            createdBy = createdBy,
            createdTime = createdTime,
            id = id?.toIdWrapper { DeliveryId(it) },
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            plantingSiteId = plantingSiteId.toIdWrapper { PlantingSiteId(it) },
            withdrawalId = withdrawalId.toIdWrapper { WithdrawalId(it) },
        )

    deliveriesDao.insert(rowWithDetails)

    return rowWithDetails.id!!.also { inserted.deliveryIds.add(it) }
  }

  fun insertPlanting(
      row: PlantingsRow = PlantingsRow(),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      deliveryId: Any = row.deliveryId ?: inserted.deliveryId,
      id: Any? = row.id,
      numPlants: Int = row.numPlants ?: 1,
      plantingSiteId: Any = row.plantingSiteId ?: inserted.plantingSiteId,
      plantingTypeId: PlantingType = row.plantingTypeId ?: PlantingType.Delivery,
      plantingSubzoneId: Any? = row.plantingSubzoneId ?: inserted.plantingSubzoneIds.lastOrNull(),
      speciesId: Any = row.speciesId ?: inserted.speciesId
  ): PlantingId {
    val deliveryIdWrapper = deliveryId.toIdWrapper { DeliveryId(it) }
    val plantingSiteIdWrapper = plantingSiteId.toIdWrapper { PlantingSiteId(it) }
    val plantingSubzoneIdWrapper = plantingSubzoneId?.toIdWrapper { PlantingSubzoneId(it) }

    val rowWithDefaults =
        row.copy(
            createdBy = createdBy,
            createdTime = createdTime,
            deliveryId = deliveryIdWrapper,
            id = id?.toIdWrapper { PlantingId(it) },
            numPlants = numPlants,
            plantingSiteId = plantingSiteIdWrapper,
            plantingTypeId = plantingTypeId,
            plantingSubzoneId = plantingSubzoneIdWrapper,
            speciesId = speciesId.toIdWrapper { SpeciesId(it) },
        )

    plantingsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.plantingIds.add(it) }
  }

  fun insertPlantingSitePopulation(
      plantingSiteId: Any = inserted.plantingSiteId,
      speciesId: Any = inserted.speciesId,
      totalPlants: Int = 1,
      plantsSinceLastObservation: Int = totalPlants,
  ) {
    plantingSitePopulationsDao.insert(
        PlantingSitePopulationsRow(
            plantingSiteId = plantingSiteId.toIdWrapper<Any, PlantingSiteId> { PlantingSiteId(it) },
            speciesId = speciesId.toIdWrapper<Any, SpeciesId> { SpeciesId(it) },
            totalPlants = totalPlants,
            plantsSinceLastObservation = plantsSinceLastObservation,
        ))
  }

  fun insertPlantingSubzonePopulation(
      plantingSubzoneId: Any = inserted.plantingSubzoneId,
      speciesId: Any = inserted.speciesId,
      totalPlants: Int = 1,
      plantsSinceLastObservation: Int = totalPlants,
  ) {
    plantingSubzonePopulationsDao.insert(
        PlantingSubzonePopulationsRow(
            plantingSubzoneId = plantingSubzoneId.toIdWrapper { PlantingSubzoneId(it) },
            speciesId = speciesId.toIdWrapper { SpeciesId(it) },
            totalPlants = totalPlants,
            plantsSinceLastObservation = plantsSinceLastObservation,
        ))
  }

  fun insertPlantingZonePopulation(
      plantingZoneId: Any = inserted.plantingZoneId,
      speciesId: Any = inserted.speciesId,
      totalPlants: Int = 1,
      plantsSinceLastObservation: Int = totalPlants,
  ) {
    plantingZonePopulationsDao.insert(
        PlantingZonePopulationsRow(
            plantingZoneId = plantingZoneId.toIdWrapper { PlantingZoneId(it) },
            speciesId = speciesId.toIdWrapper { SpeciesId(it) },
            totalPlants = totalPlants,
            plantsSinceLastObservation = plantsSinceLastObservation,
        ))
  }

  fun addPlantingSubzonePopulation(
      plantingSubzoneId: Any = inserted.plantingSubzoneId,
      speciesId: Any = inserted.speciesId,
      totalPlants: Int = 1,
      plantsSinceLastObservation: Int = totalPlants,
  ) {
    val plantingSubzoneIdWrapper = plantingSubzoneId.toIdWrapper { PlantingSubzoneId(it) }
    val speciesIdWrapper = speciesId.toIdWrapper { SpeciesId(it) }

    with(PLANTING_SUBZONE_POPULATIONS) {
      dslContext
          .insertInto(PLANTING_SUBZONE_POPULATIONS)
          .set(PLANTING_SUBZONE_ID, plantingSubzoneIdWrapper)
          .set(SPECIES_ID, speciesIdWrapper)
          .set(TOTAL_PLANTS, totalPlants)
          .set(PLANTS_SINCE_LAST_OBSERVATION, plantsSinceLastObservation)
          .onDuplicateKeyUpdate()
          .set(TOTAL_PLANTS, TOTAL_PLANTS.plus(totalPlants))
          .set(
              PLANTS_SINCE_LAST_OBSERVATION,
              PLANTS_SINCE_LAST_OBSERVATION.plus(plantsSinceLastObservation))
          .execute()
    }
  }

  fun addPlantingZonePopulation(
      plantingZoneId: Any = inserted.plantingZoneId,
      speciesId: Any = inserted.speciesId,
      totalPlants: Int = 1,
      plantsSinceLastObservation: Int = totalPlants,
  ) {
    val plantingZoneIdWrapper = plantingZoneId.toIdWrapper { PlantingZoneId(it) }
    val speciesIdWrapper = speciesId.toIdWrapper { SpeciesId(it) }

    with(PLANTING_ZONE_POPULATIONS) {
      dslContext
          .insertInto(PLANTING_ZONE_POPULATIONS)
          .set(PLANTING_ZONE_ID, plantingZoneIdWrapper)
          .set(SPECIES_ID, speciesIdWrapper)
          .set(TOTAL_PLANTS, totalPlants)
          .set(PLANTS_SINCE_LAST_OBSERVATION, plantsSinceLastObservation)
          .onDuplicateKeyUpdate()
          .set(TOTAL_PLANTS, TOTAL_PLANTS.plus(totalPlants))
          .set(
              PLANTS_SINCE_LAST_OBSERVATION,
              PLANTS_SINCE_LAST_OBSERVATION.plus(plantsSinceLastObservation))
          .execute()
    }
  }

  fun insertReport(
      row: ReportsRow = ReportsRow(),
      body: String = row.body?.data() ?: """{"version":"1","organizationName":"org"}""",
      id: Any? = row.id,
      lockedBy: Any? = row.lockedBy,
      lockedTime: Instant? = row.lockedTime ?: lockedBy?.let { Instant.EPOCH },
      organizationId: Any = row.organizationId ?: this.organizationId,
      projectId: Any? = row.projectId,
      projectName: String? = row.projectName,
      quarter: Int = row.quarter ?: 1,
      submittedBy: Any? = row.submittedBy,
      submittedTime: Instant? = row.submittedTime ?: submittedBy?.let { Instant.EPOCH },
      status: ReportStatus =
          row.statusId
              ?: when {
                lockedBy != null -> ReportStatus.Locked
                submittedBy != null -> ReportStatus.Submitted
                else -> ReportStatus.New
              },
      year: Int = row.year ?: 1970,
  ): ReportId {
    val projectIdWrapper = projectId?.toIdWrapper { ProjectId(it) }
    val projectNameWithDefault =
        projectName ?: projectIdWrapper?.let { projectsDao.fetchOneById(it)?.name }

    val rowWithDefaults =
        row.copy(
            body = JSONB.jsonb(body),
            id = id?.toIdWrapper { ReportId(it) },
            lockedBy = lockedBy?.toIdWrapper { UserId(it) },
            lockedTime = lockedTime,
            organizationId = organizationId.toIdWrapper { OrganizationId(it) },
            projectId = projectIdWrapper,
            projectName = projectNameWithDefault,
            quarter = quarter,
            statusId = status,
            submittedBy = submittedBy?.toIdWrapper { UserId(it) },
            submittedTime = submittedTime,
            year = year,
        )

    reportsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.reportIds.add(it) }
  }

  fun insertOrganizationReportSettings(
      organizationId: Any = this.organizationId,
      isEnabled: Boolean = true,
  ) {
    val row =
        OrganizationReportSettingsRow(
            isEnabled = isEnabled,
            organizationId = organizationId.toIdWrapper { OrganizationId(it) },
        )

    organizationReportSettingsDao.insert(row)
  }

  fun insertProjectReportSettings(
      projectId: Any = inserted.projectId,
      isEnabled: Boolean = true,
  ) {
    val row =
        ProjectReportSettingsRow(
            isEnabled = isEnabled,
            projectId = projectId.toIdWrapper { ProjectId(it) },
        )

    projectReportSettingsDao.insert(row)
  }

  fun insertObservation(
      row: ObservationsRow = ObservationsRow(),
      createdTime: Instant = Instant.EPOCH,
      endDate: LocalDate = row.endDate ?: LocalDate.of(2023, 1, 31),
      id: Any? = row.id,
      plantingSiteId: Any = row.plantingSiteId ?: inserted.plantingSiteId,
      startDate: LocalDate = row.startDate ?: LocalDate.of(2023, 1, 1),
      completedTime: Instant? = row.completedTime,
      state: ObservationState =
          row.stateId
              ?: if (completedTime != null) {
                ObservationState.Completed
              } else {
                ObservationState.InProgress
              },
      upcomingNotificationSentTime: Instant? = row.upcomingNotificationSentTime,
  ): ObservationId {
    val rowWithDefaults =
        row.copy(
            completedTime = completedTime,
            createdTime = createdTime,
            endDate = endDate,
            id = id?.toIdWrapper { ObservationId(it) },
            plantingSiteId = plantingSiteId.toIdWrapper { PlantingSiteId(it) },
            startDate = startDate,
            stateId = state,
            upcomingNotificationSentTime = upcomingNotificationSentTime,
        )

    observationsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.observationIds.add(it) }
  }

  fun insertObservationPhoto(
      row: ObservationPhotosRow = ObservationPhotosRow(),
      fileId: Any = row.fileId ?: inserted.fileId,
      observationId: Any = row.observationId ?: inserted.observationId,
      monitoringPlotId: Any = row.monitoringPlotId ?: inserted.monitoringPlotId,
      gpsCoordinates: Point = row.gpsCoordinates?.centroid ?: point(1),
      position: ObservationPlotPosition = row.positionId ?: ObservationPlotPosition.SouthwestCorner,
  ) {
    val rowWithDefaults =
        row.copy(
            fileId = fileId.toIdWrapper { FileId(it) },
            gpsCoordinates = gpsCoordinates,
            monitoringPlotId = monitoringPlotId.toIdWrapper { MonitoringPlotId(it) },
            observationId = observationId.toIdWrapper { ObservationId(it) },
            positionId = position,
        )

    observationPhotosDao.insert(rowWithDefaults)
  }

  fun insertObservationPlot(
      row: ObservationPlotsRow = ObservationPlotsRow(),
      claimedBy: UserId? = row.claimedBy,
      claimedTime: Instant? = row.claimedTime ?: if (claimedBy != null) Instant.EPOCH else null,
      completedBy: UserId? = row.completedBy,
      completedTime: Instant? =
          row.completedTime ?: if (completedBy != null) Instant.EPOCH else null,
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      isPermanent: Boolean = row.isPermanent ?: false,
      monitoringPlotId: Any = row.monitoringPlotId ?: inserted.monitoringPlotId,
      observationId: Any = row.observationId ?: inserted.observationId,
  ) {
    val rowWithDefaults =
        row.copy(
            claimedBy = claimedBy,
            claimedTime = claimedTime,
            completedBy = completedBy,
            completedTime = completedTime,
            createdBy = createdBy,
            createdTime = createdTime,
            isPermanent = isPermanent,
            observationId = observationId.toIdWrapper { ObservationId(it) },
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            monitoringPlotId = monitoringPlotId.toIdWrapper { MonitoringPlotId(it) },
        )

    observationPlotsDao.insert(rowWithDefaults)
  }

  fun insertObservedCoordinates(
      row: ObservedPlotCoordinatesRow = ObservedPlotCoordinatesRow(),
      id: Any? = row.id,
      observationId: Any = row.observationId ?: inserted.observationId,
      monitoringPlotId: Any = row.monitoringPlotId ?: inserted.monitoringPlotId,
      gpsCoordinates: Point = row.gpsCoordinates?.centroid ?: point(1),
      position: ObservationPlotPosition = row.positionId ?: ObservationPlotPosition.NortheastCorner,
  ): ObservedPlotCoordinatesId {
    val rowWithDefaults =
        row.copy(
            gpsCoordinates = gpsCoordinates,
            id = id?.toIdWrapper { ObservedPlotCoordinatesId(it) },
            monitoringPlotId = monitoringPlotId.toIdWrapper { MonitoringPlotId(it) },
            observationId = observationId.toIdWrapper { ObservationId(it) },
            positionId = position,
        )

    observedPlotCoordinatesDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!
  }

  fun insertRecordedPlant(
      row: RecordedPlantsRow = RecordedPlantsRow(),
      gpsCoordinates: Point = row.gpsCoordinates?.centroid ?: point(1),
      id: Any? = row.id,
      monitoringPlotId: Any = row.monitoringPlotId ?: inserted.monitoringPlotId,
      observationId: Any = row.observationId ?: inserted.observationId,
      speciesId: Any? = row.speciesId,
      speciesName: String? = row.speciesName,
      certainty: RecordedSpeciesCertainty =
          row.certaintyId
              ?: when {
                speciesId != null -> RecordedSpeciesCertainty.Known
                speciesName != null -> RecordedSpeciesCertainty.Other
                else -> RecordedSpeciesCertainty.Unknown
              },
      status: RecordedPlantStatus = row.statusId ?: RecordedPlantStatus.Live,
  ): RecordedPlantId {
    val rowWithDefaults =
        row.copy(
            certaintyId = certainty,
            gpsCoordinates = gpsCoordinates,
            id = id?.toIdWrapper { RecordedPlantId(it) },
            monitoringPlotId = monitoringPlotId.toIdWrapper { MonitoringPlotId(it) },
            observationId = observationId.toIdWrapper { ObservationId(it) },
            speciesId = speciesId?.toIdWrapper { SpeciesId(it) },
            speciesName = speciesName,
            statusId = status,
        )

    recordedPlantsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!
  }

  fun insertTimeZone(timeZone: Any = ZoneId.of("Pacific/Honolulu")): ZoneId {
    val zoneId = if (timeZone is ZoneId) timeZone else ZoneId.of("$timeZone")
    timeZonesDao.insert(TimeZonesRow(zoneId))
    return zoneId
  }

  protected fun insertOrganizationInternalTag(
      organizationId: Any = this.organizationId,
      tagId: InternalTagId = InternalTagIds.Reporter,
      createdBy: Any = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
  ) {
    organizationInternalTagsDao.insert(
        OrganizationInternalTagsRow(
            internalTagId = tagId,
            organizationId = organizationId.toIdWrapper { OrganizationId(it) },
            createdBy = createdBy.toIdWrapper { UserId(it) },
            createdTime = createdTime))
  }

  private var nextParticipantNumber = 1

  fun insertParticipant(
      id: Any? = null,
      name: String = "Participant ${nextParticipantNumber++}",
      createdBy: Any = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      cohortId: Any? = null,
  ): ParticipantId {
    val row =
        ParticipantsRow(
            cohortId = cohortId?.toIdWrapper { CohortId(it) },
            createdBy = createdBy.toIdWrapper { UserId(it) },
            createdTime = createdTime,
            id = id?.toIdWrapper { ParticipantId(it) },
            modifiedBy = createdBy.toIdWrapper { UserId(it) },
            modifiedTime = createdTime,
            name = name,
        )

    participantsDao.insert(row)

    return row.id!!.also { inserted.participantIds.add(it) }
  }

  private var nextCohortNumber = 1

  fun insertCohort(
      id: Any? = null,
      name: String = "Cohort ${nextCohortNumber++}",
      phase: CohortPhase = CohortPhase.Phase0DueDiligence,
      createdBy: Any = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
  ): CohortId {
    val row =
        CohortsRow(
            createdBy = createdBy.toIdWrapper { UserId(it) },
            createdTime = createdTime,
            id = id?.toIdWrapper { CohortId(it) },
            modifiedBy = createdBy.toIdWrapper { UserId(it) },
            modifiedTime = createdTime,
            name = name,
            phaseId = phase,
        )

    cohortsDao.insert(row)

    return row.id!!.also { inserted.cohortIds.add(it) }
  }

  fun insertCohortModule(
      cohortId: Any = inserted.cohortId,
      moduleId: Any = inserted.moduleId,
      startDate: LocalDate = LocalDate.EPOCH,
      endDate: LocalDate = LocalDate.of(2257, 1, 1),
  ) {
    val row =
        CohortModulesRow(
            cohortId = cohortId.toIdWrapper { CohortId(it) },
            moduleId = moduleId.toIdWrapper { ModuleId(it) },
            startDate = startDate,
            endDate = endDate,
        )

    cohortModulesDao.insert(row)
  }

  fun insertVote(
      projectId: Any = inserted.projectId,
      phase: CohortPhase = CohortPhase.Phase0DueDiligence,
      user: UserId = currentUser().userId,
      voteOption: VoteOption? = null,
      conditionalInfo: String? = null,
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
  ): ProjectVotesRow {
    val row =
        ProjectVotesRow(
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            projectId = projectId.toIdWrapper { ProjectId(it) },
            phaseId = phase,
            userId = user,
            voteOptionId = voteOption,
            conditionalInfo = conditionalInfo,
        )

    projectVotesDao.insert(row)

    return row
  }

  fun insertVoteDecision(
      projectId: Any = inserted.projectId,
      phase: CohortPhase = CohortPhase.Phase0DueDiligence,
      voteOption: VoteOption? = null,
      modifiedTime: Instant = Instant.EPOCH,
  ): ProjectVoteDecisionsRow {
    val row =
        ProjectVoteDecisionsRow(
            projectId = projectId.toIdWrapper { ProjectId(it) },
            phaseId = phase,
            modifiedTime = modifiedTime,
            voteOptionId = voteOption,
        )

    projectVoteDecisionDao.insert(row)

    return row
  }

  class Inserted {
    val accessionIds = mutableListOf<AccessionId>()
    val automationIds = mutableListOf<AutomationId>()
    val batchIds = mutableListOf<BatchId>()
    val cohortIds = mutableListOf<CohortId>()
    val deliverableIds = mutableListOf<DeliverableId>()
    val deliveryIds = mutableListOf<DeliveryId>()
    val deviceIds = mutableListOf<DeviceId>()
    val draftPlantingSiteIds = mutableListOf<DraftPlantingSiteId>()
    val facilityIds = mutableListOf<FacilityId>()
    val fileIds = mutableListOf<FileId>()
    val moduleIds = mutableListOf<ModuleId>()
    val monitoringPlotIds = mutableListOf<MonitoringPlotId>()
    val notificationIds = mutableListOf<NotificationId>()
    val observationIds = mutableListOf<ObservationId>()
    val organizationIds = mutableListOf<OrganizationId>()
    val participantIds = mutableListOf<ParticipantId>()
    val plantingIds = mutableListOf<PlantingId>()
    val plantingSeasonIds = mutableListOf<PlantingSeasonId>()
    val plantingSiteIds = mutableListOf<PlantingSiteId>()
    val plantingSiteNotificationIds = mutableListOf<PlantingSiteNotificationId>()
    val plantingSubzoneIds = mutableListOf<PlantingSubzoneId>()
    val plantingZoneIds = mutableListOf<PlantingZoneId>()
    val projectIds = mutableListOf<ProjectId>()
    val reportIds = mutableListOf<ReportId>()
    val speciesIds = mutableListOf<SpeciesId>()
    val subLocationIds = mutableListOf<SubLocationId>()
    val submissionIds = mutableListOf<SubmissionId>()
    val submissionDocumentIds = mutableListOf<SubmissionDocumentId>()
    val uploadIds = mutableListOf<UploadId>()
    val userIds = mutableListOf<UserId>()
    val withdrawalIds = mutableListOf<WithdrawalId>()

    val accessionId
      get() = accessionIds.last()

    val automationId
      get() = automationIds.last()

    val batchId
      get() = batchIds.last()

    val cohortId
      get() = cohortIds.last()

    val deliverableId
      get() = deliverableIds.last()

    val deliveryId
      get() = deliveryIds.last()

    val deviceId
      get() = deviceIds.last()

    val draftPlantingSiteId
      get() = draftPlantingSiteIds.last()

    val facilityId
      get() = facilityIds.last()

    val fileId
      get() = fileIds.last()

    val moduleId
      get() = moduleIds.last()

    val monitoringPlotId
      get() = monitoringPlotIds.last()

    val notificationId
      get() = notificationIds.last()

    val observationId
      get() = observationIds.last()

    val organizationId
      get() = organizationIds.last()

    val participantId
      get() = participantIds.last()

    val plantingId
      get() = plantingIds.last()

    val plantingSeasonId
      get() = plantingSeasonIds.last()

    val plantingSiteId
      get() = plantingSiteIds.last()

    val plantingSiteNotificationId
      get() = plantingSiteNotificationIds.last()

    val plantingSubzoneId
      get() = plantingSubzoneIds.last()

    val plantingZoneId
      get() = plantingZoneIds.last()

    val projectId
      get() = projectIds.last()

    val reportId
      get() = reportIds.last()

    val speciesId
      get() = speciesIds.last()

    val subLocationId
      get() = subLocationIds.last()

    val submissionId
      get() = submissionIds.last()

    val uploadId
      get() = uploadIds.last()

    val userId
      get() = userIds.last()

    val withdrawalId
      get() = withdrawalIds.last()
  }

  class DockerPostgresDataSourceInitializer :
      ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(applicationContext: ConfigurableApplicationContext) {
      if (!started) {
        postgresContainer.start()
        started = true
      }

      TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
          applicationContext,
          "spring.datasource.url=${postgresContainer.jdbcUrl}",
          "spring.datasource.username=${postgresContainer.username}",
          "spring.datasource.password=${postgresContainer.password}",
      )
    }
  }

  companion object {
    /**
     * PostgreSQL function name that retrieves serial sequence name. This is also the column name
     * that holds the result in a successful response.
     *
     * [Docs here](https://www.postgresql.org/docs/13/functions-info.html).
     */
    const val PG_GET_SERIAL_SEQUENCE = "pg_get_serial_sequence"

    private val imageName: DockerImageName =
        DockerImageName.parse("$POSTGRES_DOCKER_REPOSITORY:$POSTGRES_DOCKER_TAG")
            .asCompatibleSubstituteFor("postgres")

    val postgresContainer: PostgreSQLContainer<*> =
        PostgreSQLContainer(imageName)
            .withDatabaseName("terraware")
            .withExposedPorts(PostgreSQLContainer.POSTGRESQL_PORT)
            .withNetwork(Network.newNetwork())
            .withNetworkAliases("postgres")
    var started: Boolean = false
  }
}
