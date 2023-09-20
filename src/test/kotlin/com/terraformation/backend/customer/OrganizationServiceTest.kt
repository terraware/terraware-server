package com.terraformation.backend.customer

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.auth.InMemoryKeycloakAdminClient
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.OrganizationAbandonedEvent
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.event.UserAddedToTerrawareEvent
import com.terraformation.backend.customer.event.UserDeletionStartedEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationHasOtherUsersException
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationUsersRow
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationsRow
import com.terraformation.backend.db.default_schema.tables.records.OrganizationUsersRecord
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.dummyKeycloakInfo
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.jobrunr.jobs.JobId
import org.jobrunr.jobs.lambdas.IocJobLambda
import org.jobrunr.scheduling.JobScheduler
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException

internal class OrganizationServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser(UserId(200))
  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(ORGANIZATIONS)

  @Autowired private lateinit var config: TerrawareServerConfig

  private val clock = TestClock()
  private lateinit var organizationStore: OrganizationStore
  private lateinit var parentStore: ParentStore
  private val publisher = TestEventPublisher()
  private val scheduler: JobScheduler = mockk()
  private lateinit var userStore: UserStore

  private lateinit var service: OrganizationService

  @BeforeEach
  fun setUp() {
    parentStore = ParentStore(dslContext)
    organizationStore = OrganizationStore(clock, dslContext, organizationsDao, publisher)
    userStore =
        UserStore(
            clock,
            config,
            dslContext,
            mockk(),
            InMemoryKeycloakAdminClient(),
            dummyKeycloakInfo(),
            organizationStore,
            parentStore,
            PermissionStore(dslContext),
            publisher,
            usersDao,
        )

    service =
        OrganizationService(
            dslContext, organizationStore, publisher, scheduler, SystemUser(usersDao), userStore)

    every { user.canCreateFacility(any()) } returns true
    every { user.canCreateStorageLocation(any()) } returns true
    every { user.canDeleteOrganization(any()) } returns true
    every { user.canListOrganizationUsers(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadStorageLocation(any()) } returns true
    every { user.canRemoveOrganizationUser(any(), any()) } returns true
  }

  @Test
  fun `deleteOrganization throws exception if organization has users other than the current one`() {
    val otherUserId = UserId(100)

    insertUser(user.userId)
    insertUser(otherUserId)
    insertOrganization()
    insertOrganizationUser(role = Role.Owner)
    insertOrganizationUser(otherUserId)

    assertThrows<OrganizationHasOtherUsersException> { service.deleteOrganization(organizationId) }
  }

  @Test
  fun `deleteOrganization throws exception if user has no permission to delete organization`() {
    val organizationId = OrganizationId(1)
    every { user.canDeleteOrganization(organizationId) } returns false

    assertThrows<AccessDeniedException> { service.deleteOrganization(organizationId) }
  }

  @Test
  fun `deleteOrganization removes current user from organization`() {
    insertUser()
    insertOrganization()
    insertOrganizationUser(role = Role.Owner)

    service.deleteOrganization(organizationId)

    val expected = emptyList<OrganizationUsersRecord>()
    val actual = dslContext.selectFrom(ORGANIZATION_USERS).fetch()
    assertEquals(expected, actual)
  }

  @Test
  fun `deleteOrganization publishes event on success`() {
    insertUser()
    insertOrganization()
    insertOrganizationUser(role = Role.Owner)

    service.deleteOrganization(organizationId)

    publisher.assertEventPublished(OrganizationAbandonedEvent(organizationId))
  }

  @Test
  fun `UserDeletionStartedEvent handler removes user from all their organizations`() {
    val otherUserId = UserId(100)
    val soloOrganizationId1 = OrganizationId(1)
    val soloOrganizationId2 = OrganizationId(2)
    val sharedOrganizationId = OrganizationId(3)
    val unrelatedOrganizationId = OrganizationId(4)

    insertUser(user.userId)
    insertUser(otherUserId)

    insertOrganization(soloOrganizationId1)
    insertOrganization(soloOrganizationId2)
    insertOrganization(sharedOrganizationId)
    insertOrganization(unrelatedOrganizationId)

    insertOrganizationUser(user.userId, soloOrganizationId1, Role.Owner)
    insertOrganizationUser(user.userId, soloOrganizationId2, Role.Owner)
    insertOrganizationUser(user.userId, sharedOrganizationId, Role.Owner)
    insertOrganizationUser(otherUserId, sharedOrganizationId, Role.Contributor)
    insertOrganizationUser(otherUserId, unrelatedOrganizationId, Role.Owner)

    service.on(UserDeletionStartedEvent(user.userId))

    val expectedOrganizationUsers =
        setOf(
            OrganizationUsersRow(
                otherUserId,
                sharedOrganizationId,
                Role.Contributor,
                Instant.EPOCH,
                Instant.EPOCH,
                user.userId,
                user.userId),
            OrganizationUsersRow(
                otherUserId,
                unrelatedOrganizationId,
                Role.Owner,
                Instant.EPOCH,
                Instant.EPOCH,
                user.userId,
                user.userId),
        )

    assertEquals(
        expectedOrganizationUsers,
        organizationUsersDao.findAll().toSet(),
        "User should be removed from organizations, but other user should remain")

    publisher.assertExactEventsPublished(
        setOf(
            OrganizationAbandonedEvent(soloOrganizationId1),
            OrganizationAbandonedEvent(soloOrganizationId2)))
  }

  @Test
  fun `event listeners are annotated correctly`() {
    assertIsEventListener<OrganizationAbandonedEvent>(service)
    assertIsEventListener<UserDeletionStartedEvent>(service)
  }

  @Test
  fun `OrganizationAbandonedEvent listener schedules deletion of organization data`() {
    // Capture the scheduled function so we can verify the end result. We don't need to test whether
    // or not JobRunr itself correctly runs scheduled jobs in this test, but we do need to test that
    // the scheduled job does what it's supposed to.
    val slot = slot<IocJobLambda<OrganizationService>>()
    every { scheduler.enqueue(capture(slot)) } answers { JobId(UUID.randomUUID()) }

    insertSiteData()

    service.on(OrganizationAbandonedEvent(organizationId))

    assertNotEquals(
        emptyList<OrganizationsRow>(),
        organizationsDao.findAll(),
        "Should not have deleted organization synchronously")

    verify { scheduler.enqueue<OrganizationService>(any()) }

    // Now run the job that was just enqueued.
    slot.captured.accept(service)

    assertEquals(
        emptyList<OrganizationsRow>(),
        organizationsDao.findAll(),
        "Scheduled job should have deleted organization")

    publisher.assertEventPublished(OrganizationDeletionStartedEvent(organizationId))
  }

  @Test
  fun `UserAddedToOrganization event is published when existing user is added to an organization`() {
    val otherUserId = UserId(100)
    val organizationId = OrganizationId(1)

    insertUser(user.userId)
    insertUser(otherUserId, email = "existingUser@email.com")

    insertOrganization(organizationId)

    every { user.canAddOrganizationUser(organizationId) } returns true
    every { user.canSetOrganizationUserRole(organizationId, Role.Contributor) } returns true
    service.addUser(
        email = "existingUser@email.com", organizationId = organizationId, role = Role.Contributor)

    publisher.assertExactEventsPublished(
        setOf(
            UserAddedToOrganizationEvent(
                userId = otherUserId, organizationId = organizationId, addedBy = user.userId)))
  }

  @Test
  fun `UserAddedToTerraware event is published when new user is added to an organization`() {
    val newUserEmail = "newuser@email.com"
    val organizationId = OrganizationId(1)

    insertUser(user.userId)
    insertOrganization(organizationId)

    every { user.canAddOrganizationUser(organizationId) } returns true
    every { user.canSetOrganizationUserRole(organizationId, Role.Contributor) } returns true

    service.addUser(email = newUserEmail, organizationId = organizationId, role = Role.Contributor)

    val newUser = userStore.fetchByEmail(newUserEmail)
    assertNotNull(newUser, "New user does not exist")

    publisher.assertExactEventsPublished(
        setOf(
            UserAddedToTerrawareEvent(
                userId = newUser!!.userId, organizationId = organizationId, addedBy = user.userId)))
  }

  @Test
  fun `assigning a Terraformation Contact throws exception without permission`() {
    every { user.canAddTerraformationContact(organizationId) } returns false
    assertThrows<AccessDeniedException> {
      service.assignTerraformationContact("tfcontact@terraformation.com", organizationId)
    }
  }

  @Test
  fun `assigns a brand new Terraformation Contact`() {
    insertUser(user.userId)
    insertOrganization(organizationId)

    assertNull(
        organizationStore.fetchTerraformationContact(organizationId),
        "Should not find a Terraformation Contact")

    every { user.canAddTerraformationContact(organizationId) } returns true

    val result = service.assignTerraformationContact("tfcontact@terraformation.com", organizationId)
    assertNotNull(result, "Should have a valid result")
    assertEquals(
        organizationStore.fetchTerraformationContact(organizationId),
        result,
        "Should find a matching Terraformation Contact")
  }

  @Test
  fun `removes existing Terraformation Contact and assigns a new one`() {
    insertUser(user.userId)
    insertOrganization(organizationId)

    every { user.canAddTerraformationContact(organizationId) } returns true
    every { user.canRemoveTerraformationContact(organizationId) } returns true

    val userToRemove =
        service.assignTerraformationContact("tfcontact@terraformation.com", organizationId)
    assertNotNull(userToRemove, "Should have a valid result")
    val reassignedUser =
        service.assignTerraformationContact("tfcontactnew@terraformation.com", organizationId)
    assertNotNull(reassignedUser, "Should have a valid new result")
    assertEquals(
        organizationStore.fetchTerraformationContact(organizationId),
        reassignedUser,
        "Should find a matching Terraformation Contact")
    assertThrows<UserNotFoundException> {
      organizationStore.fetchUser(organizationId, userToRemove)
    }
  }

  @Test
  fun `removes existing Terraformation Contact and sets the role for reassigned Terraformation Contact if user already exists`() {
    insertUser(user.userId)
    insertOrganization(organizationId)

    every { user.canAddTerraformationContact(organizationId) } returns true
    every { user.canRemoveTerraformationContact(organizationId) } returns true
    every { user.canSetTerraformationContact(organizationId) } returns true
    every { user.canAddOrganizationUser(organizationId) } returns true
    every { user.canSetOrganizationUserRole(organizationId, Role.Admin) } returns true

    val adminUser = service.addUser("admin@terraformation.com", organizationId, Role.Admin)
    assertNotNull(adminUser, "Should have a valid result")
    val userToRemove =
        service.assignTerraformationContact("tfcontact@terraformation.com", organizationId)
    assertNotNull(userToRemove, "Should have a valid result")
    val reassignedUser =
        service.assignTerraformationContact("admin@terraformation.com", organizationId)
    assertEquals(adminUser, reassignedUser, "Should reassign role on existing user")
    assertEquals(
        organizationStore.fetchTerraformationContact(organizationId),
        reassignedUser,
        "Should find a matching Terraformation Contact")
    assertThrows<UserNotFoundException> {
      organizationStore.fetchUser(organizationId, userToRemove)
    }
  }
}
