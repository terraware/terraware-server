package com.terraformation.backend.api

import com.terraformation.backend.auth.AnonymousClient
import com.terraformation.backend.auth.ControllerClientIdentity
import com.terraformation.backend.auth.LoggedInUserIdentity
import com.terraformation.backend.auth.Role
import com.terraformation.backend.db.tables.daos.SitesDao
import com.terraformation.backend.db.tables.pojos.SitesRow
import com.terraformation.backend.services.emptyEnumSet
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.util.EnumSet
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SiteControllerTest {
  private val siteDao = mockk<SitesDao>()
  private val siteController = SiteController(siteDao)

  private val organizationId = 1L
  private val siteId = 123L

  private val authentication = ControllerClientIdentity(organizationId)
  private val superAdminAuthentication =
      LoggedInUserIdentity("superadmin", null, EnumSet.of(Role.SUPER_ADMIN))

  @Nested
  @DisplayName("GET /api/v1/site")
  inner class ListSites {
    @Test
    fun `rejects requests from clients without organizations`() {
      assertThrows(NoOrganizationException::class.java) {
        siteController.listSites(LoggedInUserIdentity("test", null, emptyEnumSet()))
      }
    }

    @Test
    fun `returns object with empty list if no sites defined`() {
      every { siteDao.fetchByOrganizationId(organizationId) } returns emptyList()
      assertEquals(ListSitesResponse(emptyList()), siteController.listSites(authentication))
    }

    @Test
    fun `returns list of sites`() {
      every { siteDao.fetchByOrganizationId(organizationId) } returns
          listOf(SitesRow(id = 1, name = "First Site"), SitesRow(id = 2, name = "Second Site"))

      val expected =
          ListSitesResponse(
              listOf(ListSitesElement(1, "First Site"), ListSitesElement(2, "Second Site")))
      assertEquals(expected, siteController.listSites(authentication))
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
            organizationId = organizationId,
            name = "site",
            latitude = latitude,
            longitude = longitude,
            locale = locale,
            timezone = timezone)

    @Test
    fun `rejects requests from regular clients without organizations`() {
      assertThrows(NoOrganizationException::class.java) {
        siteController.getSite(AnonymousClient, 1)
      }
    }

    @Test
    fun `accepts requests from super admin clients without organizations`() {
      every { siteDao.fetchOneById(siteId) } returns site
      assertDoesNotThrow { siteController.getSite(superAdminAuthentication, siteId) }
    }

    @Test
    fun `rejects requests for nonexistent sites`() {
      every { siteDao.fetchOneById(siteId) } returns null
      assertThrows(NotFoundException::class.java) { siteController.getSite(authentication, siteId) }
    }

    @Test
    fun `rejects requests for sites owned by another organization`() {
      every { siteDao.fetchOneById(siteId) } returns
          SitesRow(id = siteId, organizationId = 0, name = "site")
      assertThrows(WrongOrganizationException::class.java) {
        siteController.getSite(authentication, siteId)
      }
    }

    @Test
    fun `returns site data if permitted`() {
      every { siteDao.fetchOneById(siteId) } returns site

      val expected =
          GetSiteResponse(
              id = siteId,
              name = "site",
              latitude = latitudeString,
              longitude = longitudeString,
              locale = locale,
              timezone = timezone)
      assertEquals(expected, siteController.getSite(authentication, siteId))
    }
  }
}
