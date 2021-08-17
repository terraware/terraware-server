package com.terraformation.backend.config

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.db.tables.daos.DevicesDao
import com.terraformation.backend.db.tables.daos.StorageLocationsDao
import com.terraformation.backend.db.tables.pojos.DevicesRow
import com.terraformation.backend.db.tables.pojos.StorageLocationsRow
import com.terraformation.backend.log.perClassLogger
import java.io.IOException
import java.time.Duration
import java.time.Instant
import javax.annotation.ManagedBean
import org.jooq.DAO
import org.jooq.DSLContext
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.TaskScheduler

@JsonIgnoreProperties("organizations", "projects", "sites", "facilities")
data class PerSiteConfig(
    val devices: List<DevicesRow> = emptyList(),
    val storageLocations: List<StorageLocationsRow> = emptyList(),
)

@ManagedBean
class PerSiteConfigUpdater(
    private val devicesDao: DevicesDao,
    private val dslContext: DSLContext,
    private val storageLocationsDao: StorageLocationsDao,
    private val objectMapper: ObjectMapper,
    private val serverConfig: TerrawareServerConfig,
    private val taskScheduler: TaskScheduler
) {
  private val log = perClassLogger()

  @EventListener
  fun scheduleTasks(@Suppress("UNUSED_PARAMETER") event: ApplicationStartedEvent) {
    if (serverConfig.siteConfigRefreshSecs > 0) {
      taskScheduler.scheduleAtFixedRate(
          this::refreshConfig,
          Instant.EPOCH,
          Duration.ofSeconds(serverConfig.siteConfigRefreshSecs))
    } else {
      log.info("Disabling periodic refresh of per-site configuration")
    }
  }

  fun refreshConfig() {
    synchronized(this) {
      log.info("Refreshing per-site configuration")

      val config = fetchConfig()
      if (config != null) {
        updateDatabase(config)
      }
    }
  }

  fun fetchConfig(): PerSiteConfig? {
    return try {
      serverConfig.siteConfigUrl.inputStream.use { stream ->
        // BACKWARD COMPATIBILITY: Allow old-style config files that use "site module" instead of
        // "facility". Remove this once configs are updated in S3.
        val originalContents = stream.readAllBytes().decodeToString()
        val contents =
            originalContents
                .replace("\"siteModules\"", "\"facilities\"")
                .replace("\"siteModuleId\"", "\"facilityId\"")
        if (contents != originalContents) {
          log.warn("Converted legacy per-site config. Please replace 'siteModule' with 'facility'.")
        }

        return objectMapper.readValue<PerSiteConfig>(contents)
      }
    } catch (e: IOException) {
      log.info("Failed to fetch per-site configuration: ${e.message}")
      null
    }
  }

  fun updateDatabase(perSiteConfig: PerSiteConfig) {
    // Any entries that aren't explicitly marked as disabled in the config should be enabled.
    perSiteConfig.devices.forEach { it.enabled = it.enabled ?: true }
    perSiteConfig.storageLocations.forEach { it.enabled = it.enabled ?: true }

    // Need to insert and delete IDs in the right order because there are foreign key relationships.
    insertAndUpdate(perSiteConfig.devices, devicesDao)
    insertAndUpdate(perSiteConfig.storageLocations, storageLocationsDao)

    delete(perSiteConfig.devices, devicesDao) { it.enabled = false }
    delete(perSiteConfig.storageLocations, storageLocationsDao) { it.enabled = false }
  }

  private fun <T, I : Any> insertAndUpdate(desired: List<T>, dao: DAO<*, T, I>) {
    val existingIds = dao.findAll().mapNotNull { dao.getId(it) }.toSet()

    dao.update(desired.filter { dao.getId(it) in existingIds })
    dao.insert(desired.filter { dao.getId(it) !in existingIds })
  }

  private fun <T, I : Any> delete(desired: List<T>, dao: DAO<*, T, I>, disable: (T) -> Unit) {
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
