package com.terraformation.backend.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.FacilityIdConfigConverter
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.FEATURE_PHOTOS
import com.terraformation.backend.db.tables.references.LAYERS
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.PHOTOS
import com.terraformation.backend.db.tables.references.PLANTS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.PROJECT_TYPE_SELECTIONS
import com.terraformation.backend.db.tables.references.PROJECT_USERS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.db.tables.references.USERS
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import net.postgis.jdbc.geometry.Geometry
import net.postgis.jdbc.geometry.Point
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jooq.JooqTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.support.TestPropertySourceUtils
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

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
@ContextConfiguration(
    initializers = [DatabaseTest.DockerPostgresDataSourceInitializer::class],
    classes = [FacilityIdConfigConverter::class])
@EnableConfigurationProperties(TerrawareServerConfig::class)
@JooqTest
@Testcontainers
abstract class DatabaseTest {
  @Autowired protected lateinit var dslContext: DSLContext

  /**
   * List of sequences to reset before each test method. Test classes can use this to get
   * predictable IDs when inserting new data.
   */
  protected val sequencesToReset: List<String>
    get() = emptyList()

  @BeforeEach
  fun resetSequences() {
    sequencesToReset.forEach { sequenceName ->
      dslContext.alterSequence(sequenceName).restart().execute()
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

  protected fun insertOrganization(
      id: Any? = null,
      name: String = "Organization $id",
      countryCode: String? = null,
      countrySubdivisionCode: String? = null,
  ): OrganizationId {
    return with(ORGANIZATIONS) {
      dslContext
          .insertInto(ORGANIZATIONS)
          .set(COUNTRY_CODE, countryCode)
          .set(COUNTRY_SUBDIVISION_CODE, countrySubdivisionCode)
          .set(CREATED_TIME, Instant.EPOCH)
          .apply { if (id != null) set(ID, id.toIdWrapper { OrganizationId(it) }) }
          .set(NAME, name)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .returning(ID)
          .fetchOne(ID)!!
    }
  }

  protected fun insertProject(
      id: Any,
      organizationId: Any = "$id".toLong() / 10,
      name: String = "Project $id",
      description: String? = null,
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
          .set(CREATED_TIME, Instant.EPOCH)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(NAME, name)
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
  ) {
    with(SITES) {
      dslContext
          .insertInto(SITES)
          .set(ID, id.toIdWrapper { SiteId(it) })
          .set(PROJECT_ID, projectId.toIdWrapper { ProjectId(it) })
          .set(NAME, name)
          .set(LOCATION, location)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .execute()
    }
  }

  protected fun insertFacility(
      id: Any,
      siteId: Any = "$id".toLong() / 10,
      name: String = "Facility $id",
      type: FacilityType = FacilityType.SeedBank
  ) {
    with(FACILITIES) {
      dslContext
          .insertInto(FACILITIES)
          .set(ID, id.toIdWrapper { FacilityId(it) })
          .set(SITE_ID, siteId.toIdWrapper { SiteId(it) })
          .set(TYPE_ID, type)
          .set(NAME, name)
          .execute()
    }
  }

  protected fun insertLayer(
      id: Any,
      siteId: Any = "$id".toLong() / 10,
      layerType: LayerType = LayerType.PlantsPlanted,
      tileSetName: String = "Tile set test name",
      proposed: Boolean = false,
      hidden: Boolean = false,
      deleted: Boolean = false,
      createdTime: Instant = Instant.EPOCH,
      modifiedTime: Instant = Instant.EPOCH
  ) {

    with(LAYERS) {
      dslContext
          .insertInto(LAYERS)
          .set(ID, id.toIdWrapper { LayerId(it) })
          .set(SITE_ID, siteId.toIdWrapper { SiteId(it) })
          .set(LAYER_TYPE_ID, layerType)
          .set(TILE_SET_NAME, tileSetName)
          .set(PROPOSED, proposed)
          .set(HIDDEN, hidden)
          .set(DELETED, deleted)
          .set(CREATED_TIME, createdTime)
          .set(MODIFIED_TIME, modifiedTime)
          .execute()
    }
  }

  protected fun insertFeature(
      id: Any,
      layerId: Any = "$id".toLong() / 10,
      geom: Geometry? = null,
      gpsHorizAccuracy: Double? = null,
      gpsVertAccuracy: Double? = null,
      attrib: String? = null,
      notes: String? = null,
      enteredTime: Instant = Instant.EPOCH,
      createdTime: Instant = Instant.EPOCH,
      modifiedTime: Instant = Instant.EPOCH,
  ) {
    with(FEATURES) {
      dslContext
          .insertInto(FEATURES)
          .set(ID, id.toIdWrapper { FeatureId(it) })
          .set(LAYER_ID, layerId.toIdWrapper { LayerId(it) })
          .set(GEOM, geom)
          .set(GPS_HORIZ_ACCURACY, gpsHorizAccuracy)
          .set(GPS_VERT_ACCURACY, gpsVertAccuracy)
          .set(ATTRIB, attrib)
          .set(NOTES, notes)
          .set(ENTERED_TIME, enteredTime)
          .set(CREATED_TIME, createdTime)
          .set(MODIFIED_TIME, modifiedTime)
          .execute()
    }
  }

  protected fun insertPhoto(
      id: Any,
      storageUrl: URI = URI("http://server/$id"),
      fileName: String = "$id.jpg",
      contentType: String = MediaType.IMAGE_JPEG_VALUE,
      size: Long = 1L,
      createdTime: Instant = Instant.EPOCH,
      capturedTime: Instant = Instant.EPOCH,
      modifiedTime: Instant = Instant.EPOCH,
  ) {
    with(PHOTOS) {
      dslContext
          .insertInto(PHOTOS)
          .set(CAPTURED_TIME, capturedTime)
          .set(CONTENT_TYPE, contentType)
          .set(CREATED_TIME, createdTime)
          .set(FILE_NAME, fileName)
          .set(ID, id.toIdWrapper { PhotoId(it) })
          .set(MODIFIED_TIME, modifiedTime)
          .set(SIZE, size)
          .set(STORAGE_URL, storageUrl)
          .execute()
    }
  }

  protected fun insertFeaturePhoto(
      photoId: Any,
      featureId: Any = "$photoId",
      plantObservationId: Any? = null,
  ) {
    with(FEATURE_PHOTOS) {
      dslContext
          .insertInto(FEATURE_PHOTOS)
          .set(PHOTO_ID, photoId.toIdWrapper { PhotoId(it) })
          .set(FEATURE_ID, featureId.toIdWrapper { FeatureId(it) })
          .apply {
            if (plantObservationId != null)
                set(PLANT_OBSERVATION_ID, plantObservationId.toIdWrapper { PlantObservationId(it) })
          }
          .execute()
    }
  }

  protected fun insertPlant(
      featureId: Any,
      label: String? = null,
      speciesId: Any? = null,
      naturalRegen: Boolean? = null,
      datePlanted: LocalDate? = null,
  ) {
    with(PLANTS) {
      dslContext
          .insertInto(PLANTS)
          .set(FEATURE_ID, featureId.toIdWrapper { FeatureId(it) })
          .set(LABEL, label)
          .set(SPECIES_ID, speciesId?.toIdWrapper { SpeciesId(it) })
          .set(NATURAL_REGEN, naturalRegen)
          .set(DATE_PLANTED, datePlanted)
          .execute()
    }
  }

  /** Creates an organization, site, and facility that can be referenced by various tests. */
  fun insertSiteData() {
    insertOrganization(1, "dev")
    insertProject(2, 1, "project")
    insertSite(10, 2, "sim")
    insertFacility(100, 10, "ohana")
  }

  /** Creates a user that can be referenced by various tests. */
  fun insertUser(
      userId: Any = currentUser().userId,
      authId: String? = "XYZ",
      email: String = "user@terraformation.com",
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
      pendingInvitationTime: Instant? = null,
  ) {
    with(ORGANIZATION_USERS) {
      dslContext
          .insertInto(ORGANIZATION_USERS)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(ORGANIZATION_ID, organizationId.toIdWrapper { OrganizationId(it) })
          .set(PENDING_INVITATION_TIME, pendingInvitationTime)
          .set(ROLE_ID, role.id)
          .set(USER_ID, userId.toIdWrapper { UserId(it) })
          .execute()
    }
  }

  /** Adds a user to a project. */
  fun insertProjectUser(
      userId: Any = currentUser().userId,
      projectId: Any,
  ) {
    with(PROJECT_USERS) {
      dslContext
          .insertInto(PROJECT_USERS)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(PROJECT_ID, projectId.toIdWrapper { ProjectId(it) })
          .set(USER_ID, userId.toIdWrapper { UserId(it) })
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
        DockerImageName.parse("postgis/postgis:12-3.1").asCompatibleSubstituteFor("postgres")
    val postgresContainer: PostgreSQLContainer<*> =
        PostgreSQLContainer<PostgreSQLContainer<*>>(imageName)
            .withDatabaseName("terraware")
            .withExposedPorts(PostgreSQLContainer.POSTGRESQL_PORT)
            .withNetwork(Network.newNetwork())
            .withNetworkAliases("postgres")
    var started: Boolean = false
  }
}
