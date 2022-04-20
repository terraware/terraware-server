package com.terraformation.backend.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.tables.daos.AccessionGerminationTestTypesDao
import com.terraformation.backend.db.tables.daos.AccessionPhotosDao
import com.terraformation.backend.db.tables.daos.AccessionsDao
import com.terraformation.backend.db.tables.daos.AppDevicesDao
import com.terraformation.backend.db.tables.daos.AutomationsDao
import com.terraformation.backend.db.tables.daos.BagsDao
import com.terraformation.backend.db.tables.daos.DevicesDao
import com.terraformation.backend.db.tables.daos.FacilitiesDao
import com.terraformation.backend.db.tables.daos.FacilityAlertRecipientsDao
import com.terraformation.backend.db.tables.daos.GeolocationsDao
import com.terraformation.backend.db.tables.daos.GerminationTestsDao
import com.terraformation.backend.db.tables.daos.GerminationsDao
import com.terraformation.backend.db.tables.daos.OrganizationsDao
import com.terraformation.backend.db.tables.daos.PhotosDao
import com.terraformation.backend.db.tables.daos.ProjectTypeSelectionsDao
import com.terraformation.backend.db.tables.daos.ProjectUsersDao
import com.terraformation.backend.db.tables.daos.ProjectsDao
import com.terraformation.backend.db.tables.daos.SitesDao
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.daos.SpeciesNamesDao
import com.terraformation.backend.db.tables.daos.SpeciesOptionsDao
import com.terraformation.backend.db.tables.daos.StorageLocationsDao
import com.terraformation.backend.db.tables.daos.ThumbnailsDao
import com.terraformation.backend.db.tables.daos.TimeseriesDao
import com.terraformation.backend.db.tables.daos.UsersDao
import com.terraformation.backend.db.tables.daos.WithdrawalsDao
import com.terraformation.backend.db.tables.references.DEVICES
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.PROJECT_TYPE_SELECTIONS
import com.terraformation.backend.db.tables.references.PROJECT_USERS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.SPECIES_NAMES
import com.terraformation.backend.db.tables.references.SPECIES_OPTIONS
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.tables.references.USERS
import java.time.Instant
import java.time.LocalDate
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSupertypeOf
import net.postgis.jdbc.geometry.Point
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
 * Postgresql function name that retrieves serial sequence name. This is also the column name that
 * holds the result in a successful response.
 * @see https://www.postgresql.org/docs/9.2/functions-info.html
 */
const val PG_GET_SERIAL_SEQUENCE = "pg_get_serial_sequence"

