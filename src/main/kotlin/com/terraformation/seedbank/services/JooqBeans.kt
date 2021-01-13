package com.terraformation.seedbank.services

import com.terraformation.seedbank.db.tables.daos.ApiKeyDao
import com.terraformation.seedbank.db.tables.daos.SiteDao
import com.terraformation.seedbank.db.tables.daos.TimeseriesDao
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Makes generated jOOQ classes available for dependency injection. */
@Configuration
class JooqBeans(dslContext: DSLContext) {
  private val configuration = dslContext.configuration()

  @Bean fun apiKeyDao() = ApiKeyDao(configuration)
  @Bean fun siteDao() = SiteDao(configuration)
  @Bean fun timeseriesDao() = TimeseriesDao(configuration)
}
