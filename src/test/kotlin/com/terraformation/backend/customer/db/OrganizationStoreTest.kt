package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.OrganizationUserModel
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.SiteModel
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectStatus
import com.terraformation.backend.db.ProjectType
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.newPoint
import com.terraformation.backend.db.tables.daos.OrganizationsDao
import com.terraformation.backend.db.tables.daos.UsersDao
import com.terraformation.backend.db.tables.pojos.OrganizationsRow
import com.terraformation.backend.db.tables.references.PROJECT_USERS
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class OrganizationStoreTest : DatabaseTest(), RunsAsUser {
  override val user: UserModel = mockk()
  override val sequencesToReset: List<String> = listOf("organizations_id_seq")

  private val clock: Clock = mockk()
  private lateinit var organizationsDao: OrganizationsDao
  private lateinit var permissionStore: PermissionStore
  private lateinit var usersDao: UsersDao
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
          description = "Description $siteId",
          location = location,
          createdTime = Instant.EPOCH,
          modifiedTime = Instant.EPOCH,
          facilities = listOf(facilityModel))
  private val projectModel =
      ProjectModel(
          description = "Project description $projectId",
          id = projectId,
          organizationId = organizationId,
          name = "Project $projectId",
          sites = listOf(siteModel),
          startDate = LocalDate.EPOCH.plusDays(projectId.value),
          status = ProjectStatus.Planting,
          types = setOf(ProjectType.Agroforestry, ProjectType.SustainableTimber))
  private val organizationModel =
      OrganizationModel(
          id = organizationId,
          countryCode = "US",
          countrySubdivisionCode = "US-HI",
          name = "Organization $organizationId",
          projects = listOf(projectModel))

  @BeforeEach
  fun setUp() {
    val jooqConfig = dslContext.configuration()

    organizationsDao = OrganizationsDao(jooqConfig)
    permissionStore = PermissionStore(dslContext)
    usersDao = UsersDao(jooqConfig)
    store = OrganizationStore(clock, dslContext, organizationsDao)

    every { clock.instant() } returns Instant.EPOCH

    every { user.canReadOrganization(any()) } returns true
    every { user.canUpdateOrganization(any()) } returns true
    every { user.canAddOrganizationUser(any()) } returns true
    every { user.canListOrganizationUsers(any()) } returns true
    every { user.canRemoveOrganizationUser(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadSite(any()) } returns true
    every { user.canReadFacility(any()) } returns true

    every { user.organizationRoles } returns mapOf(organizationId to Role.OWNER)
    every { user.projectRoles } returns mapOf(projectId to Role.OWNER)
    every { user.userId } returns UserId(1)

    assertEquals(
        organizationId,
        insertOrganization(
            name = organizationModel.name,
            countryCode = organizationModel.countryCode,
            countrySubdivisionCode = organizationModel.countrySubdivisionCode))
    insertProject(
        projectId,
        description = projectModel.description,
        startDate = projectModel.startDate,
        status = projectModel.status,
        types = projectModel.types)
    insertSite(siteId, description = siteModel.description, location = location)
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

  @Test
  fun `createWithAdmin populates organization details`() {
    insertUser()

    val row =
        OrganizationsRow(
            countryCode = "US",
            countrySubdivisionCode = "US-HI",
            description = "Test description",
            name = "Test Org",
        )
    val createdModel = store.createWithAdmin(row)

    val expected =
        row.copy(
            createdTime = clock.instant(), id = OrganizationId(2), modifiedTime = clock.instant())
    val actual = organizationsDao.fetchOneById(createdModel.id)!!

    assertEquals(expected, actual)
  }

  @Test
  fun `createWithAdmin folds country and subdivision codes to all-caps`() {
    insertUser()

    val createdModel =
        store.createWithAdmin(
            OrganizationsRow(
                countryCode = "us",
                countrySubdivisionCode = "us-hi",
                name = "Test Org",
            ))
    val row = organizationsDao.fetchOneById(createdModel.id)!!

    assertEquals("US", row.countryCode, "Country code")
    assertEquals("US-HI", row.countrySubdivisionCode, "Subdivision code")
  }

  @Test
  fun `createWithAdmin rejects invalid country codes`() {
    assertThrows<IllegalArgumentException> {
      store.createWithAdmin(OrganizationsRow(countryCode = "XX", name = "Test Org"))
    }
  }

  @Test
  fun `createWithAdmin rejects invalid country subdivision codes`() {
    assertThrows<IllegalArgumentException> {
      store.createWithAdmin(
          OrganizationsRow(countryCode = "US", countrySubdivisionCode = "US-XX", name = "Test Org"))
    }
  }

  @Test
  fun `createWithAdmin rejects mismatched country and subdivision codes`() {
    assertThrows<IllegalArgumentException> {
      store.createWithAdmin(
          OrganizationsRow(countryCode = "GB", countrySubdivisionCode = "US-HI", name = "Test Org"))
    }
  }

  @Test
  fun `createWithAdmin adds current user as admin`() {
    insertUser()

    val createdModel = store.createWithAdmin(OrganizationsRow(name = "Test Org"))
    val roles = permissionStore.fetchOrganizationRoles(user.userId)

    assertEquals(mapOf(createdModel.id to Role.OWNER), roles)
  }

  @Test
  fun `update populates organization details`() {
    val newTime = clock.instant().plusSeconds(1000)
    every { clock.instant() } returns newTime

    val updates =
        OrganizationsRow(
            id = organizationId,
            name = "New Name",
            description = "New Description",
            countryCode = "ZA")
    val expected = updates.copy(createdTime = Instant.EPOCH, modifiedTime = newTime)

    store.update(updates)

    val actual = organizationsDao.fetchOneById(organizationId)!!

    assertEquals(expected, actual)
  }

  @Test
  fun `update throws exception if user has no permission to update organization`() {
    every { user.canUpdateOrganization(organizationId) } returns false

    assertThrows<AccessDeniedException> {
      store.update(OrganizationsRow(id = organizationId, name = "New Name"))
    }
  }

  @Test
  fun `update rejects invalid country codes`() {
    assertThrows<IllegalArgumentException> {
      store.update(OrganizationsRow(id = organizationId, name = "X", countryCode = "XX"))
    }
  }

  @Test
  fun `fetchUsers returns information about members`() {
    val otherProjectId = ProjectId(11)
    val expected =
        listOf(
            OrganizationUserModel(
                UserId(100),
                "user1@x.com",
                "First1",
                "Last1",
                UserType.Individual,
                clock.instant(),
                organizationId,
                Role.ADMIN,
                null,
                emptyList()),
            OrganizationUserModel(
                UserId(101),
                "user2@x.com",
                "First2",
                "Last2",
                UserType.Individual,
                clock.instant(),
                organizationId,
                Role.CONTRIBUTOR,
                null,
                listOf(projectId, otherProjectId)),
        )

    insertProject(otherProjectId)
    expected.forEach { configureUser(it) }

    val actual = store.fetchUsers(organizationId)

    assertEquals(expected, actual)
  }

  @Test
  fun `fetchUsers hides real name of user with pending invitation`() {
    val model =
        OrganizationUserModel(
            UserId(100),
            "x@y.com",
            "First",
            "Last",
            UserType.Individual,
            clock.instant(),
            organizationId,
            Role.CONTRIBUTOR,
            Instant.EPOCH,
            emptyList())
    configureUser(model)

    val expected = listOf(model.copy(firstName = null, lastName = null))
    val actual = store.fetchUsers(organizationId)

    assertEquals(expected, actual)
  }

  @Test
  fun `fetchUsers throws exception if member has no permission to list users`() {
    every { user.canListOrganizationUsers(organizationId) } returns false

    assertThrows<AccessDeniedException> { store.fetchUsers(organizationId) }
  }

  @Test
  fun `fetchUsers throws exception if member has no permission to see organization`() {
    every { user.canReadOrganization(organizationId) } returns false
    every { user.canListOrganizationUsers(organizationId) } returns false

    assertThrows<OrganizationNotFoundException> { store.fetchUsers(organizationId) }
  }

  @Test
  fun `fetchUsers only requires permission to list users, not to read other organization data`() {
    every { user.canReadOrganization(organizationId) } returns false

    val model =
        OrganizationUserModel(
            UserId(100),
            "x@y.com",
            "First",
            "Last",
            UserType.Individual,
            clock.instant(),
            organizationId,
            Role.CONTRIBUTOR,
            null,
            emptyList())
    configureUser(model)

    val expected = listOf(model)
    val actual = store.fetchUsers(organizationId)

    assertEquals(expected, actual)
  }

  @Test
  fun `removeUser throws exception if no permission to remove users`() {
    every { user.canRemoveOrganizationUser(organizationId) } returns false

    assertThrows<AccessDeniedException> { store.removeUser(organizationId, currentUser().userId) }
  }

  @Test
  fun `removeUser throws exception if user is not in the organization`() {
    insertUser()

    assertThrows<UserNotFoundException> { store.removeUser(organizationId, currentUser().userId) }
  }

  @Test
  fun `removeUser removes user from requested organization and projects`() {
    val model = organizationUserModel(projectIds = listOf(projectId))
    configureUser(model)

    val otherOrgId = OrganizationId(5)
    val otherProjectId = ProjectId(50)
    insertOrganization(otherOrgId)
    insertProject(otherProjectId, organizationId = otherOrgId)
    insertOrganizationUser(model.userId, otherOrgId)
    insertProjectUser(model.userId, otherProjectId)

    store.removeUser(organizationId, model.userId)

    val projects =
        dslContext
            .select(PROJECT_USERS.PROJECT_ID)
            .from(PROJECT_USERS)
            .where(PROJECT_USERS.USER_ID.eq(model.userId))
            .fetch(PROJECT_USERS.PROJECT_ID)
    assertEquals(
        listOf(otherProjectId), projects, "User should still belong to project in other org")

    assertThrows<UserNotFoundException> { store.fetchUser(organizationId, model.userId) }
    assertNotNull(store.fetchUser(otherOrgId, model.userId))
  }

  private fun organizationUserModel(
      userId: UserId = UserId(100),
      email: String = "x@y.com",
      firstName: String = "First",
      lastName: String = "Last",
      userType: UserType = UserType.Individual,
      createdTime: Instant = clock.instant(),
      organizationId: OrganizationId = this.organizationId,
      role: Role = Role.CONTRIBUTOR,
      pendingInvitationTime: Instant? = null,
      projectIds: List<ProjectId> = emptyList()
  ): OrganizationUserModel {
    return OrganizationUserModel(
        userId,
        email,
        firstName,
        lastName,
        userType,
        createdTime,
        organizationId,
        role,
        pendingInvitationTime,
        projectIds)
  }

  private fun configureUser(model: OrganizationUserModel) {
    insertUser(model.userId, null, model.email, model.firstName, model.lastName, model.userType)
    insertOrganizationUser(
        model.userId, model.organizationId, model.role, model.pendingInvitationTime)
    model.projectIds.forEach { insertProjectUser(model.userId, it) }
  }
}
