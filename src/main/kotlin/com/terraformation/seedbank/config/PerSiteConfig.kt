package com.terraformation.seedbank.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.seedbank.db.tables.daos.DeviceDao
import com.terraformation.seedbank.db.tables.daos.OrganizationDao
import com.terraformation.seedbank.db.tables.daos.SiteDao
import com.terraformation.seedbank.db.tables.daos.SiteModuleDao
import com.terraformation.seedbank.db.tables.daos.StorageLocationDao
import com.terraformation.seedbank.db.tables.pojos.Device
import com.terraformation.seedbank.db.tables.pojos.Organization
import com.terraformation.seedbank.db.tables.pojos.Site
import com.terraformation.seedbank.db.tables.pojos.SiteModule
import com.terraformation.seedbank.db.tables.pojos.StorageLocation
import com.terraformation.seedbank.services.perClassLogger
import java.io.IOException
import javax.annotation.ManagedBean
import javax.validation.constraints.NotEmpty
import org.jooq.DAO
import org.springframework.scheduling.annotation.Scheduled

data class PerSiteConfig(
    val devices: List<Device> = emptyList(),
    @NotEmpty val organizations: List<Organization>,
    @NotEmpty val sites: List<Site>,
    @NotEmpty val siteModules: List<SiteModule>,
    val storageLocations: List<StorageLocation> = emptyList(),
)

@ManagedBean
class PerSiteConfigUpdater(
    private val deviceDao: DeviceDao,
    private val organizationDao: OrganizationDao,
    private val siteDao: SiteDao,
    private val siteModuleDao: SiteModuleDao,
    private val storageLocationDao: StorageLocationDao,
    private val objectMapper: ObjectMapper,
    private val serverConfig: TerrawareServerConfig
) {
  private val log = perClassLogger()

  @Scheduled(initialDelay = 0, fixedRate = 3600000)
  fun refreshConfig() {
    log.info("Refreshing per-site configuration")

    val config = fetchConfig()
    if (config != null) {
      updateDatabase(config)
    }
  }

  fun fetchConfig(): PerSiteConfig? {
    return try {
      serverConfig.siteConfigUrl.inputStream.use { stream ->
        objectMapper.readValue<PerSiteConfig>(stream)
      }
    } catch (e: IOException) {
      log.info("Failed to fetch per-site configuration: ${e.message}")
      null
    }
  }

  fun updateDatabase(perSiteConfig: PerSiteConfig) {
    // Need to insert and delete IDs in the right order because there are foreign key relationships.
    insertAndUpdate(perSiteConfig.organizations, organizationDao)
    insertAndUpdate(perSiteConfig.sites, siteDao)
    insertAndUpdate(perSiteConfig.siteModules, siteModuleDao)
    insertAndUpdate(perSiteConfig.devices, deviceDao)
    insertAndUpdate(perSiteConfig.storageLocations, storageLocationDao)

    delete(perSiteConfig.devices, deviceDao)
    delete(perSiteConfig.storageLocations, storageLocationDao)
    delete(perSiteConfig.siteModules, siteModuleDao)
    delete(perSiteConfig.sites, siteDao)
    delete(perSiteConfig.organizations, organizationDao)
  }

  private fun <T> insertAndUpdate(desired: List<T>, dao: DAO<*, T, Long>) {
    val existingIds = dao.findAll().mapNotNull { dao.getId(it) }.toSet()

    desired.forEach { item ->
      if (dao.getId(item) in existingIds) {
        dao.update(item)
      } else {
        dao.insert(item)
      }
    }
  }

  private fun <T> delete(desired: List<T>, dao: DAO<*, T, Long>) {
    val existingIds = dao.findAll().mapNotNull { dao.getId(it) }.toSet()
    val idsToDelete = existingIds - desired.map { dao.getId(it) }

    if (idsToDelete.isNotEmpty()) {
      dao.deleteById(idsToDelete)
    }
  }
}
