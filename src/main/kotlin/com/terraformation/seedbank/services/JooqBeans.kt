package com.terraformation.seedbank.services

import com.terraformation.seedbank.db.tables.daos.*
import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import javax.inject.Inject
import org.jooq.Configuration
import org.jooq.DSLContext

/** Makes generated jOOQ classes available for dependency injection. */
@Factory
class JooqBeans {
  private lateinit var configuration: Configuration

  @Inject
  fun setDslContext(dslContext: DSLContext) {
    configuration = dslContext.configuration()
  }

  @Bean fun apiKeyDao() = ApiKeyDao(configuration)
  @Bean fun siteDao() = SiteDao(configuration)
  @Bean fun timeseriesDao() = TimeseriesDao(configuration)
}
