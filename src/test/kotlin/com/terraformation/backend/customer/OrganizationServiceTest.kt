package com.terraformation.backend.customer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.OrganizationAbandonedEvent
import com.terraformation.backend.customer.event.UserDeletionStartedEvent
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationHasOtherUsersException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.pojos.OrganizationUsersRow
import com.terraformation.backend.db.tables.records.OrganizationUsersRecord
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.mockUser
import io.mockk.Runs
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.keycloak.admin.client.resource.RealmResource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.AccessDeniedException

internal class OrganizationServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(ORGANIZATIONS)

  @Autowired private lateinit var config: TerrawareServerConfig

  private val clock: Clock = mockk()
  private lateinit var organizationStore: OrganizationStore
  private lateinit var parentStore: ParentStore
  private val publisher: ApplicationEventPublisher = mockk()
  private val realmResource: RealmResource = mockk()
  private lateinit var userStore: UserStore

  private lateinit var service: OrganizationService

  @BeforeEach
  fun setUp() {
    every { realmResource.users() } returns mockk()

    parentStore = ParentStore(dslContext)
    organizationStore = OrganizationStore(clock, dslContext, organizationsDao)
    userStore =
        UserStore(
            clock,
            config,
            dslContext,
            mockk(),
            mockk(),
            jacksonObjectMapper(),
            organizationStore,
            parentStore,
            PermissionStore(dslContext),
            publisher,
            realmResource,
            usersDao,
        )

    service = OrganizationService(dslContext, organizationStore, publisher, userStore)

    every { clock.instant() } returns Instant.EPOCH
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
    insertOrganizationUser(role = Role.OWNER)
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
    insertOrganizationUser(role = Role.OWNER)

    every { publisher.publishEvent(any<OrganizationAbandonedEvent>()) } just Runs

    service.deleteOrganization(organizationId)

    val expected = emptyList<OrganizationUsersRecord>()
    val actual = dslContext.selectFrom(ORGANIZATION_USERS).fetch()
    assertEquals(expected, actual)
  }

  @Test
  fun `deleteOrganization publishes event on success`() {
    insertUser()
    insertOrganization()
    insertOrganizationUser(role = Role.OWNER)

    every { publisher.publishEvent(any<OrganizationAbandonedEvent>()) } just Runs

    service.deleteOrganization(organizationId)

    verify { publisher.publishEvent(OrganizationAbandonedEvent(organizationId)) }
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

    insertOrganizationUser(user.userId, soloOrganizationId1, Role.OWNER)
    insertOrganizationUser(user.userId, soloOrganizationId2, Role.OWNER)
    insertOrganizationUser(user.userId, sharedOrganizationId, Role.OWNER)
    insertOrganizationUser(otherUserId, sharedOrganizationId, Role.CONTRIBUTOR)
    insertOrganizationUser(otherUserId, unrelatedOrganizationId, Role.OWNER)

    every { publisher.publishEvent(any<OrganizationAbandonedEvent>()) } just Runs

    service.on(UserDeletionStartedEvent(user.userId))

    val expectedOrganizationUsers =
        setOf(
            OrganizationUsersRow(
                otherUserId,
                sharedOrganizationId,
                Role.CONTRIBUTOR.id,
                Instant.EPOCH,
                Instant.EPOCH,
                user.userId,
                user.userId),
            OrganizationUsersRow(
                otherUserId,
                unrelatedOrganizationId,
                Role.OWNER.id,
                Instant.EPOCH,
                Instant.EPOCH,
                user.userId,
                user.userId),
        )

    assertEquals(
        expectedOrganizationUsers,
        organizationUsersDao.findAll().toSet(),
        "User should be removed from organizations, but other user should remain")

    verify { publisher.publishEvent(OrganizationAbandonedEvent(soloOrganizationId1)) }
    verify { publisher.publishEvent(OrganizationAbandonedEvent(soloOrganizationId2)) }
    confirmVerified(publisher)
  }
}
