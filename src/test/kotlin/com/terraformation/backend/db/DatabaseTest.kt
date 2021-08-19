package com.terraformation.backend.db

import com.terraformation.backend.config.FacilityIdConfigConverter
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.LAYERS
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.SITES
import java.math.BigDecimal
import java.time.Instant
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jooq.JooqTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.support.TestPropertySourceUtils
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

  protected fun insertOrganization(id: Long, name: String = "Organization $id") {
    with(ORGANIZATIONS) {
      dslContext
          .insertInto(ORGANIZATIONS)
          .set(ID, OrganizationId(id))
          .set(NAME, name)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .execute()
    }
  }

  protected fun insertProject(
      id: Long,
      organizationId: Long = id / 10,
      name: String = "Project $id"
  ) {
    with(PROJECTS) {
      dslContext
          .insertInto(PROJECTS)
          .set(ID, ProjectId(id))
          .set(ORGANIZATION_ID, OrganizationId(organizationId))
          .set(CREATED_TIME, Instant.EPOCH)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(NAME, name)
          .execute()
    }
  }

  protected fun insertSite(id: Long, projectId: Long = id / 10, name: String = "Site $id") {
    with(SITES) {
      dslContext
          .insertInto(SITES)
          .set(ID, SiteId(id))
          .set(PROJECT_ID, ProjectId(projectId))
          .set(NAME, name)
          .set(LATITUDE, BigDecimal.valueOf(1))
          .set(LONGITUDE, BigDecimal.valueOf(2))
          .execute()
    }
  }

  protected fun insertFacility(id: Long, siteId: Long = id / 10, name: String = "Facility $id") {
    with(FACILITIES) {
      dslContext
          .insertInto(FACILITIES)
          .set(ID, FacilityId(id))
          .set(SITE_ID, SiteId(siteId))
          .set(TYPE_ID, FacilityType.SeedBank)
          .set(NAME, name)
          .execute()
    }
  }

  protected fun insertLayer(
      id: Long,
      siteId: Long = id / 10,
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
          .set(ID, LayerId(id))
          .set(SITE_ID, SiteId(siteId))
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
      id: Long,
      layerId: Long = id / 10,
      shapeType: ShapeType = ShapeType.Point,
      altitude: Double? = null,
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
          .set(ID, FeatureId(id))
          .set(LAYER_ID, LayerId(layerId))
          .set(SHAPE_TYPE_ID, shapeType)
          .set(ALTITUDE, altitude)
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

  /** Creates an organization, site, and facility that can be referenced by various tests. */
  fun insertSiteData() {
    insertOrganization(1, "dev")
    insertProject(2, 1, "project")
    insertSite(10, 2, "sim")
    insertFacility(100, 10, "ohana")
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
    private val imageName: DockerImageName =
        DockerImageName.parse("postgis/postgis:12-3.1").asCompatibleSubstituteFor("postgres")
    val postgresContainer = PostgreSQLContainer<PostgreSQLContainer<*>>(imageName)
    var started: Boolean = false
  }
}
