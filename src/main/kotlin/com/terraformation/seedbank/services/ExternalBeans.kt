package com.terraformation.seedbank.services

import com.terraformation.seedbank.db.tables.daos.ApiKeyDao
import com.terraformation.seedbank.db.tables.daos.OrganizationDao
import com.terraformation.seedbank.db.tables.daos.SiteDao
import com.terraformation.seedbank.db.tables.daos.TimeseriesDao
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers instances of non-application-code classes as Spring beans so they can be cleanly
 * replaced in tests.
 */
@Configuration
class ExternalBeans(dslContext: DSLContext) {
  private val configuration = dslContext.configuration()

  // jOOQ DAO classes. Only instantiate the ones we actually use.

  @Bean fun apiKeyDao() = ApiKeyDao(configuration)
  @Bean fun organizationDao() = OrganizationDao(configuration)
  @Bean fun siteDao() = SiteDao(configuration)
  @Bean fun timeseriesDao() = TimeseriesDao(configuration)
}
