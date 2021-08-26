package com.terraformation.backend.db

import com.terraformation.backend.db.tables.daos.AccessionPhotosDao
import com.terraformation.backend.db.tables.daos.AccessionsDao
import com.terraformation.backend.db.tables.daos.DevicesDao
import com.terraformation.backend.db.tables.daos.FacilitiesDao
import com.terraformation.backend.db.tables.daos.FeaturesDao
import com.terraformation.backend.db.tables.daos.LayersDao
import com.terraformation.backend.db.tables.daos.OrganizationsDao
import com.terraformation.backend.db.tables.daos.PhotosDao
import com.terraformation.backend.db.tables.daos.PlantObservationsDao
import com.terraformation.backend.db.tables.daos.PlantsDao
import com.terraformation.backend.db.tables.daos.ProjectsDao
import com.terraformation.backend.db.tables.daos.SitesDao
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.daos.StorageLocationsDao
import com.terraformation.backend.db.tables.daos.TaskProcessedTimesDao
import com.terraformation.backend.db.tables.daos.ThumbnailDao
import com.terraformation.backend.db.tables.daos.TimeseriesDao
import com.terraformation.backend.db.tables.daos.UsersDao
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
class JooqDaoBeans(dslContext: DSLContext) {
  private val configuration = dslContext.configuration()

  // jOOQ DAO classes. Only instantiate the ones we actually use.

  @Bean fun accessionPhotoDao() = AccessionPhotosDao(configuration)
  @Bean fun accessionsDao() = AccessionsDao(configuration)
  @Bean fun deviceDao() = DevicesDao(configuration)
  @Bean fun facilitiesDao() = FacilitiesDao(configuration)
  @Bean fun featuresDao() = FeaturesDao(configuration)
  @Bean fun layersDao() = LayersDao(configuration)
  @Bean fun organizationDao() = OrganizationsDao(configuration)
  @Bean fun photosDao() = PhotosDao(configuration)
  @Bean fun plantsDao() = PlantsDao(configuration)
  @Bean fun PlantObservationsDao() = PlantObservationsDao(configuration)
  @Bean fun projectsDao() = ProjectsDao(configuration)
  @Bean fun siteDao() = SitesDao(configuration)
  @Bean fun speciesDao() = SpeciesDao(configuration)
  @Bean fun storageLocationDao() = StorageLocationsDao(configuration)
  @Bean fun taskProcessedTimeDao() = TaskProcessedTimesDao(configuration)
  @Bean fun thumbnailDao() = ThumbnailDao(configuration)
  @Bean fun timeseriesDao() = TimeseriesDao(configuration)
  @Bean fun usersDao() = UsersDao(configuration)
}
