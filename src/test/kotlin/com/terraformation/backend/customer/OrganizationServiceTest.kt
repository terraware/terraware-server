package com.terraformation.backend.customer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.OrganizationDeletedEvent
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationHasOtherUsersException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.records.OrganizationUsersRecord
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.mockUser
import io.mockk.Runs
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
    get() = listOf(ORGANIZATIONS, PROJECTS, SITES, FACILITIES)

  @Autowired private lateinit var config: TerrawareServerConfig

  private val clock: Clock = mockk()
  private lateinit var organizationStore: OrganizationStore
  private lateinit var parentStore: ParentStore
  private lateinit var projectStore: ProjectStore
  private val publisher: ApplicationEventPublisher = mockk()
  private val realmResource: RealmResource = mockk()
  private lateinit var userStore: UserStore

  private lateinit var service: OrganizationService

  @BeforeEach
  fun setUp() {
    every { realmResource.users() } returns mockk()

    parentStore = ParentStore(dslContext)
    organizationStore = OrganizationStore(clock, dslContext, organizationsDao)
    projectStore = ProjectStore(clock, dslContext, projectsDao, projectTypeSelectionsDao)
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
            realmResource,
            usersDao,
        )

    service = OrganizationService(dslContext, organizationStore, projectStore, publisher, userStore)

    every { clock.instant() } returns Instant.EPOCH
    every { user.canCreateFacility(any()) } returns true
    every { user.canCreateProject(any()) } returns true
    every { user.canCreateSite(any()) } returns true
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

    every { publisher.publishEvent(any<OrganizationDeletedEvent>()) } just Runs

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

    every { publisher.publishEvent(any<OrganizationDeletedEvent>()) } just Runs

    service.deleteOrganization(organizationId)

    verify { publisher.publishEvent(OrganizationDeletedEvent(organizationId)) }
  }
}