/**
 * Base class for database-backed tests. Subclass this to get a fully-configured database with a
 * [DSLContext] ready to use. The database is run in a Docker container which is torn down after all
 * tests have finished. This cuts down on the chance that tests will behave differently from one
 * development environment to the next.
 *
 * In general, you should only use this for testing database-centric code! Do not put SQL queries in
 * the middle of business logic. Instead, pull the queries out into data-access classes (the code
 * base uses the term "fetcher" for these) and stub out the fetchers. Database-backed tests are
 * slower and are usually not as easy to read or maintain.
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
 * to reset before each test method. Use `\ds` in the `psql` shell to list all the sequence names.
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

  @BeforeEach
  fun resetSequences() {
    sequencesToReset.forEach { sequence -> dslContext.alterSequence(sequence).restart().execute() }
  }

  fun getSerialSequenceNames(table: Table<out Record>): List<String> {
    return table.primaryKey!!.fields.mapNotNull { field ->
      try {
        dslContext
                .select(
                    DSL.function(
                        PG_GET_SERIAL_SEQUENCE,
                        SQLDataType.VARCHAR,
                        DSL.field("{0}, {1}", table.name, field.name)))
                .fetchOne(PG_GET_SERIAL_SEQUENCE)!!
            .toString()
      } catch (e: Exception) {
        println(e)
        null
      }
    }
  }

  @BeforeEach
  fun resetSequencesForTables() {
    tablesToResetSequences
        .map { table -> getSerialSequenceNames(table) }
        .flatten()
        .toSet()
        .forEach { sequenceName ->
          dslContext.alterSequence(DSL.unquotedName(sequenceName)).restart().execute()
        }
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
  private inline fun <R : Any, reified T : Any> R.toIdWrapper(wrapperConstructor: (Long) -> T): T {
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

  protected val accessionGerminationTestTypesDao: AccessionGerminationTestTypesDao by lazyDao()
  protected val accessionPhotosDao: AccessionPhotosDao by lazyDao()
  protected val accessionsDao: AccessionsDao by lazyDao()
  protected val appDevicesDao: AppDevicesDao by lazyDao()
  protected val automationsDao: AutomationsDao by lazyDao()
  protected val bagsDao: BagsDao by lazyDao()
  protected val devicesDao: DevicesDao by lazyDao()
  protected val facilitiesDao: FacilitiesDao by lazyDao()
  protected val facilityAlertRecipientsDao: FacilityAlertRecipientsDao by lazyDao()
  protected val geolocationsDao: GeolocationsDao by lazyDao()
  protected val germinationsDao: GerminationsDao by lazyDao()
  protected val germinationTestsDao: GerminationTestsDao by lazyDao()
  protected val organizationsDao: OrganizationsDao by lazyDao()
  protected val photosDao: PhotosDao by lazyDao()
  protected val projectsDao: ProjectsDao by lazyDao()
  protected val projectTypeSelectionsDao: ProjectTypeSelectionsDao by lazyDao()
  protected val projectUsersDao: ProjectUsersDao by lazyDao()
  protected val sitesDao: SitesDao by lazyDao()
  protected val speciesDao: SpeciesDao by lazyDao()
  protected val speciesNamesDao: SpeciesNamesDao by lazyDao()
  protected val speciesOptionsDao: SpeciesOptionsDao by lazyDao()
  protected val storageLocationsDao: StorageLocationsDao by lazyDao()
  protected val thumbnailsDao: ThumbnailsDao by lazyDao()
  protected val timeseriesDao: TimeseriesDao by lazyDao()
  protected val usersDao: UsersDao by lazyDao()
  protected val withdrawalsDao: WithdrawalsDao by lazyDao()

  protected fun insertOrganization(
      id: Any? = null,
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

  protected fun insertProject(
      id: Any,
      organizationId: Any = "$id".toLong() / 10,
      name: String = "Project $id",
      createdBy: UserId = currentUser().userId,
      description: String? = null,
      organizationWide: Boolean = false,
      startDate: LocalDate? = null,
      status: ProjectStatus? = null,
      types: Collection<ProjectType> = emptySet(),
  ) {
    val projectId = id.toIdWrapper { ProjectId(it) }

    with(PROJECTS) {
      dslContext
          .insertInto(PROJECTS)
          .set(DESCRIPTION, description)
          .set(ID, projectId)
          .set(ORGANIZATION_ID, organizationId.toIdWrapper { OrganizationId(it) })
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(NAME, name)
          .set(ORGANIZATION_WIDE, organizationWide)
          .set(START_DATE, startDate)
          .set(STATUS_ID, status)
          .execute()
    }

    types.forEach { type ->
      with(PROJECT_TYPE_SELECTIONS) {
        dslContext
            .insertInto(PROJECT_TYPE_SELECTIONS)
            .set(PROJECT_ID, projectId)
            .set(PROJECT_TYPE_ID, type)
            .execute()
      }
    }
  }

  protected fun insertSite(
      id: Any,
      projectId: Any = "$id".toLong() / 10,
      name: String = "Site $id",
      location: Point = mercatorPoint(1.0, 2.0, 0.0),
      createdBy: UserId = currentUser().userId,
      description: String? = null,
  ) {
    with(SITES) {
      dslContext
          .insertInto(SITES)
          .set(ID, id.toIdWrapper { SiteId(it) })
          .set(PROJECT_ID, projectId.toIdWrapper { ProjectId(it) })
          .set(NAME, name)
          .set(DESCRIPTION, description)
          .set(LOCATION, location)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .execute()
    }
  }

  protected fun insertFacility(
      id: Any,
      siteId: Any = "$id".toLong() / 10,
      name: String = "Facility $id",
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
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(ID, id.toIdWrapper { FacilityId(it) })
          .set(IDLE_AFTER_TIME, idleAfterTime)
          .set(IDLE_SINCE_TIME, idleSinceTime)
          .set(LAST_TIMESERIES_TIME, lastTimeseriesTime)
          .set(MAX_IDLE_MINUTES, maxIdleMinutes)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(NAME, name)
          .set(SITE_ID, siteId.toIdWrapper { SiteId(it) })
          .set(TYPE_ID, type)
          .execute()
    }
  }

  protected fun insertDevice(
      id: Any,
      facilityId: Any = "$id".toLong() / 10,
      name: String = "device $id",
      createdBy: UserId = currentUser().userId,
  ) {
    with(DEVICES) {
      dslContext
          .insertInto(DEVICES)
          .set(ADDRESS, "address")
          .set(CREATED_BY, createdBy)
          .set(DEVICE_TYPE, "type")
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

  protected fun insertSpecies(
      speciesId: Any,
      name: String = "Species $speciesId",
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      modifiedTime: Instant = Instant.EPOCH,
      organizationId: Any? = null,
      insertName: Boolean = organizationId != null,
      insertOption: Boolean = organizationId != null,
      speciesNameId: Any? = null,
  ) {
    val speciesIdWrapper = speciesId.toIdWrapper { SpeciesId(it) }
    val organizationIdWrapper = organizationId?.toIdWrapper { OrganizationId(it) }

    with(SPECIES) {
      dslContext
          .insertInto(SPECIES)
          .set(CREATED_TIME, createdTime)
          .set(ID, speciesIdWrapper)
          .set(MODIFIED_TIME, modifiedTime)
          .set(NAME, name)
          .execute()
    }

    if (insertName) {
      val speciesNameIdWrapper =
          speciesNameId?.toIdWrapper { SpeciesNameId(it) } ?: SpeciesNameId(speciesIdWrapper.value)

      with(SPECIES_NAMES) {
        dslContext
            .insertInto(SPECIES_NAMES)
            .set(CREATED_BY, createdBy)
            .set(CREATED_TIME, createdTime)
            .set(ID, speciesNameIdWrapper)
            .set(SPECIES_ID, speciesIdWrapper)
            .set(ORGANIZATION_ID, organizationIdWrapper)
            .set(MODIFIED_BY, createdBy)
            .set(MODIFIED_TIME, modifiedTime)
            .set(NAME, name)
            .execute()
      }
    }

    if (insertOption) {
      with(SPECIES_OPTIONS) {
        dslContext
            .insertInto(SPECIES_OPTIONS)
            .set(CREATED_BY, createdBy)
            .set(CREATED_TIME, createdTime)
            .set(MODIFIED_BY, createdBy)
            .set(MODIFIED_TIME, modifiedTime)
            .set(ORGANIZATION_ID, organizationIdWrapper)
            .set(SPECIES_ID, speciesIdWrapper)
            .execute()
      }
    }
  }

  /** Creates an organization, site, and facility that can be referenced by various tests. */
  fun insertSiteData() {
    insertUser()
    insertOrganization(1, "dev")
    insertProject(2, 1, "project")
    insertSite(10, 2, "sim")
    insertFacility(100, 10, "ohana")
  }

  /** Creates a user that can be referenced by various tests. */
  fun insertUser(
      userId: Any = currentUser().userId,
      authId: String? = "$userId",
      email: String = "$userId@terraformation.com",
      firstName: String? = "First",
      lastName: String? = "Last",
      type: UserType = UserType.Individual,
  ) {
    with(USERS) {
      dslContext
          .insertInto(USERS)
          .set(AUTH_ID, authId)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(EMAIL, email)
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
      organizationId: Any,
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

  /** Adds a user to a project. */
  fun insertProjectUser(
      userId: Any = currentUser().userId,
      projectId: Any,
      createdBy: UserId = currentUser().userId,
  ) {
    with(PROJECT_USERS) {
      dslContext
          .insertInto(PROJECT_USERS)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(PROJECT_ID, projectId.toIdWrapper { ProjectId(it) })
          .set(USER_ID, userId.toIdWrapper { UserId(it) })
          .execute()
    }
  }

  /** Adds a storage location to a facility. */
  fun insertStorageLocation(
      id: Any,
      facilityId: Any = "$id".toLong() / 10,
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

  @Suppress("UPPER_BOUND_VIOLATED_WARNING")
  companion object {
    private val imageName: DockerImageName =
        DockerImageName.parse("$POSTGRES_DOCKER_REPOSITORY:$POSTGRES_DOCKER_TAG")
            .asCompatibleSubstituteFor("postgres")

    val postgresContainer: PostgreSQLContainer<*> =
        PostgreSQLContainer<PostgreSQLContainer<*>>(imageName)
            .withDatabaseName("terraware")
            .withExposedPorts(PostgreSQLContainer.POSTGRESQL_PORT)
            .withNetwork(Network.newNetwork())
            .withNetworkAliases("postgres")
    var started: Boolean = false
  }
}
