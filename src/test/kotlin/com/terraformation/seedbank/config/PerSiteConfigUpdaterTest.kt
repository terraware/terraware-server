package com.terraformation.seedbank.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.DatabaseTest
import com.terraformation.seedbank.db.StorageCondition
import com.terraformation.seedbank.db.tables.daos.AccessionDao
import com.terraformation.seedbank.db.tables.daos.DeviceDao
import com.terraformation.seedbank.db.tables.daos.OrganizationDao
import com.terraformation.seedbank.db.tables.daos.SiteDao
import com.terraformation.seedbank.db.tables.daos.SiteModuleDao
import com.terraformation.seedbank.db.tables.daos.StorageLocationDao
import com.terraformation.seedbank.db.tables.daos.TimeseriesDao
import com.terraformation.seedbank.db.tables.pojos.Accession
import com.terraformation.seedbank.db.tables.pojos.Device
import com.terraformation.seedbank.db.tables.pojos.Organization
import com.terraformation.seedbank.db.tables.pojos.Site
import com.terraformation.seedbank.db.tables.pojos.SiteModule
import com.terraformation.seedbank.db.tables.pojos.StorageLocation
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import org.jooq.DAO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.core.io.ResourceLoader
import org.springframework.scheduling.TaskScheduler

internal class PerSiteConfigUpdaterTest : DatabaseTest() {
  @Autowired private lateinit var resourceLoader: ResourceLoader

  private lateinit var accessionDao: AccessionDao
  private lateinit var deviceDao: DeviceDao
  private lateinit var organizationDao: OrganizationDao
  private lateinit var siteDao: SiteDao
  private lateinit var siteModuleDao: SiteModuleDao
  private lateinit var storageLocationDao: StorageLocationDao
  private lateinit var timeseriesDao: TimeseriesDao
  private lateinit var updater: PerSiteConfigUpdater

  private val databaseBootstrapper: DatabaseBootstrapper = mockk()
  private val serverConfig: TerrawareServerConfig = mockk()
  private val taskScheduler: TaskScheduler = mockk()

  @BeforeEach
  fun setup() {
    val config = dslContext.configuration()

    accessionDao = AccessionDao(config)
    deviceDao = DeviceDao(config)
    organizationDao = OrganizationDao(config)
    siteDao = SiteDao(config)
    siteModuleDao = SiteModuleDao(config)
    storageLocationDao = StorageLocationDao(config)
    timeseriesDao = TimeseriesDao(config)

    updater =
        PerSiteConfigUpdater(
            databaseBootstrapper,
            deviceDao,
            dslContext,
            organizationDao,
            siteDao,
            siteModuleDao,
            storageLocationDao,
            ObjectMapper().registerKotlinModule(),
            serverConfig,
            taskScheduler)

    justRun { databaseBootstrapper.updateApiKey() }
  }

  @Test
  fun `rows are inserted into clean database`() {
    val config = simpleConfig()

    updater.updateDatabase(config)

    assertConfigInDatabase(config)
    verify { databaseBootstrapper.updateApiKey() }
  }

  @Test
  fun `existing rows are deleted`() {
    val emptyConfig = PerSiteConfig(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    updater.updateDatabase(simpleConfig())
    updater.updateDatabase(emptyConfig)

    assertConfigInDatabase(emptyConfig)
  }

  @Test
  fun `parent rows are marked disabled if still referenced`() {
    val initial = simpleConfig()
    val emptyConfig = PerSiteConfig(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    updater.updateDatabase(initial)

    // Add an accession that refers to the storage location, which refers to the site module, which
    // refers to the site, which refers to the organization.
    accessionDao.insert(
        Accession(
            createdTime = Instant.EPOCH,
            number = "1",
            siteModuleId = 3,
            stateId = AccessionState.Pending,
            storageLocationId = 5))

    val expected =
        PerSiteConfig(
            devices = emptyList(),
            organizations = initial.organizations.map { it.copy(enabled = false) },
            sites = initial.sites.map { it.copy(enabled = false) },
            siteModules = initial.siteModules.map { it.copy(enabled = false) },
            storageLocations = initial.storageLocations.map { it.copy(enabled = false) })

    updater.updateDatabase(emptyConfig)
    assertConfigInDatabase(expected)
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
    val organization = Organization(1, "test", true)
    val site =
        Site(
            2,
            organization.id,
            "testSite",
            BigDecimal("1.0000000"),
            BigDecimal("2.0000000"),
            enabled = true)
    val siteModule = SiteModule(3, site.id, 1, "testModule", enabled = true)
    val device =
        Device(
            4,
            siteModule.id,
            "testDevice",
            1,
            "type",
            "make",
            "model",
            "protocol",
            "address",
            12345,
            "settings",
            5432,
            true)
    val storageLocation =
        StorageLocation(5, siteModule.id, "location", StorageCondition.Freezer, true)

    return PerSiteConfig(
        organizations = listOf(organization),
        sites = listOf(site),
        siteModules = listOf(siteModule),
        devices = listOf(device),
        storageLocations = listOf(storageLocation),
    )
  }

  @Test
  fun `periodic refresh is scheduled based on configuration`() {
    every { taskScheduler.scheduleAtFixedRate(any(), any(), any<Duration>()) } returns mockk()
    every { serverConfig.siteConfigRefreshSecs } returns 10

    updater.scheduleTasks(ApplicationStartedEvent(SpringApplication(), emptyArray(), null))
    verify { taskScheduler.scheduleAtFixedRate(any(), Instant.EPOCH, Duration.ofSeconds(10)) }
  }

  @Test
  fun `periodic refresh is not scheduled if interval is 0`() {
    every { serverConfig.siteConfigRefreshSecs } returns 0

    // If this calls the scheduler, the scheduler mock will throw an exception since we haven't
    // set behavior of any method calls.
    updater.scheduleTasks(ApplicationStartedEvent(SpringApplication(), emptyArray(), null))
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
