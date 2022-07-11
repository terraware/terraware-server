package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.OrganizationUserModel
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.CannotRemoveLastOwnerException
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityConnectionState
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.pojos.OrganizationsRow
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.PROJECT_USERS
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class OrganizationStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(ORGANIZATIONS)

  private val clock: Clock = mockk()
  private lateinit var permissionStore: PermissionStore
  private lateinit var store: OrganizationStore

  private val facilityModel =
      FacilityModel(
          connectionState = FacilityConnectionState.NotConnected,
          createdTime = Instant.EPOCH,
          description = "Description $facilityId",
          id = facilityId,
          lastTimeseriesTime = null,
          maxIdleMinutes = 30,
          modifiedTime = Instant.EPOCH,
          name = "Facility $facilityId",
          organizationId = organizationId,
          type = FacilityType.SeedBank)
  private val organizationModel =
      OrganizationModel(
          id = organizationId,
          name = "Organization $organizationId",
          countryCode = "US",
          countrySubdivisionCode = "US-HI",
          createdTime = Instant.EPOCH,
          facilities = listOf(facilityModel),
          totalUsers = 0)

  @BeforeEach
  fun setUp() {
    permissionStore = PermissionStore(dslContext)
    store = OrganizationStore(clock, dslContext, organizationsDao)

    every { clock.instant() } returns Instant.EPOCH

    every { user.canReadOrganization(any()) } returns true
    every { user.canUpdateOrganization(any()) } returns true
    every { user.canAddOrganizationUser(any()) } returns true
    every { user.canListOrganizationUsers(any()) } returns true
    every { user.canRemoveOrganizationUser(any(), any()) } returns true
    every { user.canSetOrganizationUserRole(any(), any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadSite(any()) } returns true
    every { user.canReadFacility(any()) } returns true

    every { user.facilityRoles } returns mapOf(facilityId to Role.OWNER)
    every { user.organizationRoles } returns mapOf(organizationId to Role.OWNER)
    every { user.projectRoles } returns mapOf(projectId to Role.OWNER)

    insertUser()
    insertOrganization(
        id = null,
        name = organizationModel.name,
        countryCode = organizationModel.countryCode,
        countrySubdivisionCode = organizationModel.countrySubdivisionCode)
    insertProject()
    insertSite()
    insertFacility()
  }

  @Test
  fun `fetchAll honors fetch depth`() {
    assertEquals(
        listOf(organizationModel),
        store.fetchAll(OrganizationStore.FetchDepth.Facility),
        "Fetch depth = Facility")

    assertEquals(
        listOf(organizationModel.copy(facilities = null)),
        store.fetchAll(OrganizationStore.FetchDepth.Organization),
        "Fetch depth = Organization")
  }

  @Test
  fun `fetchById honors fetch depth`() {
    assertEquals(
        organizationModel,
        store.fetchOneById(organizationId, OrganizationStore.FetchDepth.Facility),
        "Fetch depth = Facility")

    assertEquals(
        organizationModel.copy(facilities = null),
        store.fetchOneById(organizationId, OrganizationStore.FetchDepth.Organization),
        "Fetch depth = Organization")
  }

  @Test
  fun `fetchById requires user to be in the organization`() {
    every { user.organizationRoles } returns emptyMap()

    assertThrows<OrganizationNotFoundException> { store.fetchOneById(organizationId) }
  }

  @Test
  fun `fetchById returns correct total user count`() {
    val expectedTotalUsers = 10
    val baseUserId = 100

    (baseUserId until expectedTotalUsers + baseUserId).forEach { userId ->
      insertUser(userId)
      insertOrganizationUser(userId)
    }

    assertEquals(expectedTotalUsers, store.fetchOneById(organizationId).totalUsers)
  }

  @Test
  fun `fetchAll excludes organizations the user is not in`() {
    every { user.organizationRoles } returns emptyMap()

    assertEquals(emptyList<OrganizationModel>(), store.fetchAll())
  }

  @Test
  fun `fetchById excludes projects the user is not in`() {
    every { user.facilityRoles } returns emptyMap()
    every { user.projectRoles } returns emptyMap()

    val expected = organizationModel.copy(facilities = emptyList())

    val actual = store.fetchOneById(organizationId, OrganizationStore.FetchDepth.Facility)
    assertEquals(expected, actual)
  }

  @Test
  fun `fetchAll excludes projects the user is not in`() {
    every { user.facilityRoles } returns emptyMap()
    every { user.projectRoles } returns emptyMap()

    val expected = listOf(organizationModel.copy(facilities = emptyList()))

    val actual = store.fetchAll(OrganizationStore.FetchDepth.Facility)
    assertEquals(expected, actual)
  }

  @Test
  fun `createWithAdmin populates organization details`() {
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
            createdBy = user.userId,
            createdTime = clock.instant(),
            id = OrganizationId(2),
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
        )
    val actual = organizationsDao.fetchOneById(createdModel.id)!!

    assertEquals(expected, actual)
    assertEquals(1, createdModel.totalUsers, "Total users")
  }

  @Test
  fun `createWithAdmin folds country and subdivision codes to all-caps`() {
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
    val createdModel = store.createWithAdmin(OrganizationsRow(name = "Test Org"))
    val roles = permissionStore.fetchOrganizationRoles(user.userId)

    assertEquals(mapOf(createdModel.id to Role.OWNER), roles)
  }

  @Test
  fun `update populates organization details`() {
    val newTime = clock.instant().plusSeconds(1000)
    every { clock.instant() } returns newTime

    val newUserId = UserId(101)
    insertUser(newUserId)

    val updates =
        OrganizationsRow(
            id = organizationId,
            name = "New Name",
            description = "New Description",
            countryCode = "ZA")
    val expected =
        updates.copy(
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            modifiedBy = newUserId,
            modifiedTime = newTime,
        )

    every { user.userId } returns newUserId

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
                emptyList()),
            OrganizationUserModel(
                UserId(101),
                "user2@x.com",
                "First2",
                "Last2",
                UserType.SuperAdmin,
                clock.instant(),
                organizationId,
                Role.CONTRIBUTOR,
                listOf(projectId, otherProjectId)),
        )

    insertProject(otherProjectId)
    expected.forEach { configureUser(it) }
    configureUser(
        OrganizationUserModel(
            UserId(102),
            "balena-12345",
            "Api",
            "Client",
            UserType.APIClient,
            clock.instant(),
            organizationId,
            Role.CONTRIBUTOR,
            emptyList()))

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
            emptyList())
    configureUser(model)

    val expected = listOf(model)
    val actual = store.fetchUsers(organizationId)

    assertEquals(expected, actual)
  }

  @Test
  fun `fetchApiClients returns information about API client users`() {
    val otherProjectId = ProjectId(11)
    val expected =
        listOf(
            OrganizationUserModel(
                UserId(100),
                "balena-12345",
                "Api",
                "Client",
                UserType.APIClient,
                clock.instant(),
                organizationId,
                Role.CONTRIBUTOR,
                emptyList()))

    insertProject(otherProjectId)
    expected.forEach { configureUser(it) }
    configureUser(
        OrganizationUserModel(
            UserId(101),
            "user1@x.com",
            "First1",
            "Last1",
            UserType.Individual,
            clock.instant(),
            organizationId,
            Role.ADMIN,
            emptyList()),
    )

    val actual = store.fetchApiClients(organizationId)

    assertEquals(expected, actual)
  }

  @Test
  fun `addUser sets user role`() {
    val newUserId = UserId(3)
    insertUser(newUserId)

    store.addUser(organizationId, newUserId, Role.CONTRIBUTOR)

    val model = store.fetchUser(organizationId, newUserId)
    assertEquals(Role.CONTRIBUTOR, model.role)
  }

  @Test
  fun `addUser throws exception if no permission to add users`() {
    every { user.canAddOrganizationUser(organizationId) } returns false

    assertThrows<AccessDeniedException> {
      store.addUser(organizationId, currentUser().userId, Role.CONTRIBUTOR)
    }
  }

  @Test
  fun `removeUser throws exception if no permission to remove users`() {
    every { user.canRemoveOrganizationUser(organizationId, currentUser().userId) } returns false

    assertThrows<AccessDeniedException> { store.removeUser(organizationId, currentUser().userId) }
  }

  @Test
  fun `removeUser throws exception if user is not in the organization`() {
    assertThrows<UserNotFoundException> { store.removeUser(organizationId, currentUser().userId) }
  }

  @Test
  fun `removeUser removes user from requested organization and projects`() {
    val model = organizationUserModel(projectIds = listOf(projectId))
    configureUser(model)

    val otherOrgId = OrganizationId(5)
    val otherProjectId = ProjectId(50)
    insertOrganization(otherOrgId)
    insertProject(otherProjectId, otherOrgId)
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

  @Test
  fun `removeUser allows removing owner if there is another owner`() {
    val owner1 = organizationUserModel(userId = UserId(100), role = Role.OWNER)
    val owner2 = organizationUserModel(userId = UserId(101), role = Role.OWNER)
    configureUser(owner1)
    configureUser(owner2)

    store.removeUser(organizationId, owner1.userId)

    assertThrows<UserNotFoundException> { store.fetchUser(organizationId, owner1.userId) }
  }

  @Test
  fun `removeUser does not allow removing the only owner`() {
    val owner = organizationUserModel(userId = UserId(100), role = Role.OWNER)
    val admin = organizationUserModel(userId = UserId(101), role = Role.ADMIN)
    configureUser(owner)
    configureUser(admin)

    assertThrows<CannotRemoveLastOwnerException> { store.removeUser(organizationId, owner.userId) }
  }

  @Test
  fun `setUserRole allows demoting owner if there is another owner`() {
    val owner1 = organizationUserModel(userId = UserId(100), role = Role.OWNER)
    val owner2 = organizationUserModel(userId = UserId(101), role = Role.OWNER)
    configureUser(owner1)
    configureUser(owner2)

    store.setUserRole(organizationId, owner1.userId, Role.ADMIN)

    assertEquals(Role.ADMIN, store.fetchUser(organizationId, owner1.userId).role)
  }

  @Test
  fun `setUserRole does not allow demoting the only owner`() {
    val owner = organizationUserModel(userId = UserId(100), role = Role.OWNER)
    val admin = organizationUserModel(userId = UserId(101), role = Role.ADMIN)
    configureUser(owner)
    configureUser(admin)

    assertThrows<CannotRemoveLastOwnerException> {
      store.setUserRole(organizationId, owner.userId, Role.ADMIN)
    }
  }

  @Test
  fun `countRoleUsers includes counts for roles with no users`() {
    listOf(Role.OWNER, Role.OWNER, Role.CONTRIBUTOR).forEachIndexed { index, role ->
      configureUser(organizationUserModel(userId = UserId(index + 100L), role = role))
    }

    val expected =
        mapOf(
            Role.ADMIN to 0,
            Role.MANAGER to 0,
            Role.CONTRIBUTOR to 1,
            Role.OWNER to 2,
        )
    val actual = store.countRoleUsers(organizationId)

    assertEquals(expected, actual)
  }

  @Test
  fun `countRoleUsers requires permission to list users`() {
    every { user.canListOrganizationUsers(organizationId) } returns false

    assertThrows<AccessDeniedException> { store.countRoleUsers(organizationId) }
  }

  @Test
  fun `fetchEmailRecipients returns addresses of opted-in users`() {
    val otherOrganizationId = OrganizationId(2)
    val optedInNonMember = UserId(100)
    val optedInMember = UserId(101)
    val optedOutMember = UserId(102)

    insertOrganization(otherOrganizationId)

    insertUser(optedInNonMember, email = "optedInNonMember@x.com", emailNotificationsEnabled = true)
    insertUser(optedInMember, email = "optedInMember@x.com", emailNotificationsEnabled = true)
    insertUser(optedOutMember, email = "optedOutMember@x.com")

    insertOrganizationUser(optedInNonMember, otherOrganizationId)
    insertOrganizationUser(optedInMember, organizationId)
    insertOrganizationUser(optedOutMember, organizationId)

    val expected = setOf("optedInMember@x.com")
    val actual = store.fetchEmailRecipients(organizationId).toSet()

    assertEquals(expected, actual)
  }

  private fun organizationUserModel(
      userId: UserId = UserId(100),
      email: String = "$userId@y.com",
      firstName: String = "First",
      lastName: String = "Last",
      userType: UserType = UserType.Individual,
      createdTime: Instant = clock.instant(),
      organizationId: OrganizationId = this.organizationId,
      role: Role = Role.CONTRIBUTOR,
      projectIds: List<ProjectId> = emptyList(),
  ): OrganizationUserModel {
    return OrganizationUserModel(
        userId, email, firstName, lastName, userType, createdTime, organizationId, role, projectIds)
  }

  private fun configureUser(model: OrganizationUserModel) {
    insertUser(model.userId, null, model.email, model.firstName, model.lastName, model.userType)
    insertOrganizationUser(model.userId, model.organizationId, model.role)
    model.projectIds.forEach { insertProjectUser(model.userId, it) }
  }
}
