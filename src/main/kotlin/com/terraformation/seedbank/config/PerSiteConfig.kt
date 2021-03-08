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
import java.time.Duration
import java.time.Instant
import javax.annotation.ManagedBean
import javax.validation.constraints.NotEmpty
import org.jooq.DAO
import org.jooq.DSLContext
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.TaskScheduler

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
    private val dslContext: DSLContext,
    private val organizationDao: OrganizationDao,
    private val siteDao: SiteDao,
    private val siteModuleDao: SiteModuleDao,
    private val storageLocationDao: StorageLocationDao,
    private val objectMapper: ObjectMapper,
    private val serverConfig: TerrawareServerConfig,
    private val taskScheduler: TaskScheduler
) {
  private val log = perClassLogger()

  @EventListener
  fun scheduleTasks(event: ApplicationStartedEvent) {
    if (serverConfig.siteConfigRefreshSecs > 0) {
      taskScheduler.scheduleAtFixedRate(
          this::refreshConfig,
          Instant.EPOCH,
          Duration.ofSeconds(serverConfig.siteConfigRefreshSecs))
    } else {
      log.info("Disabling periodic refresh of per-site configuration")
    }
  }

  private fun refreshConfig() {
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
    // Any entries that aren't explicitly marked as disabled in the config should be enabled.
    perSiteConfig.devices.forEach { it.enabled = it.enabled ?: true }
    perSiteConfig.organizations.forEach { it.enabled = it.enabled ?: true }
    perSiteConfig.sites.forEach { it.enabled = it.enabled ?: true }
    perSiteConfig.siteModules.forEach { it.enabled = it.enabled ?: true }
    perSiteConfig.storageLocations.forEach { it.enabled = it.enabled ?: true }

    // Need to insert and delete IDs in the right order because there are foreign key relationships.
    insertAndUpdate(perSiteConfig.organizations, organizationDao)
    insertAndUpdate(perSiteConfig.sites, siteDao)
    insertAndUpdate(perSiteConfig.siteModules, siteModuleDao)
    insertAndUpdate(perSiteConfig.devices, deviceDao)
    insertAndUpdate(perSiteConfig.storageLocations, storageLocationDao)

    delete(perSiteConfig.devices, deviceDao) { it.enabled = false }
    delete(perSiteConfig.storageLocations, storageLocationDao) { it.enabled = false }
    delete(perSiteConfig.siteModules, siteModuleDao) { it.enabled = false }
    delete(perSiteConfig.sites, siteDao) { it.enabled = false }
    delete(perSiteConfig.organizations, organizationDao) { it.enabled = false }
  }

  private fun <T, I : Number> insertAndUpdate(desired: List<T>, dao: DAO<*, T, I>) {
    val existingIds = dao.findAll().mapNotNull { dao.getId(it) }.toSet()

    dao.update(desired.filter { dao.getId(it) in existingIds })
    dao.insert(desired.filter { dao.getId(it) !in existingIds })
  }

  private fun <T, I : Number> delete(desired: List<T>, dao: DAO<*, T, I>, disable: (T) -> Unit) {
    val existing = dao.findAll()
    val existingById = existing.associateBy { dao.getId(it)!! }
    val existingIds = existingById.keys
    val idsToDelete = existingIds - desired.map { dao.getId(it) }

    idsToDelete.forEach { id ->
      try {
        // Delete in a transaction so that if the delete fails due to an integrity constraint
        // violation, any enclosing transaction won't also be rolled back.
        dslContext.transaction { _ -> dao.deleteById(id) }
      } catch (e: DataIntegrityViolationException) {
        val item = existingById[id]!!
        disable(item)
        dao.update(item)
      }
    }
  }
}
