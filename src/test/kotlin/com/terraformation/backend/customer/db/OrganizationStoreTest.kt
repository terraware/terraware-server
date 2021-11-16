package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.SiteModel
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.newPoint
import com.terraformation.backend.db.tables.daos.OrganizationsDao
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class OrganizationStoreTest : DatabaseTest(), RunsAsUser {
  override val user: UserModel = mockk()

  private val clock: Clock = mockk()
  private lateinit var organizationsDao: OrganizationsDao
  private lateinit var store: OrganizationStore

  private val organizationId = OrganizationId(1)
  private val projectId = ProjectId(10)
  private val siteId = SiteId(100)
  private val facilityId = FacilityId(1000)

  // This gets converted to Mercator in the DB; using smaller values causes floating-point
  // inaccuracies that make assertEquals() fail. The values here work on x86_64; keep an eye on
  // whether they work consistently across platforms.
  private val location = newPoint(150.0, 80.0, 30.0, SRID.LONG_LAT)

  private val facilityModel =
      FacilityModel(
          id = facilityId,
          siteId = siteId,
          name = "Facility $facilityId",
          type = FacilityType.SeedBank)
  private val siteModel =
      SiteModel(
          id = siteId,
          projectId = projectId,
          name = "Site $siteId",
          location = location,
          createdTime = Instant.EPOCH,
          modifiedTime = Instant.EPOCH,
          facilities = listOf(facilityModel))
  private val projectModel =
      ProjectModel(
          id = projectId,
          organizationId = organizationId,
          name = "Project $projectId",
          sites = listOf(siteModel))
  private val organizationModel =
      OrganizationModel(
          id = organizationId,
          name = "Organization $organizationId",
          projects = listOf(projectModel))

  @BeforeEach
  fun setUp() {
    val jooqConfig = dslContext.configuration()

    organizationsDao = OrganizationsDao(jooqConfig)
    store = OrganizationStore(clock, dslContext, organizationsDao)

    every { user.canReadOrganization(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadSite(any()) } returns true
    every { user.canReadFacility(any()) } returns true

    every { user.organizationRoles } returns mapOf(organizationId to Role.OWNER)
    every { user.projectRoles } returns mapOf(projectId to Role.OWNER)
    every { user.userId } returns UserId(1)

    insertOrganization(organizationId)
    insertProject(projectId)
    insertSite(siteId, location = location)
    insertFacility(facilityId)
  }

  @Test
  fun `fetchAll honors fetch depth`() {

    assertEquals(
        listOf(organizationModel),
        store.fetchAll(OrganizationStore.FetchDepth.Facility),
        "Fetch depth = Facility")

    assertEquals(
        listOf(
            organizationModel.copy(
                projects =
                    listOf(projectModel.copy(sites = listOf(siteModel.copy(facilities = null)))))),
        store.fetchAll(OrganizationStore.FetchDepth.Site),
        "Fetch depth = Site")

    assertEquals(
        listOf(organizationModel.copy(projects = listOf(projectModel.copy(sites = null)))),
        store.fetchAll(OrganizationStore.FetchDepth.Project),
        "Fetch depth = Project")

    assertEquals(
        listOf(organizationModel.copy(projects = null)),
        store.fetchAll(OrganizationStore.FetchDepth.Organization),
        "Fetch depth = Organization")
  }

  @Test
  fun `fetchById honors fetch depth`() {
    assertEquals(
        organizationModel,
        store.fetchById(organizationId, OrganizationStore.FetchDepth.Facility),
        "Fetch depth = Facility")

    assertEquals(
        organizationModel.copy(
            projects =
                listOf(projectModel.copy(sites = listOf(siteModel.copy(facilities = null))))),
        store.fetchById(organizationId, OrganizationStore.FetchDepth.Site),
        "Fetch depth = Site")

    assertEquals(
        organizationModel.copy(projects = listOf(projectModel.copy(sites = null))),
        store.fetchById(organizationId, OrganizationStore.FetchDepth.Project),
        "Fetch depth = Project")

    assertEquals(
        organizationModel.copy(projects = null),
        store.fetchById(organizationId, OrganizationStore.FetchDepth.Organization),
        "Fetch depth = Organization")
  }

  @Test
  fun `fetchById requires user to be in the organization`() {
    every { user.organizationRoles } returns emptyMap()

    assertNull(store.fetchById(organizationId))
  }

  @Test
  fun `fetchAll excludes organizations the user is not in`() {
    every { user.organizationRoles } returns emptyMap()

    assertEquals(emptyList<OrganizationModel>(), store.fetchAll())
  }

  @Test
  fun `fetchById excludes projects the user is not in`() {
    every { user.projectRoles } returns emptyMap()

    val expected = organizationModel.copy(projects = emptyList())

    val actual = store.fetchById(organizationId, OrganizationStore.FetchDepth.Project)
    assertEquals(expected, actual)
  }

  @Test
  fun `fetchAll excludes projects the user is not in`() {
    every { user.projectRoles } returns emptyMap()

    val expected = listOf(organizationModel.copy(projects = emptyList()))

    val actual = store.fetchAll(OrganizationStore.FetchDepth.Project)
    assertEquals(expected, actual)
  }
}
