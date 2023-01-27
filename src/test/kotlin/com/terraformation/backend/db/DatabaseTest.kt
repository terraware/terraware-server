package com.terraformation.backend.db

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.daos.AutomationsDao
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.db.default_schema.tables.daos.DeviceManagersDao
import com.terraformation.backend.db.default_schema.tables.daos.DeviceTemplatesDao
import com.terraformation.backend.db.default_schema.tables.daos.DevicesDao
import com.terraformation.backend.db.default_schema.tables.daos.FacilitiesDao
import com.terraformation.backend.db.default_schema.tables.daos.NotificationsDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationUsersDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.db.default_schema.tables.daos.PhotosDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesProblemsDao
import com.terraformation.backend.db.default_schema.tables.daos.ThumbnailsDao
import com.terraformation.backend.db.default_schema.tables.daos.TimeZonesDao
import com.terraformation.backend.db.default_schema.tables.daos.TimeseriesDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadProblemsDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadsDao
import com.terraformation.backend.db.default_schema.tables.daos.UsersDao
import com.terraformation.backend.db.default_schema.tables.pojos.TimeZonesRow
import com.terraformation.backend.db.default_schema.tables.references.AUTOMATIONS
import com.terraformation.backend.db.default_schema.tables.references.DEVICES
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.NOTIFICATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.UPLOADS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.daos.BatchQuantityHistoryDao
import com.terraformation.backend.db.nursery.tables.daos.BatchWithdrawalsDao
import com.terraformation.backend.db.nursery.tables.daos.BatchesDao
import com.terraformation.backend.db.nursery.tables.daos.WithdrawalPhotosDao
import com.terraformation.backend.db.nursery.tables.pojos.BatchWithdrawalsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalsRow
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.StorageCondition
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.tables.daos.AccessionCollectorsDao
import com.terraformation.backend.db.seedbank.tables.daos.AccessionPhotosDao
import com.terraformation.backend.db.seedbank.tables.daos.AccessionQuantityHistoryDao
import com.terraformation.backend.db.seedbank.tables.daos.AccessionsDao
import com.terraformation.backend.db.seedbank.tables.daos.BagsDao
import com.terraformation.backend.db.seedbank.tables.daos.GeolocationsDao
import com.terraformation.backend.db.seedbank.tables.daos.StorageLocationsDao
import com.terraformation.backend.db.seedbank.tables.daos.ViabilityTestResultsDao
import com.terraformation.backend.db.seedbank.tables.daos.ViabilityTestsDao
import com.terraformation.backend.db.seedbank.tables.daos.WithdrawalsDao
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.seedbank.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.PlotId
import com.terraformation.backend.db.tracking.tables.daos.DeliveriesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingZonesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingsDao
import com.terraformation.backend.db.tracking.tables.daos.PlotsDao
import com.terraformation.backend.db.tracking.tables.pojos.DeliveriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlotsRow
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.*
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSupertypeOf
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DAOImpl
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.BeforeEach
import org.locationtech.jts.geom.Geometry
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
  protected val batchesDao: BatchesDao by lazyDao()
  protected val batchQuantityHistoryDao: BatchQuantityHistoryDao by lazyDao()
  protected val batchWithdrawalsDao: BatchWithdrawalsDao by lazyDao()
  protected val countriesDao: CountriesDao by lazyDao()
  protected val deliveriesDao: DeliveriesDao by lazyDao()
  protected val deviceManagersDao: DeviceManagersDao by lazyDao()
  protected val devicesDao: DevicesDao by lazyDao()
  protected val deviceTemplatesDao: DeviceTemplatesDao by lazyDao()
  protected val facilitiesDao: FacilitiesDao by lazyDao()
  protected val geolocationsDao: GeolocationsDao by lazyDao()
  protected val notificationsDao: NotificationsDao by lazyDao()
  protected val nurseryWithdrawalsDao:
      com.terraformation.backend.db.nursery.tables.daos.WithdrawalsDao by
      lazyDao()
  protected val organizationsDao: OrganizationsDao by lazyDao()
  protected val organizationUsersDao: OrganizationUsersDao by lazyDao()
  protected val photosDao: PhotosDao by lazyDao()
  protected val plantingsDao: PlantingsDao by lazyDao()
  protected val plantingSitesDao: PlantingSitesDao by lazyDao()
  protected val plantingZonesDao: PlantingZonesDao by lazyDao()
  protected val plotsDao: PlotsDao by lazyDao()
  protected val speciesDao: SpeciesDao by lazyDao()
  protected val speciesProblemsDao: SpeciesProblemsDao by lazyDao()
  protected val storageLocationsDao: StorageLocationsDao by lazyDao()
  protected val thumbnailsDao: ThumbnailsDao by lazyDao()
  protected val timeseriesDao: TimeseriesDao by lazyDao()
  protected val timeZonesDao: TimeZonesDao by lazyDao()
  protected val uploadProblemsDao: UploadProblemsDao by lazyDao()
  protected val uploadsDao: UploadsDao by lazyDao()
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
          .returning(ID)
          .fetchOne(ID)!!
    }
  }

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
  ) {
    with(FACILITIES) {
      dslContext
          .insertInto(FACILITIES)
          .set(CONNECTION_STATE_ID, FacilityConnectionState.NotConnected)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(DESCRIPTION, description)
          .set(ID, id.toIdWrapper { FacilityId(it) })
          .set(IDLE_AFTER_TIME, idleAfterTime)
          .set(IDLE_SINCE_TIME, idleSinceTime)
          .set(LAST_NOTIFICATION_DATE, lastNotificationDate)
          .set(LAST_TIMESERIES_TIME, lastTimeseriesTime)
          .set(MAX_IDLE_MINUTES, maxIdleMinutes)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(NAME, name)
          .set(NEXT_NOTIFICATION_TIME, nextNotificationTime)
          .set(ORGANIZATION_ID, organizationId.toIdWrapper { OrganizationId(it) })
          .set(TIME_ZONE, timeZone)
          .set(TYPE_ID, type)
          .execute()
    }
  }

  protected fun insertDevice(
      id: Any,
      facilityId: Any = this.facilityId,
      name: String = "device $id",
      createdBy: UserId = currentUser().userId,
      type: String = "type"
  ) {
    with(DEVICES) {
      dslContext
          .insertInto(DEVICES)
          .set(ADDRESS, "address")
          .set(CREATED_BY, createdBy)
          .set(DEVICE_TYPE, type)
          .set(FACILITY_ID, facilityId.toIdWrapper { FacilityId(it) })
          .set(ID, id.toIdWrapper { DeviceId(it) })
          .set(MAKE, "make")
          .set(MODEL, "model")
          .set(MODIFIED_BY, createdBy)
          .set(NAME, name)
          .set(PROTOCOL, "protocol")
          .execute()
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
      dslContext
          .insertInto(AUTOMATIONS)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(DEVICE_ID, deviceId?.toIdWrapper { DeviceId(it) })
          .set(FACILITY_ID, facilityId.toIdWrapper { FacilityId(it) })
          .set(ID, id.toIdWrapper { AutomationId(it) })
          .set(LOWER_THRESHOLD, lowerThreshold)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(NAME, name)
          .set(TIMESERIES_NAME, timeseriesName)
          .set(TYPE, type)
          .set(UPPER_THRESHOLD, upperThreshold)
          .execute()
    }
  }

  protected fun insertSpecies(
      speciesId: Any? = null,
      scientificName: String = "Species $speciesId",
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      modifiedTime: Instant = Instant.EPOCH,
      organizationId: Any = this.organizationId,
      deletedTime: Instant? = null,
      checkedTime: Instant? = null,
      initialScientificName: String = scientificName,
      commonName: String? = null,
  ): SpeciesId {
    val speciesIdWrapper = speciesId?.toIdWrapper { SpeciesId(it) }
    val organizationIdWrapper = organizationId.toIdWrapper { OrganizationId(it) }

    return with(SPECIES) {
      dslContext
          .insertInto(SPECIES)
          .set(CHECKED_TIME, checkedTime)
          .set(COMMON_NAME, commonName)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, createdTime)
          .set(DELETED_BY, if (deletedTime != null) createdBy else null)
          .set(DELETED_TIME, deletedTime)
          .apply { speciesIdWrapper?.let { set(ID, it) } }
          .set(INITIAL_SCIENTIFIC_NAME, initialScientificName)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, modifiedTime)
          .set(ORGANIZATION_ID, organizationIdWrapper)
          .set(SCIENTIFIC_NAME, scientificName)
          .returning(ID)
          .fetchOne(ID)!!
    }
  }

  /** Creates a user that can be referenced by various tests. */
  fun insertUser(
      userId: Any = currentUser().userId,
      authId: String? = "$userId",
      email: String = "$userId@terraformation.com",
      firstName: String? = "First",
      lastName: String? = "Last",
      type: UserType = UserType.Individual,
      emailNotificationsEnabled: Boolean = false,
      timeZone: ZoneId? = null,
      locale: Locale? = null,
  ) {
    with(USERS) {
      dslContext
          .insertInto(USERS)
          .set(AUTH_ID, authId)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(EMAIL, email)
          .set(EMAIL_NOTIFICATIONS_ENABLED, emailNotificationsEnabled)
          .set(ID, userId.toIdWrapper { UserId(it) })
          .set(FIRST_NAME, firstName)
          .set(LAST_NAME, lastName)
          .set(LOCALE, locale)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(TIME_ZONE, timeZone)
          .set(USER_TYPE_ID, type)
          .execute()
    }
  }

  /** Adds a user to an organization. */
  fun insertOrganizationUser(
      userId: Any = currentUser().userId,
      organizationId: Any = this.organizationId,
      role: Role = Role.CONTRIBUTOR,
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
          .set(ROLE_ID, role.id)
          .set(USER_ID, userId.toIdWrapper { UserId(it) })
          .execute()
    }
  }

  /** Adds a storage location to a facility. */
  fun insertStorageLocation(
      id: Any,
      facilityId: Any = this.facilityId,
      name: String = "Location $id",
      condition: StorageCondition = StorageCondition.Freezer,
      createdBy: UserId = currentUser().userId,
  ) {
    with(STORAGE_LOCATIONS) {
      dslContext
          .insertInto(STORAGE_LOCATIONS)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(CONDITION_ID, condition)
          .set(FACILITY_ID, facilityId.toIdWrapper { FacilityId(it) })
          .set(ID, id.toIdWrapper { StorageLocationId(it) })
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(NAME, name)
          .execute()
    }
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
      dslContext
          .insertInto(UPLOADS)
          .set(CONTENT_TYPE, contentType)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, createdTime)
          .set(FACILITY_ID, facilityId)
          .set(FILENAME, fileName)
          .set(ID, id.toIdWrapper { UploadId(it) })
          .set(LOCALE, locale)
          .set(ORGANIZATION_ID, organizationId)
          .set(STATUS_ID, status)
          .set(STORAGE_URL, storageUrl)
          .set(TYPE_ID, type)
          .execute()
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

    return rowWithDefaults.id!!
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
      readyQuantity: Int = row.readyQuantity ?: 0,
      readyByDate: LocalDate? = row.readyByDate,
      speciesId: Any = row.speciesId ?: throw IllegalArgumentException("Missing species ID"),
      version: Int = row.version ?: 1,
      batchNumber: String = row.batchNumber ?: id?.toString() ?: "${nextBatchNuber++}",
  ): BatchId {
    val rowWithDefaults =
        row.copy(
            addedDate = addedDate,
            batchNumber = batchNumber,
            createdBy = createdBy,
            createdTime = createdTime,
            facilityId = facilityId.toIdWrapper { FacilityId(it) },
            germinatingQuantity = germinatingQuantity,
            id = id?.toIdWrapper { BatchId(it) },
            latestObservedGerminatingQuantity = germinatingQuantity,
            latestObservedNotReadyQuantity = notReadyQuantity,
            latestObservedReadyQuantity = readyQuantity,
            latestObservedTime = createdTime,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            notReadyQuantity = notReadyQuantity,
            organizationId = organizationId.toIdWrapper { OrganizationId(it) },
            readyQuantity = readyQuantity,
            readyByDate = readyByDate,
            speciesId = speciesId.toIdWrapper { SpeciesId(it) },
            version = version,
        )

    batchesDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!
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

    return rowWithDefaults.id!!
  }

  fun insertBatchWithdrawal(
      row: BatchWithdrawalsRow = BatchWithdrawalsRow(),
      batchId: Any = row.batchId ?: throw IllegalArgumentException("Missing batch ID"),
      destinationBatchId: Any? = row.destinationBatchId,
      germinatingQuantityWithdrawn: Int = row.germinatingQuantityWithdrawn ?: 0,
      notReadyQuantityWithdrawn: Int = row.notReadyQuantityWithdrawn ?: 0,
      readyQuantityWithdrawn: Int = row.readyQuantityWithdrawn ?: 0,
      withdrawalId: Any =
          row.withdrawalId ?: throw IllegalArgumentException("Missing withdrawal ID")
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
      boundary: Geometry? = row.boundary,
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      id: Any? = row.id,
      organizationId: Any = row.organizationId ?: this.organizationId,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      name: String = row.name ?: id?.let { "Site $id" } ?: "Site ${nextPlantingSiteNumber++}",
      timeZone: ZoneId? = row.timeZone,
  ): PlantingSiteId {
    val rowWithDefaults =
        row.copy(
            boundary = boundary,
            createdBy = createdBy,
            createdTime = createdTime,
            id = id?.toIdWrapper { PlantingSiteId(it) },
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            organizationId = organizationId.toIdWrapper { OrganizationId(it) },
            timeZone = timeZone,
        )

    plantingSitesDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!
  }

  private var nextPlantingZoneNumber: Int = 1

  fun insertPlantingZone(
      row: PlantingZonesRow = PlantingZonesRow(),
      boundary: Geometry? = row.boundary,
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      id: Any? = row.id,
      plantingSiteId: Any =
          row.plantingSiteId ?: throw IllegalArgumentException("Missing planting site ID"),
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      name: String = row.name ?: id?.let { "Z$id" } ?: "Z${nextPlantingZoneNumber++}",
  ): PlantingZoneId {
    val rowWithDefaults =
        row.copy(
            boundary = boundary,
            createdBy = createdBy,
            createdTime = createdTime,
            id = id?.toIdWrapper { PlantingZoneId(it) },
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            plantingSiteId = plantingSiteId.toIdWrapper { PlantingSiteId(it) },
        )

    plantingZonesDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!
  }

  private var nextPlotNumber: Int = 1

  fun insertPlot(
      row: PlotsRow = PlotsRow(),
      boundary: Geometry? = row.boundary,
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      id: Any? = row.id,
      plantingSiteId: Any? = row.plantingSiteId,
      plantingZoneId: Any =
          row.plantingZoneId ?: throw IllegalArgumentException("Missing planting zone ID"),
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      name: String = row.name ?: id?.let { "$id" } ?: "${nextPlotNumber++}",
      fullName: String = "Z1-$name",
  ): PlotId {
    val plantingZoneIdWrapper = plantingZoneId.toIdWrapper { PlantingZoneId(it) }
    val plantingSiteIdWrapper =
        plantingSiteId?.toIdWrapper { PlantingSiteId(it) }
            ?: plantingZonesDao.fetchOneById(plantingZoneIdWrapper)?.plantingSiteId
                ?: throw IllegalArgumentException("Missing planting site ID")

    val rowWithDefaults =
        row.copy(
            boundary = boundary,
            createdBy = createdBy,
            createdTime = createdTime,
            fullName = fullName,
            id = id?.toIdWrapper { PlotId(it) },
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            plantingSiteId = plantingSiteIdWrapper,
            plantingZoneId = plantingZoneIdWrapper,
        )

    plotsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!
  }

  fun insertDelivery(
      row: DeliveriesRow = DeliveriesRow(),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      id: Any? = row.id,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      plantingSiteId: Any =
          row.plantingSiteId ?: throw IllegalArgumentException("Missing planting site ID"),
      withdrawalId: Any =
          row.withdrawalId ?: throw IllegalArgumentException("Missing withdrawal ID"),
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

    return rowWithDetails.id!!
  }

  fun insertPlanting(
      row: PlantingsRow = PlantingsRow(),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      deliveryId: Any = row.deliveryId ?: throw IllegalArgumentException("Missing delivery ID"),
      id: Any? = row.id,
      numPlants: Int = row.numPlants ?: 1,
      plantingSiteId: Any? = row.plantingSiteId,
      plantingTypeId: PlantingType = row.plantingTypeId ?: PlantingType.Delivery,
      plotId: Any? = row.plotId,
      speciesId: Any = row.speciesId ?: throw IllegalArgumentException("Missing species ID"),
  ): PlantingId {
    val deliveryIdWrapper = deliveryId.toIdWrapper { DeliveryId(it) }
    val plantingSiteIdWrapper =
        plantingSiteId?.toIdWrapper { PlantingSiteId(it) }
            ?: deliveriesDao.fetchOneById(deliveryIdWrapper)?.plantingSiteId
                ?: throw IllegalArgumentException("Missing planting site ID")

    val rowWithDefaults =
        row.copy(
            createdBy = createdBy,
            createdTime = createdTime,
            deliveryId = deliveryIdWrapper,
            id = id?.toIdWrapper { PlantingId(it) },
            numPlants = numPlants,
            plantingSiteId = plantingSiteIdWrapper,
            plantingTypeId = plantingTypeId,
            plotId = plotId?.toIdWrapper { PlotId(it) },
            speciesId = speciesId.toIdWrapper { SpeciesId(it) },
        )

    plantingsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!
  }

  fun insertTimeZone(timeZone: Any = ZoneId.of("Pacific/Honolulu")): ZoneId {
    val zoneId = if (timeZone is ZoneId) timeZone else ZoneId.of("$timeZone")
    timeZonesDao.insert(TimeZonesRow(zoneId))
    return zoneId
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
