package com.terraformation.seedbank.db

import com.terraformation.seedbank.db.tables.references.ORGANIZATION
import com.terraformation.seedbank.db.tables.references.SITE
import com.terraformation.seedbank.db.tables.references.SITE_MODULE
import java.math.BigDecimal
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
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
@ContextConfiguration(initializers = [DatabaseTest.DockerPostgresDataSourceInitializer::class])
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

  /** Creates an organization, site, and site module that can be referenced by various tests. */
  fun insertSiteData() {
    with(ORGANIZATION) { dslContext.insertInto(ORGANIZATION).set(ID, 1).set(NAME, "dev").execute() }

    with(SITE) {
      dslContext
          .insertInto(SITE)
          .set(ID, 10)
          .set(ORGANIZATION_ID, 1)
          .set(NAME, "sim")
          .set(LATITUDE, BigDecimal.valueOf(1))
          .set(LONGITUDE, BigDecimal.valueOf(2))
          .execute()
    }

    with(SITE_MODULE) {
      dslContext
          .insertInto(SITE_MODULE)
          .set(ID, 100)
          .set(SITE_ID, 10)
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
