package com.terraformation.backend.customer.api

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.api.NoOrganizationException
import com.terraformation.backend.api.NotFoundException
import com.terraformation.backend.api.WrongOrganizationException
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.daos.ProjectsDao
import com.terraformation.backend.db.tables.daos.SitesDao
import com.terraformation.backend.db.tables.pojos.ProjectsRow
import com.terraformation.backend.db.tables.pojos.SitesRow
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SiteControllerTest : RunsAsUser {
  override val user = mockk<UserModel>()
  private val projectsDao = mockk<ProjectsDao>()
  private val siteDao = mockk<SitesDao>()
  private val siteController = SiteController(projectsDao, siteDao)

  private val organizationId = OrganizationId(1)
  private val projectId = ProjectId(2)
  private val siteId = SiteId(123)

  @BeforeEach
  fun defaultToAuthenticatedUser() {
    every { user.defaultOrganizationId() } returns organizationId
  }

  @Nested
  @DisplayName("GET /api/v1/site")
  inner class ListSites {
    @Test
    fun `rejects requests from clients without organizations`() {
      every { user.defaultOrganizationId() } returns null

      assertThrows<NoOrganizationException> { siteController.listSites() }
    }

    @Test
    fun `returns object with empty list if no sites defined`() {
      every { projectsDao.fetchByOrganizationId(organizationId) } returns
          listOf(ProjectsRow(id = projectId))
      every { siteDao.fetchByProjectId(projectId) } returns emptyList()

      assertEquals(ListSitesResponse(emptyList()), siteController.listSites())
    }

    @Test
    fun `returns list of sites`() {
      every { projectsDao.fetchByOrganizationId(organizationId) } returns
          listOf(ProjectsRow(id = projectId))
      every { siteDao.fetchByProjectId(projectId) } returns
          listOf(
              SitesRow(id = SiteId(1), name = "First Site"),
              SitesRow(id = SiteId(2), name = "Second Site"))

      val expected =
          ListSitesResponse(
              listOf(
                  ListSitesElement(SiteId(1), "First Site"),
                  ListSitesElement(SiteId(2), "Second Site")))

      assertEquals(expected, siteController.listSites())
    }
  }

  @Nested
  @DisplayName("GET /api/v1/site/{siteId}")
  inner class GetSite {
    private val timezone = "US/Pacific"
    private val locale = "en-US"
    private val longitudeString = "123.456"
    private val latitudeString = "91.351"
    private val longitude = BigDecimal(longitudeString)
    private val latitude = BigDecimal(latitudeString)
    private val site =
        SitesRow(
            id = siteId,
            name = "site",
            projectId = projectId,
            latitude = latitude,
            longitude = longitude,
            locale = locale,
            timezone = timezone)
    private val project =
        ProjectsRow(id = projectId, name = "project", organizationId = organizationId)

    @Test
    fun `accepts requests from super admin clients without organizations`() {
      every { user.canReadSite(siteId) } returns true
      every { projectsDao.fetchOneById(projectId) } returns project
      every { siteDao.fetchOneById(siteId) } returns site

      assertDoesNotThrow { siteController.getSite(siteId) }
    }

    @Test
    fun `rejects requests for nonexistent sites`() {
      every { user.canReadSite(siteId) } returns true
      every { siteDao.fetchOneById(siteId) } returns null

      assertThrows<NotFoundException> { siteController.getSite(siteId) }
    }

    @Test
    fun `rejects requests not allowed by permission manager`() {
      every { user.canReadSite(siteId) } returns false
      every { projectsDao.fetchOneById(projectId) } returns
          ProjectsRow(id = projectId, organizationId = OrganizationId(0))
      every { siteDao.fetchOneById(siteId) } returns
          SitesRow(id = siteId, projectId = projectId, name = "site")

      assertThrows<WrongOrganizationException> { siteController.getSite(siteId) }
    }

    @Test
    fun `returns site data if permitted`() {
      every { user.canReadSite(siteId) } returns true
      every { projectsDao.fetchOneById(projectId) } returns project
      every { siteDao.fetchOneById(siteId) } returns site

      val expected =
          GetSiteResponse(
              id = siteId,
              name = "site",
              latitude = latitudeString,
              longitude = longitudeString,
              locale = locale,
              timezone = timezone)

      assertEquals(expected, siteController.getSite(siteId))
    }
  }
}
