package com.terraformation.backend.db

import com.terraformation.backend.config.FacilityIdConfigConverter
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.tables.references.FACILITIES
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

  /** Creates an organization, site, and facility that can be referenced by various tests. */
  fun insertSiteData() {
    val organizationId = OrganizationId(1)
    val projectId = ProjectId(2)
    val siteId = SiteId(10)
    val facilityId = FacilityId(100)

    with(ORGANIZATIONS) {
      dslContext.insertInto(ORGANIZATIONS).set(ID, organizationId).set(NAME, "dev").execute()
    }

    with(PROJECTS) {
      dslContext
          .insertInto(PROJECTS)
          .set(ID, projectId)
          .set(ORGANIZATION_ID, organizationId)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(NAME, "project")
          .execute()
    }

    with(SITES) {
      dslContext
          .insertInto(SITES)
          .set(ID, siteId)
          .set(PROJECT_ID, projectId)
          .set(NAME, "sim")
          .set(LATITUDE, BigDecimal.valueOf(1))
          .set(LONGITUDE, BigDecimal.valueOf(2))
          .execute()
    }

    with(FACILITIES) {
      dslContext
          .insertInto(FACILITIES)
          .set(ID, facilityId)
          .set(SITE_ID, siteId)
          .set(TYPE_ID, 1)
          .set(NAME, "ohana")
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
    val postgresContainer = PostgreSQLContainer<PostgreSQLContainer<*>>("postgres:12")
    var started: Boolean = false
  }
}
