package com.terraformation.backend.customer

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.SiteStore
import com.terraformation.backend.customer.model.SiteModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.LayerType
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.pojos.LayersRow
import com.terraformation.backend.db.tables.pojos.SitesRow
import com.terraformation.backend.gis.db.LayerStore
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class SiteServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  override val sequencesToReset: List<String> = listOf("layers_id_seq", "site_id_seq")

  private val clock: Clock = mockk()

  private lateinit var service: SiteService

  private val projectId = ProjectId(2)

  @BeforeEach
  fun setUp() {
    service =
        SiteService(
            dslContext,
            SiteStore(clock, dslContext, ParentStore(dslContext), sitesDao),
            LayerStore(clock, dslContext))

    every { clock.instant() } returns Instant.EPOCH
    every { user.canCreateLayer(any()) } returns true
    every { user.canCreateSite(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadSite(any()) } returns true

    insertUser()
    insertOrganization(1, "dev")
    insertProject(projectId, 1, "project")
  }

  @Test
  fun `create creates site and layers`() {
    val newRow = SitesRow(projectId = projectId, name = "Test Site")
    val layerTypes = setOf(LayerType.AerialPhotos, LayerType.PlantsPlanted)
    val siteId = SiteId(1)

    val expectedSiteModel =
        SiteModel(
            createdTime = clock.instant(),
            description = null,
            id = siteId,
            location = null,
            modifiedTime = clock.instant(),
            name = "Test Site",
            projectId = projectId,
        )

    val actualSiteModel = service.create(newRow, layerTypes)
    assertEquals(expectedSiteModel, actualSiteModel)

    val expectedSites =
        listOf(
            newRow.copy(
                createdBy = user.userId,
                createdTime = clock.instant(),
                enabled = true,
                id = siteId,
                modifiedBy = user.userId,
                modifiedTime = clock.instant(),
            ))

    val actualSites = sitesDao.fetchByProjectId(projectId)
    assertEquals(expectedSites, actualSites)

    val expectedLayers =
        setOf(
            LayersRow(
                createdBy = user.userId,
                createdTime = clock.instant(),
                deleted = false,
                hidden = false,
                layerTypeId = LayerType.AerialPhotos,
                modifiedBy = user.userId,
                modifiedTime = clock.instant(),
                proposed = false,
                siteId = siteId,
            ),
            LayersRow(
                createdBy = user.userId,
                createdTime = clock.instant(),
                deleted = false,
                hidden = false,
                layerTypeId = LayerType.PlantsPlanted,
                modifiedBy = user.userId,
                modifiedTime = clock.instant(),
                proposed = false,
                siteId = siteId,
            ),
        )

    val actualLayers = layersDao.fetchBySiteId(siteId).map { it.copy(id = null) }.toSet()
    assertEquals(expectedLayers, actualLayers, "Should have created layers")
  }

  @Test
  fun `create throws exception and does not insert site if user has no permission to create layers`() {
    every { user.canCreateLayer(any()) } returns false

    assertThrows<AccessDeniedException> {
      service.create(
          SitesRow(projectId = projectId, name = "Test Site"), setOf(LayerType.PlantsPlanted))
    }

    val sites = sitesDao.fetchByProjectId(projectId)
    assertEquals(emptyList<SitesRow>(), sites, "Should not have created the site")
  }

  @Test
  fun `create throws exception if user has no permission to create sites`() {
    every { user.canCreateSite(any()) } returns false

    assertThrows<AccessDeniedException> {
      service.create(SitesRow(projectId = projectId, name = "Test Site"), emptySet())
    }
  }
}
