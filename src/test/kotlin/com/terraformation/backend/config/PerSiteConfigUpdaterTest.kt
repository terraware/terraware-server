package com.terraformation.backend.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.StorageCondition
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.tables.daos.AccessionsDao
import com.terraformation.backend.db.tables.daos.DevicesDao
import com.terraformation.backend.db.tables.daos.FacilitiesDao
import com.terraformation.backend.db.tables.daos.OrganizationsDao
import com.terraformation.backend.db.tables.daos.ProjectsDao
import com.terraformation.backend.db.tables.daos.SitesDao
import com.terraformation.backend.db.tables.daos.StorageLocationsDao
import com.terraformation.backend.db.tables.daos.TimeseriesDao
import com.terraformation.backend.db.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tables.pojos.DevicesRow
import com.terraformation.backend.db.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.tables.pojos.OrganizationsRow
import com.terraformation.backend.db.tables.pojos.ProjectsRow
import com.terraformation.backend.db.tables.pojos.SitesRow
import com.terraformation.backend.db.tables.pojos.StorageLocationsRow
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

  private lateinit var accessionsDao: AccessionsDao
  private lateinit var devicesDao: DevicesDao
  private lateinit var organizationsDao: OrganizationsDao
  private lateinit var projectsDao: ProjectsDao
  private lateinit var sitesDao: SitesDao
  private lateinit var facilitiesDao: FacilitiesDao
  private lateinit var storageLocationsDao: StorageLocationsDao
  private lateinit var timeseriesDao: TimeseriesDao
  private lateinit var updater: PerSiteConfigUpdater

  private val databaseBootstrapper: DatabaseBootstrapper = mockk()
  private val serverConfig: TerrawareServerConfig = mockk()
  private val taskScheduler: TaskScheduler = mockk()

  @BeforeEach
  fun setup() {
    val config = dslContext.configuration()

    accessionsDao = AccessionsDao(config)
    devicesDao = DevicesDao(config)
    organizationsDao = OrganizationsDao(config)
    projectsDao = ProjectsDao(config)
    sitesDao = SitesDao(config)
    facilitiesDao = FacilitiesDao(config)
    storageLocationsDao = StorageLocationsDao(config)
    timeseriesDao = TimeseriesDao(config)

    updater =
        PerSiteConfigUpdater(
            databaseBootstrapper,
            devicesDao,
            dslContext,
            storageLocationsDao,
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
    val emptyConfig = PerSiteConfig(emptyList(), emptyList())

    updater.updateDatabase(simpleConfig())
    updater.updateDatabase(emptyConfig)

    assertConfigInDatabase(emptyConfig)
  }

  @Test
  fun `parent rows are marked disabled if still referenced`() {
    val initial = simpleConfig()
    val emptyConfig = PerSiteConfig(emptyList(), emptyList())

    updater.updateDatabase(initial)

    // Add an accession that refers to the storage location, which refers to the site module, which
    // refers to the site, which refers to the organization.
    accessionsDao.insert(
        AccessionsRow(
            createdTime = Instant.EPOCH,
            number = "1",
            facilityId = FacilityId(3),
            stateId = AccessionState.Pending,
            storageLocationId = StorageLocationId(5)))

    val expected =
        PerSiteConfig(
            devices = emptyList(),
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
  fun `config files using site module instead of facility are converted`() {
    every { serverConfig.siteConfigUrl } returns
        resourceLoader.getResource("classpath:config/dev-sitemodule.json")

    assertNotNull(updater.fetchConfig())
  }

  @Test
  fun `failure to fetch config is handled`() {
    every { serverConfig.siteConfigUrl } returns
        resourceLoader.getResource("http://localhost:1/foo")

    assertNull(updater.fetchConfig())
  }

  private fun simpleConfig(): PerSiteConfig {
    val organization =
        OrganizationsRow(
            id = OrganizationId(1),
            name = "test",
            createdTime = Instant.EPOCH,
            modifiedTime = Instant.EPOCH)
    val project =
        ProjectsRow(
            id = ProjectId(2),
            organizationId = organization.id,
            name = "testProject",
            createdTime = Instant.EPOCH,
            modifiedTime = Instant.EPOCH,
        )
    val site =
        SitesRow(
            SiteId(2),
            "testSite",
            BigDecimal("1.0000000"),
            BigDecimal("2.0000000"),
            enabled = true,
            projectId = project.id)
    val facility =
        FacilitiesRow(FacilityId(3), site.id, FacilityType.SeedBank, "testModule", enabled = true)

    organizationsDao.insert(organization)
    projectsDao.insert(project)
    sitesDao.insert(site)
    facilitiesDao.insert(facility)

    val device =
        DevicesRow(
            DeviceId(4),
            facility.id,
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
        StorageLocationsRow(
            StorageLocationId(5), facility.id, "location", StorageCondition.Freezer, true)

    return PerSiteConfig(
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
    assertRows(expected.devices, devicesDao)
    assertRows(expected.storageLocations, storageLocationsDao)
  }

  private fun <T> assertRows(expected: Collection<T>, dao: DAO<*, T, *>) {
    assertEquals(expected.toSet(), dao.findAll().toSet())
  }
}
