package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.mercatorPoint
import com.terraformation.backend.db.tables.daos.SitesDao
import com.terraformation.backend.db.tables.pojos.SitesRow
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class SiteStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockk()

  private val clock: Clock = mockk()
  private lateinit var parentStore: ParentStore
  private lateinit var sitesDao: SitesDao
  private lateinit var store: SiteStore

  private val organizationId = OrganizationId(1)
  private val projectId = ProjectId(2)
  private val siteId = SiteId(10)

  @BeforeEach
  fun setUp() {
    parentStore = ParentStore(dslContext)
    sitesDao = SitesDao(dslContext.configuration())
    store = SiteStore(clock, dslContext, parentStore, sitesDao)

    every { clock.instant() } returns Instant.EPOCH
    every { user.canReadProject(any()) } returns true
    every { user.canCreateSite(any()) } returns true
    every { user.canReadSite(any()) } returns true
    every { user.canUpdateSite(any()) } returns true
    every { user.canDeleteSite(any()) } returns true

    insertSiteData()
  }

  @Test
  fun `update can modify data without moving to new project`() {
    every { clock.instant() } returns Instant.EPOCH.plusSeconds(1000)

    val edits =
        SitesRow(
            description = "New description",
            enabled = true,
            id = siteId,
            locale = "New locale",
            location = mercatorPoint(-10.0, -20.0, 30.0),
            name = "New name",
            timezone = "US/Hawaii",
        )

    store.update(edits)

    val expected =
        edits.copy(
            projectId = projectId, createdTime = Instant.EPOCH, modifiedTime = clock.instant())
    val actual = sitesDao.fetchOneById(siteId)!!

    assertEquals(expected, actual)
  }

  @Test
  fun `update can move site between projects`() {
    val newProjectId = ProjectId(3)

    insertProject(newProjectId, organizationId)

    val rowWithNewProject = sitesDao.fetchOneById(siteId)!!.copy(projectId = newProjectId)

    store.update(rowWithNewProject)
    val updatedRow = sitesDao.fetchOneById(siteId)!!

    assertEquals(newProjectId, updatedRow.projectId)
  }

  @Test
  fun `update throws exception if asked to move site between organizations`() {
    val newOrganizationId = OrganizationId(2)
    val newProjectId = ProjectId(3)

    insertOrganization(newOrganizationId)
    insertProject(newProjectId, newOrganizationId)

    val rowWithNewProject = sitesDao.fetchOneById(siteId)!!.copy(projectId = newProjectId)

    assertThrows<IllegalArgumentException> { store.update(rowWithNewProject) }
  }

  @Test
  fun `update throws exception if user has no permission to update site`() {
    every { user.canUpdateSite(siteId) } returns false

    val rowWithNewName = sitesDao.fetchOneById(siteId)!!.copy(name = "New Name")

    assertThrows<AccessDeniedException> { store.update(rowWithNewName) }
  }

  @Test
  fun `update throws exception if user has no permission to delete site from current project`() {
    val newProjectId = ProjectId(3)
    insertProject(newProjectId, organizationId)

    every { user.canDeleteSite(siteId) } returns false

    val rowWithNewProject = sitesDao.fetchOneById(siteId)!!.copy(projectId = newProjectId)

    assertThrows<AccessDeniedException> { store.update(rowWithNewProject) }
  }

  @Test
  fun `update throws exception if user has no permission to create site in target project`() {
    val newProjectId = ProjectId(3)
    insertProject(newProjectId, organizationId)

    every { user.canCreateSite(newProjectId) } returns false

    val rowWithNewProject = sitesDao.fetchOneById(siteId)!!.copy(projectId = newProjectId)

    assertThrows<AccessDeniedException> { store.update(rowWithNewProject) }
  }
}
