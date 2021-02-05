package com.terraformation.seedbank.services

import com.terraformation.seedbank.db.tables.daos.ApiKeyDao
import com.terraformation.seedbank.db.tables.daos.DeviceDao
import com.terraformation.seedbank.db.tables.daos.OrganizationDao
import com.terraformation.seedbank.db.tables.daos.SiteDao
import com.terraformation.seedbank.db.tables.daos.SiteModuleDao
import com.terraformation.seedbank.db.tables.daos.StorageLocationDao
import com.terraformation.seedbank.db.tables.daos.TimeseriesDao
import java.time.Clock
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Registers instances of non-application-code classes as Spring beans so they can be cleanly
 * replaced in tests.
 */
@Configuration
@EnableScheduling
class ExternalBeans(dslContext: DSLContext) {
  private val configuration = dslContext.configuration()

  // jOOQ DAO classes. Only instantiate the ones we actually use.

  @Bean fun apiKeyDao() = ApiKeyDao(configuration)
  @Bean fun deviceDao() = DeviceDao(configuration)
  @Bean fun organizationDao() = OrganizationDao(configuration)
  @Bean fun siteDao() = SiteDao(configuration)
  @Bean fun siteModuleDao() = SiteModuleDao(configuration)
  @Bean fun storageLocationDao() = StorageLocationDao(configuration)
  @Bean fun timeseriesDao() = TimeseriesDao(configuration)

  @Bean fun clock() = Clock.systemUTC()!!
}
