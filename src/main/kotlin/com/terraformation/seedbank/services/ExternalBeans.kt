package com.terraformation.seedbank.services

import com.terraformation.seedbank.db.tables.daos.AccessionPhotosDao
import com.terraformation.seedbank.db.tables.daos.ApiKeysDao
import com.terraformation.seedbank.db.tables.daos.DevicesDao
import com.terraformation.seedbank.db.tables.daos.OrganizationsDao
import com.terraformation.seedbank.db.tables.daos.SiteModulesDao
import com.terraformation.seedbank.db.tables.daos.SitesDao
import com.terraformation.seedbank.db.tables.daos.SpeciesDao
import com.terraformation.seedbank.db.tables.daos.StorageLocationsDao
import com.terraformation.seedbank.db.tables.daos.TaskProcessedTimesDao
import com.terraformation.seedbank.db.tables.daos.TimeseriesDao
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

  @Bean fun accessionPhotoDao() = AccessionPhotosDao(configuration)
  @Bean fun apiKeyDao() = ApiKeysDao(configuration)
  @Bean fun deviceDao() = DevicesDao(configuration)
  @Bean fun organizationDao() = OrganizationsDao(configuration)
  @Bean fun siteDao() = SitesDao(configuration)
  @Bean fun siteModuleDao() = SiteModulesDao(configuration)
  @Bean fun speciesDao() = SpeciesDao(configuration)
  @Bean fun storageLocationDao() = StorageLocationsDao(configuration)
  @Bean fun taskProcessedTimeDao() = TaskProcessedTimesDao(configuration)
  @Bean fun timeseriesDao() = TimeseriesDao(configuration)
}
