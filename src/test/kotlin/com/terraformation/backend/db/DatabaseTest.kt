package com.terraformation.backend.db

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.tables.daos.AccessionPhotosDao
import com.terraformation.backend.db.tables.daos.AccessionsDao
import com.terraformation.backend.db.tables.daos.AppDevicesDao
import com.terraformation.backend.db.tables.daos.AutomationsDao
import com.terraformation.backend.db.tables.daos.BagsDao
import com.terraformation.backend.db.tables.daos.DeviceManagersDao
import com.terraformation.backend.db.tables.daos.DeviceTemplatesDao
import com.terraformation.backend.db.tables.daos.DevicesDao
import com.terraformation.backend.db.tables.daos.FacilitiesDao
import com.terraformation.backend.db.tables.daos.GeolocationsDao
import com.terraformation.backend.db.tables.daos.NotificationsDao
import com.terraformation.backend.db.tables.daos.OrganizationsDao
import com.terraformation.backend.db.tables.daos.PhotosDao
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.daos.SpeciesProblemsDao
import com.terraformation.backend.db.tables.daos.StorageLocationsDao
import com.terraformation.backend.db.tables.daos.ThumbnailsDao
import com.terraformation.backend.db.tables.daos.TimeseriesDao
import com.terraformation.backend.db.tables.daos.UploadProblemsDao
import com.terraformation.backend.db.tables.daos.UploadsDao
import com.terraformation.backend.db.tables.daos.UsersDao
import com.terraformation.backend.db.tables.daos.ViabilityTestResultsDao
import com.terraformation.backend.db.tables.daos.ViabilityTestSelectionsDao
import com.terraformation.backend.db.tables.daos.ViabilityTestsDao
import com.terraformation.backend.db.tables.daos.WithdrawalsDao
import com.terraformation.backend.db.tables.references.AUTOMATIONS
import com.terraformation.backend.db.tables.references.DEVICES
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.NOTIFICATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.tables.references.UPLOADS
import com.terraformation.backend.db.tables.references.USERS
import java.net.URI
import java.time.Instant
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSupertypeOf
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Sequence
import org.jooq.Table
import org.jooq.impl.DAOImpl
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.BeforeEach
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
 *
 * - Each test method is run in a transaction which is rolled back afterwards, so no need to worry
 * about test methods polluting the database for each other if they're writing values.
 * - But that means test methods can't use data written by previous methods. If your test method
 * needs sample data, either put it in a migration (migrations are run before any tests) or in a
 * helper method.
 * - Sequences, including the ones used to generate auto-increment primary keys, are normally not
 * reset when transactions are rolled back. But it is useful to have a predictable set of IDs to
 * compare against. So subclasses can override the [sequencesToReset] value with a list of sequences
 * to reset before each test method. Or, often more convenient, they can override
 * [tablesToResetSequences] with a list of tables whose primary key sequences should be reset.
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
   * List of sequences to reset before each test method. Test classes can use this to get
   * predictable IDs when inserting new data.
   */
  protected val sequencesToReset: List<Sequence<out Number>>
    get() = emptyList()

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
  fun resetSequences() {
    sequencesToReset.forEach { sequence -> dslContext.alterSequence(sequence).restart().execute() }
  }

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
        dslContext
            .select(
                DSL.function(
                    PG_GET_SERIAL_SEQUENCE,
                    SQLDataType.VARCHAR,
                    DSL.value(table.name),
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
   * which case it is turned into a Long and passed to [wrapperConstructor] or an ID of the desired
   * type (in which case it is returned to the caller).
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

  protected val accessionPhotosDao: AccessionPhotosDao by lazyDao()
  protected val accessionsDao: AccessionsDao by lazyDao()
  protected val appDevicesDao: AppDevicesDao by lazyDao()
  protected val automationsDao: AutomationsDao by lazyDao()
  protected val bagsDao: BagsDao by lazyDao()
  protected val deviceManagersDao: DeviceManagersDao by lazyDao()
  protected val devicesDao: DevicesDao by lazyDao()
  protected val deviceTemplatesDao: DeviceTemplatesDao by lazyDao()
  protected val facilitiesDao: FacilitiesDao by lazyDao()
  protected val geolocationsDao: GeolocationsDao by lazyDao()
  protected val notificationsDao: NotificationsDao by lazyDao()
  protected val organizationsDao: OrganizationsDao by lazyDao()
  protected val photosDao: PhotosDao by lazyDao()
  protected val speciesDao: SpeciesDao by lazyDao()
  protected val speciesProblemsDao: SpeciesProblemsDao by lazyDao()
  protected val storageLocationsDao: StorageLocationsDao by lazyDao()
  protected val thumbnailsDao: ThumbnailsDao by lazyDao()
  protected val timeseriesDao: TimeseriesDao by lazyDao()
  protected val uploadProblemsDao: UploadProblemsDao by lazyDao()
  protected val uploadsDao: UploadsDao by lazyDao()
  protected val usersDao: UsersDao by lazyDao()
  protected val viabilityTestResultsDao: ViabilityTestResultsDao by lazyDao()
  protected val viabilityTestsDao: ViabilityTestsDao by lazyDao()
  protected val viabilityTestSelectionsDao: ViabilityTestSelectionsDao by lazyDao()
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
          .set(LAST_TIMESERIES_TIME, lastTimeseriesTime)
          .set(MAX_IDLE_MINUTES, maxIdleMinutes)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(NAME, name)
          .set(ORGANIZATION_ID, organizationId.toIdWrapper { OrganizationId(it) })
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
      speciesId: Any,
      scientificName: String = "Species $speciesId",
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      modifiedTime: Instant = Instant.EPOCH,
      organizationId: Any = this.organizationId,
      deletedTime: Instant? = null,
      checkedTime: Instant? = null,
      initialScientificName: String = scientificName,
      commonName: String? = null,
  ) {
    val speciesIdWrapper = speciesId.toIdWrapper { SpeciesId(it) }
    val organizationIdWrapper = organizationId.toIdWrapper { OrganizationId(it) }

    with(SPECIES) {
      dslContext
          .insertInto(SPECIES)
          .set(CHECKED_TIME, checkedTime)
          .set(COMMON_NAME, commonName)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, createdTime)
          .set(DELETED_BY, if (deletedTime != null) createdBy else null)
          .set(DELETED_TIME, deletedTime)
          .set(ID, speciesIdWrapper)
          .set(INITIAL_SCIENTIFIC_NAME, initialScientificName)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, modifiedTime)
          .set(ORGANIZATION_ID, organizationIdWrapper)
          .set(SCIENTIFIC_NAME, scientificName)
          .execute()
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
          .set(MODIFIED_TIME, Instant.EPOCH)
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
  ) {
    with(UPLOADS) {
      dslContext
          .insertInto(UPLOADS)
          .set(CONTENT_TYPE, contentType)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, createdTime)
          .set(FILENAME, fileName)
          .set(ID, id.toIdWrapper { UploadId(it) })
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
