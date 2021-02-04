package com.terraformation.seedbank.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.terraformation.seedbank.db.DatabaseTest
import com.terraformation.seedbank.db.StorageCondition
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
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import org.jooq.DAO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader

internal class PerSiteConfigUpdaterTest : DatabaseTest() {
  @Autowired private lateinit var resourceLoader: ResourceLoader

  private lateinit var deviceDao: DeviceDao
  private lateinit var organizationDao: OrganizationDao
  private lateinit var siteDao: SiteDao
  private lateinit var siteModuleDao: SiteModuleDao
  private lateinit var storageLocationDao: StorageLocationDao
  private lateinit var updater: PerSiteConfigUpdater

  private val serverConfig: TerrawareServerConfig = mockk()

  @BeforeEach
  fun setup() {
    val config = dslContext.configuration()

    deviceDao = DeviceDao(config)
    organizationDao = OrganizationDao(config)
    siteDao = SiteDao(config)
    siteModuleDao = SiteModuleDao(config)
    storageLocationDao = StorageLocationDao(config)

    updater =
        PerSiteConfigUpdater(
            deviceDao,
            organizationDao,
            siteDao,
            siteModuleDao,
            storageLocationDao,
            ObjectMapper().registerKotlinModule(),
            serverConfig)

    deleteDemoData()
  }

  @Test
  fun `rows are inserted into clean database`() {
    val config = simpleConfig()

    updater.updateDatabase(config)

    assertConfigInDatabase(config)
  }

  @Test
  fun `existing rows are deleted`() {
    val emptyConfig = PerSiteConfig(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    updater.updateDatabase(simpleConfig())
    updater.updateDatabase(emptyConfig)

    assertConfigInDatabase(emptyConfig)
  }

  @Test
  fun `existing rows are updated`() {
    val initialConfig = simpleConfig()
    updater.updateDatabase(initialConfig)

    val newConfig =
        PerSiteConfig(
            devices = listOf(initialConfig.devices[0].copy(name = "new device")),
            organizations = listOf(initialConfig.organizations[0].copy(name = "new org")),
            sites = listOf(initialConfig.sites[0].copy(name = "new site")),
            siteModules = listOf(initialConfig.siteModules[0].copy(name = "new module")),
            storageLocations =
                listOf(initialConfig.storageLocations[0].copy(name = "new location")))

    updater.updateDatabase(newConfig)

    assertConfigInDatabase(newConfig)
  }

  @Test
  fun `can fetch config from classpath URL`() {
    every { serverConfig.siteConfigUrl } returns
        resourceLoader.getResource("classpath:config/dev.json")

    assertNotNull(updater.fetchConfig())
  }

  @Test
  fun `failure to fetch config is handled`() {
    every { serverConfig.siteConfigUrl } returns
        resourceLoader.getResource("http://localhost:1/foo")

    assertNull(updater.fetchConfig())
  }

  private fun simpleConfig(): PerSiteConfig {
    val organization = Organization(1, "test")
    val site =
        Site(2, organization.id, "testSite", BigDecimal("1.0000000"), BigDecimal("2.0000000"))
    val siteModule = SiteModule(3, site.id, 1, "testModule")
    val device = Device(4, siteModule.id, 1, "testDevice")
    val storageLocation = StorageLocation(5, siteModule.id, "location", StorageCondition.Freezer)

    return PerSiteConfig(
        organizations = listOf(organization),
        sites = listOf(site),
        siteModules = listOf(siteModule),
        devices = listOf(device),
        storageLocations = listOf(storageLocation),
    )
  }

  private fun assertConfigInDatabase(expected: PerSiteConfig) {
    assertRows(expected.organizations, organizationDao)
    assertRows(expected.sites, siteDao)
    assertRows(expected.siteModules, siteModuleDao)
    assertRows(expected.devices, deviceDao)
    assertRows(expected.storageLocations, storageLocationDao)
  }

  private fun <T> assertRows(expected: Collection<T>, dao: DAO<*, T, *>) {
    assertEquals(expected.toSet(), dao.findAll().toSet())
  }
}
